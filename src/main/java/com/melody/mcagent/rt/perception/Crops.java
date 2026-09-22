package com.melody.mcagent.rt.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * What counts as a crop, and whether it is ready to take.
 *
 * <p>Type-driven rather than a list of block ids, for the same reason {@link Perception#isLandmark}
 * is: a 259-mod pack ships crops nobody here has heard of, and every one of them that extends
 * vanilla's {@link CropBlock} answers these questions correctly without being named.
 *
 * <p>This is the whole of the "is it ripe" question, in one place, because two very different
 * callers need the same answer: the observation that tells the model what is growing, and the farm
 * skill that harvests without asking the model anything at all.
 */
public final class Crops {

    private Crops() {
    }

    /** Is this block a crop the bot can farm - ripe or not? */
    public static boolean isCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock
                || state.getBlock() instanceof NetherWartBlock;
    }

    /**
     * Is this crop ready to harvest?
     *
     * <p>Anything that is not a crop is never "ripe": melons and pumpkins are fruit blocks rather
     * than crops, and a bot that treated every pumpkin as ripe would spend its life re-breaking the
     * stems.
     */
    public static boolean isMature(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            return age(state, NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        }
        return false;
    }

    /** Is this farmland - the block a seed has to be planted on? */
    public static boolean isFarmland(BlockState state) {
        return state.is(Blocks.FARMLAND);
    }

    /** Would a player call this block part of a field? Used when judging a farm site. */
    public static boolean isFieldBlock(BlockState state) {
        return isCrop(state) || isFarmland(state) || state.is(Blocks.SOUL_SAND);
    }

    /**
     * What to plant here next, taken from the crop itself.
     *
     * <p>{@code getCloneItemStack} is the item a player's pick-block would give, and for every crop
     * that is the seed: wheat gives wheat seeds, carrots give a carrot, nether wart gives nether
     * wart. Reading it from the block rather than from a table means a modded crop replants itself
     * with its own seed.
     *
     * @return the stack to replant with, or empty when the crop does not say (or the block is gone)
     */
    public static ItemStack replantItem(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isCrop(state)) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack item = state.getBlock().getCloneItemStack(level, pos, state);
            return item == null ? ItemStack.EMPTY : item;
        } catch (Throwable t) {
            // A modded crop that cannot answer must not take the farm down with it.
            return ItemStack.EMPTY;
        }
    }

    /** The block a replanted crop of this kind would grow on. */
    @Nullable
    public static Block soilFor(BlockState crop) {
        if (crop.getBlock() instanceof NetherWartBlock) {
            return Blocks.SOUL_SAND;
        }
        if (crop.getBlock() instanceof CropBlock) {
            return Blocks.FARMLAND;
        }
        return null;
    }

    /** Read an age property defensively: a missing property is 0, not an exception. */
    private static int age(BlockState state, IntegerProperty property) {
        return state.hasProperty(property) ? state.getValue(property) : 0;
    }
}
