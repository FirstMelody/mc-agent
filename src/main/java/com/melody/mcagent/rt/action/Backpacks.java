package com.melody.mcagent.rt.action;

import java.lang.reflect.Method;
import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sophisticated Backpacks support, by reflection on purpose.
 *
 * <p>The dev server does not have this mod and {@code build.gradle} does not compile against it, so an
 * {@code import net.p3pp3rf1y...} would not build - and hard-wiring one mod's classes into a runtime
 * that also runs on servers without it is the wrong shape anyway. Everything here is looked up
 * lazily, cached by the JVM, and every path fails soft: with the mod absent the tools say so honestly
 * instead of throwing.
 *
 * <p>The backpack belongs in the Curios {@code back} slot, where it costs no armour or offhand slot.
 * That slot is read first; only then does this look in the bot's own inventory, which is the state a
 * backpack is in before somebody equips it.
 *
 * <p>Two facts verified against the shipped jars, because they decide how much code exists here:
 * {@code BackpackWrapper.fromStack(stack)} is the factory, and {@code IStorageWrapper} - the
 * sophisticatedcore interface it extends - already provides {@code sort()}, {@code getSortBy()} /
 * {@code setSortBy(SortBy)}, {@code getInventoryHandler()} and {@code getUpgradeHandler()}. Sorting is
 * therefore the mod's own algorithm rather than something reimplemented here.
 */
public final class Backpacks {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/backpacks");

    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String WRAPPER =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";
    private static final String BACKPACK_ITEM =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem";
    /** The Curios slot the operator wants the backpack in. */
    private static final String SLOT = "back";

    private Backpacks() {
    }

    /** Whether the mod is present in this JVM at all. */
    public static boolean available() {
        return lookup(WRAPPER) != null;
    }

