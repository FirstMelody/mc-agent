package com.melody.mcagent.rt.action;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Reading and manipulating containers, gated on the bot having actually opened them.
 *
 * <p>Per the project's realism rule, a container's contents are <b>not</b> knowable from the
 * outside: a bot must open a chest before it may see what is inside, exactly as a player must. This
 * class therefore enforces the gate rather than merely performing the transfer — the perception
 * layer asks {@link #hasOpened} before it will report any contents.
 *
 * <p>Transfers deliberately move items directly between the container and the bot's inventory
 * rather than simulating slot-by-slot clicking. A real client does the latter, but the result is
 * identical and the direct route cannot desynchronise a menu the bot has no client to render.
 */
public final class Containers {

    private Containers() {
    }

    /**
     * Resolve the {@link Container} at a position, following double chests to their partner.
     *
     * @return the container, or null if the block is not one (or is a double chest whose partner is
     *         missing, which vanilla treats as unopenable)
     */
    @Nullable
    public static Container containerAt(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, bot.level(), pos, true);
        }
        BlockEntity blockEntity = bot.level().getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    /** Is this block something the bot could open? */
    public static boolean isContainer(ServerPlayer bot, BlockPos pos) {
        return containerAt(bot, pos) != null;
    }

    /**
     * Record that the bot has opened a container.
     *
     * <p>This is the realism gate: until this has been called for a position, the perception layer
     * reports the container as closed and reveals nothing about its contents.
     */
    public static void markOpened(ServerPlayer bot, BlockPos pos) {
        OpenedContainers.of(bot).add(pos.asLong());
    }

    /** Has the bot opened this container (and not since forgotten it)? */
    public static boolean hasOpened(ServerPlayer bot, BlockPos pos) {
        return OpenedContainers.of(bot).contains(pos.asLong());
    }

    /** Forget a container's contents, e.g. when the bot walks away. */
    public static void forget(ServerPlayer bot, BlockPos pos) {
        OpenedContainers.of(bot).remove(pos.asLong());
    }

    /**
     * Open a container: verify reach and validity, then mark it as observed.
     *
     * <p>We do not go through {@code player.openMenu}, because that would install a menu the bot
     * can never acknowledge and would leave a dangling {@code containerMenu}. What matters for the
     * realism rule is that the bot performed the act of opening, which is what we record.
     */
    public static Actions.Result open(ServerPlayer bot, BlockPos pos) {
        if (!Actions.canReach(bot, pos)) {
            return Actions.Result.fail("container is out of reach");
        }
        Container container = containerAt(bot, pos);
        if (container == null) {
            return Actions.Result.fail("there is no container at " + pos.toShortString());
        }
        // Vanilla's own validity check: distance and, for modded containers, whatever else the
        // block wants to require.
        if (!container.stillValid(bot)) {
            return Actions.Result.fail("the container refuses to open (too far, or blocked)");
        }

        Actions.lookAt(bot, Vec3.atCenterOf(pos));
        markOpened(bot, pos);

        var blockEntity = bot.level().getBlockEntity(pos);
        String name = blockEntity instanceof net.minecraft.world.Nameable nameable
                ? nameable.getDisplayName().getString()
                : bot.level().getBlockState(pos).getBlock().getName().getString();

        return Actions.Result.ok("opened " + name + " (" + container.getContainerSize() + " slots)");
    }

    /** A single item observed inside a container. */
    public record SlotContents(int slot, ItemStack stack) {
    }

    /**
     * List a container's contents — only if the bot has opened it.
     *
     * <p>Returns a failure rather than an empty list when the gate is closed, so a caller cannot
     * mistake "not opened" for "empty".
     */
    public static List<SlotContents> peek(ServerPlayer bot, BlockPos pos) {
        if (!hasOpened(bot, pos)) {
            return List.of();
        }
        Container container = containerAt(bot, pos);
        if (container == null) {
            return List.of();
        }
        List<SlotContents> out = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                out.add(new SlotContents(slot, stack));
            }
        }
        return out;
    }

    /** Move up to {@code count} of an item from a container into the bot's inventory. */
    public static Actions.Result withdraw(ServerPlayer bot, BlockPos pos, String itemId, int count) {
        if (!hasOpened(bot, pos)) {
            return Actions.Result.fail("the bot has not opened that container");
        }
        Container container = containerAt(bot, pos);
        if (container == null) {
            return Actions.Result.fail("there is no container at " + pos.toShortString());
        }
        if (!container.stillValid(bot)) {
            return Actions.Result.fail("the container is no longer accessible");
        }

        int remaining = count;
        int moved = 0;

        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !matches(stack, itemId)) {
                continue;
            }

            int take = Math.min(remaining, stack.getCount());
            ItemStack extracted = container.removeItem(slot, take);
            if (extracted.isEmpty()) {
                continue;
            }

            // Put only what fits in the inventory. Anything left must go back into the exact slot
            // it came from: a real inventory click does not eject the overflow onto the ground.
            ItemStack leftover = addToInventory(bot, extracted);
            if (!leftover.isEmpty()) {
                ItemStack inSlot = container.getItem(slot);
                if (inSlot.isEmpty()) {
                    container.setItem(slot, leftover);
                } else if (ItemStack.isSameItemSameComponents(inSlot, leftover)) {
                    inSlot.grow(leftover.getCount());
                    container.setItem(slot, inSlot);
                } else {
                    // Defensive fallback for a modded container that mutates itself during
                    // removeItem. Do not delete or drop anything: put it back wherever it fits.
                    ItemStack stillLeft = insertInto(container, leftover);
                    if (!stillLeft.isEmpty()) {
                        return Actions.Result.fail("the container changed while items were moved; "
                                + "nothing else was withdrawn");
                    }
                }
            }

            int actuallyMoved = take - leftover.getCount();
            moved += actuallyMoved;
            remaining -= actuallyMoved;
        }

        container.setChanged();

        if (moved == 0) {
            return Actions.Result.fail("no " + itemId + " in that container");
        }
        return Actions.Result.ok("withdrew " + moved + "x " + itemId);
    }

    /** Move up to {@code count} of an item from the bot's inventory into a container. */
    public static Actions.Result deposit(ServerPlayer bot, BlockPos pos, String itemId, int count) {
        if (!hasOpened(bot, pos)) {
            return Actions.Result.fail("the bot has not opened that container");
        }
        Container container = containerAt(bot, pos);
        if (container == null) {
            return Actions.Result.fail("there is no container at " + pos.toShortString());
        }
        if (!container.stillValid(bot)) {
            return Actions.Result.fail("the container is no longer accessible");
        }

        var inventory = bot.getInventory();
        int remaining = count;
        int moved = 0;

        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matches(stack, itemId)) {
                continue;
            }

            int give = Math.min(remaining, stack.getCount());
            ItemStack toStore = stack.copyWithCount(give);

            ItemStack leftover = insertInto(container, toStore);
            int actuallyMoved = give - leftover.getCount();
            if (actuallyMoved <= 0) {
                continue; // container full for this item
            }

            stack.shrink(actuallyMoved);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            moved += actuallyMoved;
            remaining -= actuallyMoved;
        }

        container.setChanged();

        if (moved == 0) {
            return Actions.Result.fail("could not deposit " + itemId + " (none held, or container full)");
        }
        return Actions.Result.ok("deposited " + moved + "x " + itemId);
    }

    /** Add to the player's inventory, returning whatever did not fit. */
    public static ItemStack addToInventory(ServerPlayer bot, ItemStack stack) {
        var inventory = bot.getInventory();
        ItemStack remaining = stack.copy();

        // Top up existing matching stacks first, then use empty slots — the same order vanilla uses.
        // Slots 0..35 are the carried inventory. PlayerInventory also exposes armour and offhand
        // through getContainerSize(); writing arbitrary pickups there bypasses the slot rules and
        // can put a stack of stone in a helmet slot.
        int carriedSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < carriedSlots && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int space = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize()) - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, remaining.getCount());
            existing.grow(move);
            remaining.shrink(move);
        }

        for (int slot = 0; slot < carriedSlots && !remaining.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int move = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            inventory.setItem(slot, remaining.copyWithCount(move));
            remaining.shrink(move);
        }

        return remaining;
    }

    /** Whether at least one item from this stack can enter the carried 36-slot inventory. */
    public static boolean canFit(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        var inventory = bot.getInventory();
        int carriedSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < carriedSlots; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /** Insert as much as possible into a container, returning the part that did not fit. */
    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();

        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            if (!container.canPlaceItem(slot, remaining)) {
                continue;
            }
            int space = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, remaining.getCount());
            existing.grow(move);
            remaining.shrink(move);
            container.setItem(slot, existing);
        }

        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            if (!container.canPlaceItem(slot, remaining)) {
                continue;
            }
            int move = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            container.setItem(slot, remaining.copyWithCount(move));
            remaining.shrink(move);
        }

        return remaining;
    }

    /** Does a stack match an item id or a loose name fragment? */
    private static boolean matches(ItemStack stack, String query) {
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key.toString().equals(query)) {
            return true;
        }
        // Allow a bare path ("iron_ingot") as well as a full id ("minecraft:iron_ingot").
        if (key.getPath().equals(query)) {
            return true;
        }
        return stack.getHoverName().getString().equalsIgnoreCase(query);
    }

    /** Whether a double chest's other half is also within reach, as vanilla requires. */
    public static boolean doubleChestReachable(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE)) {
            return true;
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return true;
        }
        var partner = pos.relative(ChestBlock.getConnectedDirection(state));
        return Actions.canReach(bot, partner);
    }
}
