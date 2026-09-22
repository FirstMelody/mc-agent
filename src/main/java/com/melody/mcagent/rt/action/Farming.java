package com.melody.mcagent.rt.action;

import com.melody.mcagent.rt.perception.Crops;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The farming primitives: plant a seed, till soil, and the small helpers that decide what to plant.
 *
 * <p>Deliberately separate from {@link Stations}: an anvil is a machine with a menu, and a farm is
 * ordinary blocks. Everything here is a right-click with the right item in hand, which is what a
 * player does, so every vanilla rule applies - the seed has to be plantable on that block, the hoe
 * has to be a hoe, the light and the water have to be good enough for the crop to grow.
 */
public final class Farming {

    /** Hoes cheapest first: tilling wears a tool out and a stone hoe tills exactly as well. */
    private static final String[] HOES = {
            "wooden_hoe", "stone_hoe", "golden_hoe", "iron_hoe", "diamond_hoe", "netherite_hoe"
    };

    private Farming() {
    }

    /**
     * Plant a seed on the farmland at the given position.
     *
     * @param cropPos  the empty block the crop will occupy (the farmland is the block below it)
     * @param seedQuery the seed to plant, by id or name
     */
    public static Actions.Result plant(ServerPlayer bot, BlockPos cropPos, String seedQuery) {
        if (seedQuery == null || seedQuery.isBlank()) {
            return Actions.Result.fail("say what to plant");
        }
        BlockPos soil = cropPos.below();
        BlockState soilState = bot.level().getBlockState(soil);
        BlockState above = bot.level().getBlockState(cropPos);
        if (!above.isAir()) {
            return Actions.Result.fail("something is already growing at " + cropPos.toShortString());
        }
        if (!Crops.isFarmland(soilState) && !soilState.is(Blocks.SOUL_SAND)) {
            return Actions.Result.fail("there is no farmland under " + cropPos.toShortString()
                    + " - a seed has to go on farmland or soul sand");
        }
        if (!Actions.canReach(bot, soil)) {
            return Actions.Result.fail("that farmland is out of reach");
        }

        Actions.Result held = Actions.holdItem(bot, seedQuery);
        if (!held.success()) {
            return held;
        }
        Actions.lookAt(bot, Vec3.atCenterOf(soil));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(soil).add(0.0D, 0.5D, 0.0D),
                Direction.UP, soil, false);
        InteractionResult result = bot.gameMode.useItemOn(bot, bot.level(),
                bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        bot.swing(InteractionHand.MAIN_HAND, true);

        if (!Crops.isCrop(bot.level().getBlockState(cropPos))) {
            // Nothing sprouted: the item was not a seed, or this block refuses it. Say which, so the
            // caller is not left guessing why a field stayed empty.
            return Actions.Result.fail("the seed did not take at " + cropPos.toShortString()
                    + " (is " + held.message() + " plantable there?)");
        }
        return Actions.Result.ok("planted " + bot.level().getBlockState(cropPos)
                .getBlock().getName().getString() + " at " + cropPos.toShortString());
    }

    /**
     * Till soil into farmland with a hoe.
     *
     * <p>Only the block vanilla allows is converted - grass or dirt with air above it - so this
     * cannot be used to carve up a lawn under a building.
     */
    public static Actions.Result till(ServerPlayer bot, BlockPos soilPos, String hoeQuery) {
        BlockState state = bot.level().getBlockState(soilPos);
        if (Crops.isFarmland(state)) {
            return Actions.Result.ok("that is already farmland");
        }
        if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)
                && !state.is(net.minecraft.tags.BlockTags.DIRT)) {
            return Actions.Result.fail(bot.level().getBlockState(soilPos).getBlock().getName().getString()
                    + " at " + soilPos.toShortString() + " cannot be tilled into farmland");
        }
        if (!bot.level().getBlockState(soilPos.above()).isAir()) {
            return Actions.Result.fail("there is something standing on "
                    + soilPos.toShortString() + ", so it cannot be tilled");
        }
        if (!Actions.canReach(bot, soilPos)) {
            return Actions.Result.fail("that block is out of reach");
        }

        Actions.Result held = hoeQuery != null && !hoeQuery.isBlank()
                ? Actions.holdItem(bot, hoeQuery)
                : equipHoe(bot);
        if (!held.success()) {
            return held;
        }
        Actions.lookAt(bot, Vec3.atCenterOf(soilPos));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(soilPos).add(0.0D, 0.5D, 0.0D),
                Direction.UP, soilPos, false);
        InteractionResult result = bot.gameMode.useItemOn(bot, bot.level(),
                bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        bot.swing(InteractionHand.MAIN_HAND, true);

        if (!Crops.isFarmland(bot.level().getBlockState(soilPos))) {
            return Actions.Result.fail("tilling " + soilPos.toShortString() + " did nothing ("
                    + (result.consumesAction() ? "the hoe was used but the block did not change"
                            : "the block refused the hoe") + ")");
        }
        return Actions.Result.ok("tilled " + soilPos.toShortString() + " into farmland");
    }

    /** Hold the cheapest hoe the bot is carrying. */
    public static Actions.Result equipHoe(ServerPlayer bot) {
        for (String hoe : HOES) {
            Actions.Result held = Actions.holdItem(bot, hoe);
            if (held.success()) {
                return held;
            }
        }
        return Actions.Result.fail("you are not carrying a hoe");
    }

    /** Is this stack a hoe? Used when a farm is planned and the tool list is being checked. */
    public static boolean isHoe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.endsWith("_hoe") || stack.is(Items.NETHERITE_HOE);
    }

    /** The item id of a stack, for tools that take a name rather than a stack. */
    public static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
