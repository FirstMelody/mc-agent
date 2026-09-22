package com.melody.mcagent.rt.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The whitelisted actions a bot may perform, executed through vanilla code paths.
 *
 * <p>Every action routes through the same server-side machinery a real player's packets would
 * reach, so other mods observe exactly what they would for a human: block breaking fires
 * {@code PlayerInteractEvent.LeftClickBlock} and the block-break hook, block placing fires the
 * right-click hooks, and drops are produced by the normal loot path.
 *
 * <p>This layer holds no policy — it does not decide what a bot <em>should</em> do. Authorisation
 * (which actions are enabled, and the command allowlist) lives in the caller, so a single
 * {@link ActionPolicy} can restrict bots without touching this code.
 */
public final class Actions {

    /** Distance within which a bot may interact with blocks, matching a survival player. */
    public static final double REACH = 4.5D;

    private Actions() {
    }

    /** Outcome of an action: success plus a human/LLM-readable explanation. */
    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    // --- looking --------------------------------------------------------------------------------

    /** Turn the bot's head toward a point. */
    public static Result lookAt(ServerPlayer bot, Vec3 point) {
        com.melody.mcagent.rt.bot.MovementDriver.lookAt(bot, point);
        return Result.ok(String.format("looking at (%.1f, %.1f, %.1f)", point.x, point.y, point.z));
    }

    /**
     * Teleport a bot to the same safe position vanilla would use after death, without killing it.
     *
     * <p>This is deliberately a first-class action rather than an invocation of a mod-provided
     * {@code /home} command. Command implementations are free to reject fake players, send a
     * message without moving them, or not exist at all; the bot used to report all three as
     * success. The respawn point is vanilla player state and therefore survives server restarts.
     * Passing {@code true} keeps a respawn anchor's charge: this is an emergency relocation, not a
     * death.
     */
    public static Result returnToRespawn(ServerPlayer bot) {
        DimensionTransition target = bot.findRespawnPositionAndUseSpawnBlock(
                true, DimensionTransition.DO_NOTHING);
        Vec3 destination = target.pos();
        boolean customSpawn = bot.getRespawnPosition() != null && !target.missingRespawnBlock();

        bot.closeContainer();
        bot.stopRiding();
        if (bot.isSleeping()) {
            bot.stopSleepInBed(true, true);
        }
        boolean moved = bot.teleportTo(
                target.newLevel(), destination.x, destination.y, destination.z,
                Set.of(), target.yRot(), target.xRot());
        if (!moved) {
            return Result.fail("the server refused the teleport to your respawn point");
        }
        bot.setDeltaMovement(Vec3.ZERO);
        bot.resetFallDistance();

        String where = BlockPos.containing(destination).toShortString();
        if (customSpawn) {
            return Result.ok("returned directly to your respawn point at " + where + " in "
                    + target.newLevel().dimension().location());
        }
        if (target.missingRespawnBlock()) {
            return Result.ok("your saved bed or anchor was missing or obstructed, so you returned "
                    + "to world spawn at " + where);
        }
        return Result.ok("you have no personal respawn point, so you returned to world spawn at "
                + where);
    }

    // --- block breaking -------------------------------------------------------------------------

    /** How many ticks a break would take with the currently held item. */
    public static int ticksToBreak(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        float perTick = state.getDestroyProgress(bot, bot.level(), pos);
        if (perTick <= 0.0F) {
            return Integer.MAX_VALUE; // unbreakable with what we are holding
        }
        return (int) Math.ceil(1.0F / perTick);
    }