    /**
     * The bot's backpack, or null when it has none.
     *
     * <p>Curios' {@code back} slot first: that is where it should be, and reading it needs no guess
     * about inventory indices.
     */
    @Nullable
    public static ItemStack equipped(ServerPlayer player) {
        ItemStack fromCurios = curiosBackSlot(player);
        if (fromCurios != null && !fromCurios.isEmpty()) {
            return fromCurios;
        }
        // Not equipped yet: a backpack carried in the ordinary inventory is still worth finding, so
        // the bot can be told to put it on rather than told it has none.
        for (ItemStack stack : player.getInventory().items) {
            if (isBackpack(stack)) {
                return stack;
            }
        }
        for (ItemStack stack : player.getInventory().armor) {
            if (isBackpack(stack)) {
                return stack;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isBackpack(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** Where the backpack currently is, for a legible tool reply. */
    public static String locationOf(ServerPlayer player, ItemStack backpack) {
        if (curiosBackSlot(player) == backpack) {
            return "in the Curios back slot";
        }
        return "carried in the inventory (not equipped in the back slot yet)";
    }

    private static boolean isBackpack(ItemStack stack) {
        Class<?> item = lookup(BACKPACK_ITEM);
        return !stack.isEmpty() && item != null && item.isInstance(stack.getItem());
    }

    /** The stack in Curios' {@code back} slot, or null when Curios, the slot or the item is absent. */
    @Nullable
    private static ItemStack curiosBackSlot(ServerPlayer player) {
        IItemHandler back = curiosBackHandler(player);
        return back != null && back.getSlots() > 0 ? back.getStackInSlot(0) : null;
    }

    /** Curios' {@code back} slot as a handler, or null when Curios or that slot is absent. */
    @Nullable
    private static IItemHandler curiosBackHandler(ServerPlayer player) {
        try {
            Class<?> api = lookup(CURIOS_API);
            if (api == null) {
                return null;
            }
            Object inventory = unwrap(invokeStatic(api, "getCuriosInventory", player));
            if (inventory == null) {
                return null;
            }
            Object stacksHandler = unwrap(invoke(inventory, "getStacksHandler", SLOT));
            if (stacksHandler == null) {
                return null;
            }
            Object stacks = invoke(stacksHandler, "getStacks");
            return stacks instanceof IItemHandler slots ? slots : null;
        } catch (Throwable t) {
            LOG.debug("Could not read the Curios back slot: {}", t.toString());
            return null;
        }
    }

    /**
     * Wear the backpack in the Curios {@code back} slot.
     *
     * <p>Right-clicking a block does not do this - the bot tried and the server answered "the block
     * did not accept that item" - and wearing it as armour or in the offhand is exactly what the
     * operator does not want. So the stack is moved into the back slot directly, and an occupied back
     * slot is refused rather than overwritten: that slot may hold somebody's own curio.
     */
    public static String equip(ServerPlayer player, String requested) {
        IItemHandler back = curiosBackHandler(player);
        if (back == null || back.getSlots() == 0) {
            return "failed: this server has no Curios back slot to wear a backpack in";
        }
        ItemStack worn = back.getStackInSlot(0);
        String wanted = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (!worn.isEmpty() && !isBackpack(worn)) {
            return "failed: your back slot is occupied by "
                    + worn.getHoverName().getString() + "; move that first";
        }
        if (!wanted.isEmpty() && isBackpack(worn) && matches(worn, wanted)) {
            return "your " + worn.getHoverName().getString() + " is already worn in the back slot";
        }
        if (!(back instanceof net.neoforged.neoforge.items.IItemHandlerModifiable modifiable)) {
            return "failed: the back slot cannot be written to on this server";
        }
        // Which backpack the bot actually asked for. Reading the request matters: the first version
        // of this ignored it, so a bot handed a better backpack was told "already worn" forever and
        // retried in a loop - a player saw it stand still, burning a planning turn every 20 seconds.
        int from = -1;
        java.util.List<String> carried = new java.util.ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!isBackpack(stack) || stack == worn) {
                continue;
            }
            carried.add(stack.getHoverName().getString());
            if (wanted.isEmpty() || matches(stack, wanted)) {
                if (from < 0) {
                    from = slot;
                }
                if (!wanted.isEmpty()) {
                    break;
                }
            }
        }
        // No name given and more than one backpack carried: do not guess. Picking "the first one"
        // swapped a plain backpack back in for the diamond one a player had just handed over, and the
        // bot kept re-reading its backpack trying to work out why. Naming the candidates is something
        // the model can act on; a coin flip is not.
        if (wanted.isEmpty() && carried.size() > 1) {
            return "failed: you are carrying " + carried.size() + " backpacks ("
                    + String.join(", ", carried) + "); say which one to wear";
        }
        if (from < 0) {
            if (!wanted.isEmpty()) {
                return "failed: you are not carrying '" + requested + "' to wear";
            }
            return worn.isEmpty() ? "failed: you are not carrying a backpack to wear"
                    : "your back slot already holds " + worn.getHoverName().getString()
                            + "; name the backpack you want to swap in";
        }
        // Swapping needs somewhere to put the old one, so refuse rather than drop it on the floor.
        if (!worn.isEmpty() && !hasRoomFor(player, worn)) {
            return "failed: no room in your inventory to take the worn "
                    + worn.getHoverName().getString() + " back; put something away first";
        }
        ItemStack toWear = player.getInventory().getItem(from).copyWithCount(1);
        player.getInventory().getItem(from).shrink(1);
        String previous = "";
        if (!worn.isEmpty()) {
            placeInInventory(player, worn.copy());
            previous = "; your previous " + worn.getHoverName().getString()
                    + " went back into your inventory";
        }
        modifiable.setStackInSlot(0, toWear);
        return "wore " + toWear.getHoverName().getString()
                + " in the Curios back slot; it does not use an armour or offhand slot" + previous;
    }

    /** Whether one more copy of this stack would fit in the bot's own inventory. */
    private static boolean hasRoomFor(ServerPlayer player, ItemStack stack) {
        int space = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing.isEmpty()) {
                space += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                space += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (space >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    /** Place a stack into the bot's own inventory, splitting across slots as needed. */
    private static void placeInInventory(ServerPlayer player, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && !remaining.isEmpty();
                slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (existing.isEmpty()) {
                int place = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                player.getInventory().setItem(slot, remaining.copyWithCount(place));
                remaining.shrink(place);
            } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                int place = Math.min(existing.getMaxStackSize() - existing.getCount(),
                        remaining.getCount());
                if (place > 0) {
                    existing.grow(place);
                    remaining.shrink(place);
                }
            }
        }
    }

    /** The wrapper the mod's own API hands out for this stack, or null. */
    @Nullable
    private static Object wrapper(ItemStack backpack) {
        Class<?> factory = lookup(WRAPPER);
        if (factory == null || backpack == null || backpack.isEmpty()) {
            return null;
        }
        try {
            return factory.getMethod("fromStack", ItemStack.class).invoke(null, backpack);
        } catch (Throwable t) {
            LOG.debug("BackpackWrapper.fromStack failed: {}", t.toString());
            return null;
        }
    }

    /** Contents and upgrade slots, as text the model can act on. */
    public static String describe(ServerPlayer player, ItemStack backpack) {
        Object wrapper = wrapper(backpack);
        if (wrapper == null) {
            return "failed: that item is not a Sophisticated Backpack";
        }
        IItemHandler contents = handler(wrapper, "getInventoryHandler");
        if (contents == null) {
            return "failed: the backpack has no readable inventory";
        }
        StringBuilder out = new StringBuilder("Backpack ")
                .append(backpack.getHoverName().getString())
                .append(' ').append(locationOf(player, backpack)).append(":\n");
        int used = 0;
        StringBuilder items = new StringBuilder();
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack stack = contents.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            if (used <= 12) {
                items.append("\n  - ").append(stack.getCount()).append("x ")
                        .append(stack.getHoverName().getString());
            }
        }
        out.append("  ").append(used).append(" of ").append(contents.getSlots())
                .append(" slot(s) in use").append(items);
        if (used > 12) {
            out.append("\n  ... and ").append(used - 12).append(" more kind(s)");
        }
        IItemHandler upgrades = handler(wrapper, "getUpgradeHandler");
        if (upgrades != null) {
            StringBuilder list = new StringBuilder();
            int filled = 0;
            for (int slot = 0; slot < upgrades.getSlots(); slot++) {
                ItemStack stack = upgrades.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    filled++;
                    list.append("\n  - ").append(stack.getHoverName().getString());
                }
            }
            out.append("\n  upgrades: ").append(filled).append(" of ")
                    .append(upgrades.getSlots()).append(" slot(s) filled").append(list);
        }
        return out.toString();
    }