    /**
     * Begin breaking a block, as a client would by holding left-click.
     *
     * <p>This is the start half of a timed break. The caller must let the bot's tick run for
     * {@link #ticksToBreak} ticks and then call {@link #finishBreak}. Breaking in a single call
     * would skip the mining-time simulation that makes a bot behave like a player.
     */
    public static Result startBreak(ServerPlayer bot, BlockPos pos, Direction face) {
        if (!canReach(bot, pos)) {
            return Result.fail("block is out of reach (max " + REACH + " blocks)");
        }
        if (bot.level().getBlockState(pos).isAir()) {
            // Reported as a failure of the *bot's knowledge*, not of the action. A model that has
            // only ever seen a grouped block summary will happily guess at coordinates, and the bare
            // "there is no block there" that this used to return taught it nothing, so it guessed
            // again next turn - observed in play as four consecutive identical failures in one turn.
            return Result.fail("there is no block at " + pos.toShortString()
                    + " - it is open air. Your block list shows only what you can actually see; "
                    + "observe again and use a coordinate from it rather than guessing.");
        }

        // START only insta-breaks when the block yields in a single tick; otherwise it records the
        // breaking state that the game mode's tick() advances.
        bot.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                face,
                bot.level().getMaxBuildHeight(),
                0);
        return Result.ok("started breaking " + describeBlock(bot, pos));
    }

    /**
     * Finish a break started with {@link #startBreak}.
     *
     * <p>The game only destroys the block on STOP once progress passes 0.7; below that it arms a
     * delayed destroy that its own tick completes. Sending STOP is therefore correct either way,
     * which is why we do not need to inspect the progress ourselves.
     *
     * <p>The drops are left exactly where vanilla put them. The mining job calls
     * {@link #collectBreakDrops} immediately afterwards so they end up in the pack instead of on the
     * ground.
     */
    public static Result finishBreak(ServerPlayer bot, BlockPos pos, Direction face) {
        bot.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                face,
                bot.level().getMaxBuildHeight(),
                0);
        return Result.ok("finished breaking at " + pos.toShortString());
    }

    /** Abort an in-progress break. */
    public static Result abortBreak(ServerPlayer bot, BlockPos pos, Direction face) {
        bot.gameMode.handleBlockBreakAction(
                pos,
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                face,
                bot.level().getMaxBuildHeight(),
                0);
        return Result.ok("aborted breaking at " + pos.toShortString());
    }

    /**
     * How old a dropped item may be and still count as coming from the break that just happened.
     *
     * <p>A block drop is created at age 0, so anything older than a tick or two was already lying on
     * the ground before this break: it belongs to another player, to a block broken earlier in the
     * same job, or to something this bot threw away itself.
     */
    private static final int FRESH_DROP_AGE_TICKS = 2;

    /**
     * Move the drops a break just produced straight into the bot's inventory.
     *
     * <p>This deliberately skips the stage where drops lie on the ground and have to be walked over.
     * That is what the user asked for: watching a bot spend minutes walking between the logs of a
     * tree it had just felled was the complaint, and the walking dominated the time the job took. It
     * is not a claim that the bot picks things up by hand - the block is broken and dropped by
     * vanilla, and this only moves the result into the pack.
     *
     * <p>Only items from the break that just happened are taken, and the two guards are separate:
     * the search is limited to the block's own neighbourhood, and the item must be freshly created.
     * Without the age check the bot would vacuum up whatever it walked past, including its own
     * discarded junk and another player's dropped items.
     *
     * <p>Anything that does not fit is left on the ground rather than deleted - the entity's stack is
     * shrunk by exactly what was taken - so a full inventory loses nothing.
     *
     * @return how many items went into the inventory
     */
    public static int collectBreakDrops(ServerPlayer bot, BlockPos pos) {
        // A block's own neighbourhood: a vanilla drop appears within 0.25 of the block's centre, so
        // three quarters of a block of slack comfortably covers the block that was broken. Two
        // adjacent blocks' drops can just about reach each other's slack, which is harmless - either
        // way those items come from the same swing of the same job.
        List<net.minecraft.world.entity.item.ItemEntity> drops = bot.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(0.75D));

        int collected = 0;
        for (net.minecraft.world.entity.item.ItemEntity drop : drops) {
            if (drop.isRemoved() || drop.getAge() > FRESH_DROP_AGE_TICKS) {
                continue;
            }
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            ItemStack leftover = Containers.addToInventory(bot, stack.copy());
            int moved = before - leftover.getCount();
            // Mining must not silently abandon ore just because the pack has accumulated stone and
            // worn-out starter tools. This is a deterministic reflex, not another LLM round trip:
            // free one unquestionably expendable slot and retry the valuable remainder.
            if (!leftover.isEmpty() && isValuableMiningDrop(leftover)
                    && discardOneMiningJunk(bot) != null) {
                ItemStack afterCleanup = Containers.addToInventory(bot, leftover);
                moved += leftover.getCount() - afterCleanup.getCount();
                leftover = afterCleanup;
            }
            if (moved <= 0) {
                continue; // the pack is full; leave the whole drop where it is
            }
            collected += moved;
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                // Only part of it fitted. Shrink the entity rather than discarding it, so the
                // remainder is still there to be picked up later.
                drop.setItem(leftover);
            }
        }
        return collected;
    }

    /** Can this ground stack enter the carried inventory without throwing anything away? */
    public static boolean canFitInventory(ServerPlayer bot, ItemStack stack) {
        return Containers.canFit(bot, stack);
    }

    /**
     * Permanently discard up to {@code count} matching carried items.
     *
     * <p>This is intentionally deletion rather than spawning another ground entity. A discard tool
     * that immediately creates a fresh pickup beside the bot makes a full-pack loop worse and can be
     * mistaken for the block currently being mined.
     */
    public static Result discardInventoryItem(ServerPlayer bot, String itemQuery, int count) {
        if (itemQuery == null || itemQuery.isBlank()) {
            return Result.fail("name the item to discard");
        }
        if (count <= 0) {
            return Result.fail("count must be positive");
        }
        var inventory = bot.getInventory();
        int carriedSlots = Math.min(36, inventory.getContainerSize());
        int removed = 0;
        String description = itemQuery;
        for (int slot = 0; slot < carriedSlots && removed < count; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matchesLooseItem(stack, itemQuery)) {
                continue;
            }
            description = stack.getHoverName().getString();
            int take = Math.min(count - removed, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            removed += take;
        }
        return removed == 0
                ? Result.fail("you are not carrying any '" + itemQuery + "'")
                : Result.ok("discarded " + removed + "x " + description + " permanently");
    }

    /** Valuable mining products for which it is worth sacrificing an expendable slot. */
    private static boolean isValuableMiningDrop(ItemStack stack) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        return path.contains("diamond") || path.contains("emerald")
                || path.contains("ancient_debris") || path.contains("netherite")
                || path.contains("raw_iron") || path.contains("raw_copper")
                || path.contains("raw_gold") || path.contains("coal")
                || path.contains("redstone") || path.contains("lapis")
                || path.endsWith("_ore") || path.contains("ore_chunk")
                || path.contains("ore_piece");
    }

    /**
     * Free one slot while preserving useful possessions and at least one stack of building stone.
     * Returns a description of what was discarded, or {@code null} when no safe choice exists.
     */
    @Nullable
    private static String discardOneMiningJunk(ServerPlayer bot) {
        var inventory = bot.getInventory();
        int slots = Math.min(36, inventory.getContainerSize());
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(itemPath(stack), stack.getCount(), Integer::sum);
            }
        }

        // Redundant wooden/stone tools are the safest first choice. Preserve one of each exact item
        // and avoid the selected hand so an active mining job does not unequip itself.
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            String path = itemPath(stack);
            if (slot != inventory.selected && isLowTierTool(path)
                    && counts.getOrDefault(path, 0) > stack.getCount()) {
                return clearSlot(inventory, slot, stack, "redundant tool");
            }
        }

        // Preserve at least one full stack of each common tunnelling material for bridging/building.
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            String path = itemPath(stack);
            if (isCommonExcavationBlock(path)
                    && counts.getOrDefault(path, 0) - stack.getCount() >= stack.getMaxStackSize()) {
                return clearSlot(inventory, slot, stack, "surplus excavation block");
            }
        }

        // Last resort: tiny stacks of renewable clutter. Never guess about modded or named items.
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            String path = itemPath(stack);
            if (stack.getCount() <= 16 && isRenewableClutter(path)) {
                return clearSlot(inventory, slot, stack, "renewable clutter");
            }
        }
        return null;
    }

    private static String clearSlot(net.minecraft.world.entity.player.Inventory inventory, int slot,
                                    ItemStack stack, String reason) {
        String removed = stack.getCount() + "x " + stack.getHoverName().getString();
        inventory.setItem(slot, ItemStack.EMPTY);
        org.slf4j.LoggerFactory.getLogger("mcagent/inventory").info(
                "Freed slot {} by discarding {} ({}) to make room for a valuable mining drop",
                slot, removed, reason);
        return removed;
    }

    private static String itemPath(ItemStack stack) {
        return stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
    }

    private static boolean isLowTierTool(String path) {
        return path.matches("(wooden|stone|golden)_(pickaxe|axe|shovel|hoe|sword)");
    }

    private static boolean isCommonExcavationBlock(String path) {
        return Set.of("cobblestone", "cobbled_deepslate", "stone", "deepslate", "dirt",
                "coarse_dirt", "gravel", "netherrack", "tuff", "andesite", "diorite",
                "granite", "calcite", "sand", "sandstone").contains(path);
    }

    private static boolean isRenewableClutter(String path) {
        return path.endsWith("_sapling") || path.endsWith("_seeds") || path.endsWith("_flower")
                || path.equals("mangrove_roots") || path.equals("stick") || path.equals("flint");
    }

    private static boolean matchesLooseItem(ItemStack stack, String rawQuery) {
        if (stack.isEmpty()) {
            return false;
        }
        String query = rawQuery.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key.toString().toLowerCase(Locale.ROOT).equals(query)
                || key.getPath().toLowerCase(Locale.ROOT).equals(query)
                || stack.getHoverName().getString().equalsIgnoreCase(rawQuery.trim());
    }

    // --- block placing / item use ---------------------------------------------------------------

    /** Right-click a block with the currently held item (place a block, open a chest, use a machine). */
    public static Result useOnBlock(ServerPlayer bot, BlockPos pos, Direction face) {
        if (!canReach(bot, pos)) {
            return Result.fail("block is out of reach (max " + REACH + " blocks)");
        }

        // Face the block first: several vanilla and modded interactions are direction-sensitive.
        lookAt(bot, Vec3.atCenterOf(pos));

        Vec3 hitPoint = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5D));
        BlockHitResult hit = new BlockHitResult(hitPoint, face, pos, false);

        InteractionResult result = bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        bot.swing(InteractionHand.MAIN_HAND, true);

        if (result.consumesAction()) {
            return Result.ok("used item on " + describeBlock(bot, pos));
        }
        return Result.fail("nothing happened (the block did not accept that item)");
    }

    /** Use the held item in the air, as a player does by right-clicking while looking at nothing. */
    public static Result useItem(ServerPlayer bot) {
        InteractionResult result = bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        bot.swing(InteractionHand.MAIN_HAND, true);
        return result.consumesAction() ? Result.ok("used held item") : Result.fail("the item could not be used");
    }

    // --- inventory ------------------------------------------------------------------------------

    /** Select a hotbar slot (0-8). */
    public static Result selectHotbarSlot(ServerPlayer bot, int slot) {
        if (slot < 0 || slot > 8) {
            return Result.fail("hotbar slot must be 0-8");
        }
        bot.getInventory().selected = slot;
        return Result.ok("selected hotbar slot " + slot);
    }

    /** Drop the held stack. */
    public static Result dropHeld(ServerPlayer bot, boolean all) {
        ItemStack held = bot.getMainHandItem();
        if (held.isEmpty()) {
            return Result.fail("nothing held");
        }
        ItemStack toDrop = all ? held.copy() : held.copyWithCount(1);
        if (all) {
            bot.getInventory().setItem(bot.getInventory().selected, ItemStack.EMPTY);
        } else {
            held.shrink(1);
        }
        bot.drop(toDrop, false);
        return Result.ok("dropped " + toDrop.getCount() + "x " + toDrop.getHoverName().getString());
    }

    // --- entities -------------------------------------------------------------------------------

    /** Attack an entity, honouring the vanilla attack cooldown. */
    public static Result attack(ServerPlayer bot, Entity target) {
        if (target == bot || target.isRemoved()) {
            return Result.fail("invalid target");
        }
        if (bot.getEyePosition().distanceTo(target.position()) > REACH) {
            return Result.fail("target is out of reach");
        }
        lookAt(bot, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        bot.attack(target);
        bot.swing(InteractionHand.MAIN_HAND, true);
        bot.resetAttackStrengthTicker();
        return Result.ok("attacked " + com.melody.mcagent.rt.perception.Perception.describe(target));
    }

    /** Right-click an entity (trade with a villager, feed an animal, mount a horse). */
    public static Result interactWithEntity(ServerPlayer bot, Entity target) {
        if (target.isRemoved()) {
            return Result.fail("invalid target");
        }
        if (bot.getEyePosition().distanceTo(target.position()) > REACH) {
            return Result.fail("target is out of reach");
        }
        lookAt(bot, target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D));
        InteractionResult result = bot.interactOn(target, InteractionHand.MAIN_HAND);
        bot.swing(InteractionHand.MAIN_HAND, true);
        return result.consumesAction()
                ? Result.ok("interacted with " + com.melody.mcagent.rt.perception.Perception.describe(target))
                : Result.fail("the entity did not accept that interaction");
    }

    /**
     * Open a door or gate, and never close one.
     *
     * <p>Right-clicking a door toggles it, so "use the door" is as likely to shut it as to open it —
     * and a bot that shuts the door behind itself then cannot path back out is a bot that has
     * trapped itself. Since the pathfinder treats a doorway as passable, the bot walks into closed
     * doors routinely, so this has to be a one-way operation: open if shut, do nothing if already
     * open. Deliberately shutting a door is a thing a player does on purpose, and the bot can say so
     * if it ever needs to.
     *
     * @return true if the block was a closed door and is now open
     */
    public static boolean openIfClosed(ServerPlayer bot, BlockPos pos) {
        var state = bot.level().getBlockState(pos);
        if (!isOpenable(state)) {
            return false;
        }
        if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            return false; // already open; leave it alone
        }
        // A door is two blocks; clicking either half opens both, but reach is checked against the
        // half we were told about.
        if (!canReach(bot, pos)) {
            for (BlockPos other : new BlockPos[] { pos.above(), pos.below() }) {
                if (isOpenable(bot.level().getBlockState(other)) && canReach(bot, other)) {
                    pos = other;
                    break;
                }
            }
            if (!canReach(bot, pos)) {
                return false;
            }
        }
        useOnBlock(bot, pos, faceToward(bot, pos));
        return true;
    }

    /** Is this a door, trapdoor or fence gate - something that opens and closes? */
    public static boolean isOpenable(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.DOORS)
                || state.is(net.minecraft.tags.BlockTags.TRAPDOORS)
                || state.is(net.minecraft.tags.BlockTags.FENCE_GATES);
    }

    /** Is this an openable block that is currently shut? */
    public static boolean isClosedOpenable(net.minecraft.world.level.block.state.BlockState state) {
        return isOpenable(state)
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
    }

    // --- equipping ------------------------------------------------------------------------------

    /**
     * Put an item the bot is carrying into its hand, wherever it happens to be.
     *
     * <p>Vanilla's own flow needs a client to click a hotbar key or drag a stack; a bot has neither,
     * and asking a model to reason about hotbar indices before every action is a tax on every single
     * turn. So any action that uses what the bot is holding takes an optional item name and equips it
     * first: the model says what it wants to use, not which slot it is in.
     *
     * <p>Items already in the hotbar are selected in place; anything else is swapped into the
     * currently selected slot, so the swap is visible and reversible and nothing is ever destroyed.
     *
     * @param itemQuery item id, or a plain name such as "iron pickaxe"
     */
    public static Result holdItem(ServerPlayer bot, String itemQuery) {
        if (itemQuery == null || itemQuery.isBlank()) {
            return Result.fail("no item named");
        }
        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        String itemId = index != null ? index.resolveItem(itemQuery) : itemQuery;
        if (itemId == null) {
            return Result.fail("unknown item '" + itemQuery + "'");
        }

        var inventory = bot.getInventory();
        String wanted = itemId.toLowerCase(java.util.Locale.ROOT);

        ItemStack held = bot.getMainHandItem();
        if (matchesItem(held, wanted, itemQuery)) {
            return Result.ok("already holding " + held.getHoverName().getString());
        }

        // Hotbar first: selecting an existing slot is less disruptive than a swap.
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matchesItem(stack, wanted, itemQuery)) {
                inventory.selected = slot;
                return Result.ok("holding " + stack.getHoverName().getString());
            }
        }
        for (int slot = 9; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matchesItem(stack, wanted, itemQuery)) {
                continue;
            }
            int target = inventory.selected;
            ItemStack displaced = inventory.getItem(target);
            inventory.setItem(target, stack);
            inventory.setItem(slot, displaced);
            return Result.ok("took " + stack.getHoverName().getString()
                    + " out of your pack and are now holding it");
        }
        return Result.fail("you are not carrying any '" + itemQuery + "'");
    }

    /** Does this stack answer to the requested id or name? */
    private static boolean matchesItem(ItemStack stack, String wantedId, String rawQuery) {
        if (stack.isEmpty()) {
            return false;
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString().toLowerCase(java.util.Locale.ROOT);
        if (id.equals(wantedId) || id.endsWith(":" + wantedId)) {
            return true;
        }
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(java.util.Locale.ROOT);
        return !query.isEmpty()
                && stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).equals(query);
    }

    /**
     * Equip an item if one was named, and report failure if it is not carried.
     *
     * @return null when the caller should carry on, or a failure to return instead
     */
    @Nullable
    public static Result equipIfRequested(ServerPlayer bot, String itemQuery) {
        if (itemQuery == null || itemQuery.isBlank()) {
            return null;
        }
        Result equipped = holdItem(bot, itemQuery);
        return equipped.success() ? null : equipped;
    }

    /**
     * Equip the cheapest carried tool suited to this block, falling back to the requested tool.
     *
     * <p>The model may omit {@code item}; the server still must not leave an unrelated held item
     * selected while a suitable tool is in the pack. The ordering also preserves expensive tools
     * when a cheaper one is capable.
     */
    @Nullable
    public static Result equipForMining(ServerPlayer bot, BlockState state, String requestedItem) {
        String[] preferred;
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
            if (state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)) {
                preferred = new String[] { "diamond_pickaxe", "netherite_pickaxe" };
            } else if (state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL)) {
                preferred = new String[] {
                        "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"
                };
            } else {
                preferred = new String[] {
                        "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe"
                };
            }
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) {
            preferred = new String[] {
                    "stone_axe", "iron_axe", "diamond_axe", "netherite_axe"
            };
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) {
            preferred = new String[] {
                    "stone_shovel", "iron_shovel", "diamond_shovel", "netherite_shovel"
            };
        } else if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) {
            preferred = new String[] {
                    "stone_hoe", "iron_hoe", "diamond_hoe", "netherite_hoe"
            };
        } else {
            return equipIfRequested(bot, requestedItem);
        }
        for (String item : preferred) {
            Result equipped = holdItem(bot, item);
            if (equipped.success()) {
                return null;
            }
        }
        return equipIfRequested(bot, requestedItem);
    }

    // --- sleeping -------------------------------------------------------------------------------

    /**
     * Lie down in a bed.
     *
     * <p>Sleeping is what binds a respawn point, so this is also how a bot stops being sent back to
     * world spawn every time something kills it. Goes through vanilla's own
     * {@code startSleepInBed}, so every rule the game applies to a player applies here too: it has to
     * be night or thundering, the bed must be reachable, and the surroundings must be safe.
     */
    public static Result sleep(ServerPlayer bot, BlockPos pos) {
        if (!(bot.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return Result.fail("there is no bed at " + pos.toShortString());
        }
        if (!canReach(bot, pos)) {
            return Result.fail("the bed at " + pos.toShortString() + " is out of reach (max "
                    + REACH + " blocks)");
        }

        var outcome = bot.startSleepInBed(pos);
        if (outcome.left().isPresent()) {
            return Result.fail(describeSleepProblem(outcome.left().get()));
        }
        return Result.ok("you are now lying in the bed at " + pos.toShortString()
                + ", which is your respawn point from now on. You will wake up on your own.");
    }

    /** Wake up, if asleep. */
    public static Result wakeUp(ServerPlayer bot) {
        if (!bot.isSleeping()) {
            return Result.fail("you are not asleep");
        }
        bot.stopSleeping();
        return Result.ok("you got out of bed");
    }

    /** The nearest bed within range, or null. */
    @Nullable
    public static BlockPos findBed(ServerPlayer bot, int radius) {
        if (!(bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        BlockPos origin = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.BEDS)) {
                continue;
            }
            double distance = origin.distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Turn a vanilla refusal into something a model can act on. */
    private static String describeSleepProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem problem) {
        return switch (problem) {
            case NOT_POSSIBLE_HERE -> "you cannot sleep here - this bed is in a dimension that has no night";
            case NOT_POSSIBLE_NOW -> "you can only sleep at night or during a thunderstorm; it is daytime now";
            case TOO_FAR_AWAY -> "you are too far from the bed";
            case OBSTRUCTED -> "the bed is obstructed - something is on top of it";
            case NOT_SAFE -> "there are monsters nearby, so it is not safe to sleep";
            default -> "you could not sleep here";
        };
    }

    // --- chat -----------------------------------------------------------------------------------

    /** How long a bot is held to "you already said that", in ticks. */
    private static final long REPEAT_WINDOW_TICKS = 3600L;

    /**
     * Speak as the bot, so real players see it as an ordinary chat line.
     *
     * <p>Uses an unsigned {@code PlayerChatMessage}. The server logs such messages as "Not Secure"
     * but still delivers them, which is what we want: the bot has no client to sign with, and the
     * alternative (a system message) would not appear as player chat.
     */
    public static Result chat(ServerPlayer bot, String text) {
        if (text == null || text.isBlank()) {
            return Result.fail("cannot send an empty message");
        }
        if (text.length() > 256) {
            text = text.substring(0, 256);
        }

        // Repetition is the most visible way a bot looks broken, and it is cheap to catch: a bot does
        // not hear its own chat, so before this it could acknowledge one instruction three times in a
        // row and never notice. "The same" means identical after punctuation and spacing are
        // ignored, or an earlier line contained in this one, within the last few minutes.
        if (com.melody.mcagent.rt.perception.ChatLog.saidRecently(bot, text, REPEAT_WINDOW_TICKS)) {
            return Result.fail("you already said this within the last " + (REPEAT_WINDOW_TICKS / 20)
                    + " seconds; do not repeat yourself. Either say something with new information "
                    + "in it, or stay silent and keep working");
        }

        var message = net.minecraft.network.chat.PlayerChatMessage.unsigned(bot.getUUID(), text);
        var bound = net.minecraft.network.chat.ChatType.bind(net.minecraft.network.chat.ChatType.CHAT, bot);
        bot.server.getPlayerList().broadcastChatMessage(message, bot, bound);

        // Let the other bots hear this too. Bots speak through broadcastChatMessage rather than the
        // client packet path, so ServerChatEvent never fires for them and two bots would otherwise
        // talk past each other, each unaware the other had said anything. Recording here closes the
        // loop: several bots can hold a conversation instead of each broadcasting into the void.
        var bots = com.melody.mcagent.rt.Agent.botManager();
        if (bots != null && !bots.handles().isEmpty()) {
            java.util.List<ServerPlayer> listeners = new java.util.ArrayList<>();
            for (var handle : bots.handles()) {
                listeners.add(handle.player());
            }
            com.melody.mcagent.rt.perception.ChatLog.record(
                    bot, text, bot.level().getGameTime(), listeners);
        }
        // And remember it as its own line, so both the repetition check and the prompt know it.
        com.melody.mcagent.rt.perception.ChatLog.recordOwn(bot, text, bot.level().getGameTime());

        return Result.ok("said: " + text);
    }

    // --- commands -------------------------------------------------------------------------------

    /**
     * Run a slash command as the bot, restricted to an allowlist.
     *
     * <p>The bot is never an operator: {@code getPermissionLevel()} resolves to 0 for a non-op, so
     * the server's own permission checks apply and an op-required command simply fails. The
     * allowlist is a second, tighter gate that stops an LLM from running anything unlisted even if
     * it would be permitted.
     */
    public static Result runCommand(ServerPlayer bot, String command, ActionPolicy policy) {
        String normalised = command.startsWith("/") ? command.substring(1) : command;
        String root = normalised.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);

        if (!policy.isCommandAllowed(root)) {
            return Result.fail("command '" + root + "' is not on the allowlist");
        }

        bot.server.getCommands().performPrefixedCommand(bot.createCommandSourceStack(), normalised);
        return Result.ok("ran /" + normalised);
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Is the block within the bot's interaction range? */
    public static boolean canReach(ServerPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    /** Ask the game for the drops a break would produce, without performing it. */
    public static List<ItemStack> previewDrops(ServerPlayer bot, BlockPos pos) {
        ServerLevel level = bot.serverLevel();
        BlockState state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = new ArrayList<>(
                net.minecraft.world.level.block.Block.getDrops(state, level, pos, blockEntity, bot, bot.getMainHandItem()));
        return drops;
    }

    private static String describeBlock(ServerPlayer bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        return state.getBlock().getName().getString() + " at " + pos.toShortString();
    }

    /** The six block faces, for callers that need to pick one. */
    public static Direction[] faces() {
        return Direction.values();
    }

    @Nullable
    public static Direction faceToward(ServerPlayer bot, BlockPos pos) {
        Vec3 delta = bot.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getNearest(delta.x, delta.y, delta.z);
    }
}