    /** Sort the backpack with the mod's own algorithm. */
    public static String sort(ItemStack backpack) {
        Object wrapper = wrapper(backpack);
        if (wrapper == null) {
            return "failed: that item is not a Sophisticated Backpack";
        }
        try {
            invoke(wrapper, "sort");
            return "sorted the backpack";
        } catch (Throwable t) {
            return "failed: the backpack refused to sort (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Move matching items between the bot's own inventory and the backpack.
     *
     * @param into true to put items into the backpack, false to take them back out
     */
    public static String move(ServerPlayer player, ItemStack backpack, boolean into, String requested,
                              int requestedCount) {
        Object wrapper = wrapper(backpack);
        IItemHandler contents = wrapper == null ? null : handler(wrapper, "getInventoryHandler");
        if (contents == null) {
            return "failed: that item is not a Sophisticated Backpack with a readable inventory";
        }
        String wanted = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        int limit = requestedCount <= 0 ? Integer.MAX_VALUE : requestedCount;
        int moved = 0;
        if (into) {
            for (int slot = 0; slot < player.getInventory().getContainerSize() && moved < limit;
                    slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || stack == backpack || !matches(stack, wanted)) {
                    continue;
                }
                ItemStack offer = stack.copyWithCount(Math.min(stack.getCount(), limit - moved));
                ItemStack leftover = insert(contents, offer);
                int accepted = offer.getCount() - leftover.getCount();
                if (accepted > 0) {
                    stack.shrink(accepted);
                    moved += accepted;
                }
            }
        } else {
            for (int slot = 0; slot < contents.getSlots() && moved < limit; slot++) {
                ItemStack stack = contents.getStackInSlot(slot);
                if (stack.isEmpty() || !matches(stack, wanted)) {
                    continue;
                }
                ItemStack taken = contents.extractItem(slot,
                        Math.min(stack.getCount(), limit - moved), false);
                if (taken.isEmpty()) {
                    continue;
                }
                // Placed by hand rather than with Inventory.add: a backpack slot can hold far more
                // than a vanilla stack once a stack upgrade is installed, so taking something out may
                // need several of the bot's own slots.
                ItemStack remaining = taken;
                for (int i = 0; i < player.getInventory().getContainerSize() && !remaining.isEmpty();
                        i++) {
                    ItemStack existing = player.getInventory().getItem(i);
                    if (existing.isEmpty()) {
                        int place = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                        player.getInventory().setItem(i, remaining.copyWithCount(place));
                        remaining.shrink(place);
                    } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                        int space = existing.getMaxStackSize() - existing.getCount();
                        int place = Math.min(space, remaining.getCount());
                        if (place > 0) {
                            existing.grow(place);
                            remaining.shrink(place);
                        }
                    }
                }
                if (!remaining.isEmpty()) {
                    contents.insertItem(slot, remaining, false);
                }
                moved += taken.getCount() - remaining.getCount();
            }
        }
        if (moved == 0) {
            return "failed: nothing " + (into ? "to put in" : "to take out")
                    + (wanted.isEmpty() ? "" : " matching '" + requested + "'");
        }
        return (into ? "put " : "took ") + moved + " item(s) " + (into ? "into" : "out of")
                + " the backpack";
    }

    /**
     * Move one upgrade item from the bot's inventory into a free backpack upgrade slot.
     *
     * <p>This is the second half of "craft it or be handed it": {@code craft} makes the stack upgrade,
     * this installs it. The item is only consumed once the backpack accepts it, so a full or
     * incompatible upgrade list loses nothing.
     */
    public static String insertUpgrade(ServerPlayer player, ItemStack backpack, String requested) {
        Object wrapper = wrapper(backpack);
        IItemHandler upgrades = wrapper == null ? null : handler(wrapper, "getUpgradeHandler");
        if (upgrades == null) {
            return "failed: this backpack has no readable upgrade slots";
        }
        String wanted = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !matches(stack, wanted)) {
                continue;
            }
            ItemStack offer = stack.copyWithCount(1);
            if (insert(upgrades, offer).isEmpty()) {
                stack.shrink(1);
                return "installed " + offer.getHoverName().getString()
                        + " into the backpack's upgrade slots";
            }
        }
        return "failed: no free upgrade slot would take "
                + (wanted.isEmpty() ? "any upgrade you are carrying" : "'" + requested + "'");
    }

    // --- reflection plumbing ------------------------------------------------------------------

    @Nullable
    private static IItemHandler handler(Object wrapper, String method) {
        try {
            Object value = invoke(wrapper, method);
            return value instanceof IItemHandler items ? items : null;
        } catch (Throwable t) {
            LOG.debug("Backpack {} is unavailable: {}", method, t.toString());
            return null;
        }
    }

    private static boolean matches(ItemStack stack, String wanted) {
        if (wanted.isEmpty()) {
            return true;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString().toLowerCase(java.util.Locale.ROOT);
        String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return id.contains(wanted) || name.contains(wanted);
    }

    /** Insert as much as fits, returning what was left over. */
    private static ItemStack insert(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
        }
        return remaining;
    }

    @Nullable
    private static Object invoke(Object target, String method, Object... args) throws Exception {
        Method found = find(target.getClass(), method, args.length);
        if (found == null) {
            return null;
        }
        found.setAccessible(true);
        return found.invoke(target, args);
    }

    @Nullable
    private static Object invokeStatic(Class<?> owner, String method, Object... args) throws Exception {
        Method found = find(owner, method, args.length);
        if (found == null) {
            return null;
        }
        found.setAccessible(true);
        return found.invoke(null, args);
    }

    /** Curios returns {@code Optional}; this turns one into its value or null. */
    @Nullable
    private static Object unwrap(@Nullable Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    @Nullable
    private static Method find(Class<?> owner, String name, int arity) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) {
                    return method;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Class<?> lookup(String name) {
        try {
            return Class.forName(name, false, Backpacks.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }
}
