package com.melody.mcagent.rt.perception;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Where to put a farm.
 *
 * <p>"Do not just dig anywhere" is the whole of this class. A field is a commitment: it needs level
 * ground that a hoe will actually turn over, air above every cell, no water or lava running through
 * it, and - the one that matters most on a server with players on it - it must not be somebody's
 * garden, lawn or front room. The bot's own structure guard already refuses to break player-built
 * blocks, so a field sited inside a build would fail one cell at a time, in public, for no reason a
 * model could explain. Choosing the site first is what keeps that from happening.
 *
 * <p>Every rejection carries the reason, because "no" is not actionable and a bot that cannot say
 * why it did not build a farm looks broken.
 */
public final class FarmSite {

    /** How far above the soil the site must be clear, so the crops have room to grow. */
    private static final int CLEARANCE = 3;

    /** What the bot decided, and anything an operator would want to know about it. */
    public record Site(BlockPos centre, int radius, int groundY, List<String> notes) {

        /** A cell at the given offset from the centre, on the field's own level. */
        public BlockPos cell(int dx, int dz) {
            return new BlockPos(this.centre.getX() + dx, this.groundY, this.centre.getZ() + dz);
        }

        public String describe() {
            return "a " + (this.radius * 2 + 1) + "x" + (this.radius * 2 + 1) + " field centred on "
                    + this.centre.toShortString() + " (ground y=" + this.groundY + ")"
                    + (this.notes.isEmpty() ? "" : " - " + String.join("; ", this.notes));
        }
    }

    private FarmSite() {
    }

    /** Soil a hoe turns into farmland. Coarse and rooted dirt turn into plain dirt, so they do not. */
    public static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.DIRT_PATH) || Crops.isFarmland(state);
    }

    /**
     * Why this exact square cannot be a field, or {@code null} when it can.
     *
     * @param centre the centre cell, at the level of the soil
     */
    @Nullable
    public static String reject(ServerPlayer bot, BlockPos centre, int radius) {
        ServerLevel level = bot.serverLevel();
        int groundY = centre.getY();
        int side = radius * 2 + 1;
        int tillable = 0;
        boolean openSky = true;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos soil = new BlockPos(centre.getX() + dx, groundY, centre.getZ() + dz);
                if (level.isOutsideBuildHeight(soil)) {
                    return "that spot is outside the world";
                }
                BlockState state = level.getBlockState(soil);
                if (!state.getFluidState().isEmpty()) {
                    return "there is " + state.getFluidState().getType() + " at "
                            + soil.toShortString() + " - water or lava through the middle of a field "
                            + "is not a field";
                }
                if (!isTillable(state)) {
                    return state.getBlock().getName().getString() + " at " + soil.toShortString()
                            + " cannot be tilled into farmland (a hoe only turns grass, dirt and "
                            + "dirt paths)";
                }
                tillable++;
                for (int dy = 1; dy <= CLEARANCE; dy++) {
                    BlockPos above = soil.above(dy);
                    BlockState air = level.getBlockState(above);
                    if (!air.isAir()) {
                        if (air.getFluidState().isEmpty()) {
                            return "something is standing at " + above.toShortString() + " ("
                                    + air.getBlock().getName().getString() + "), so the crops would "
                                    + "have no room";
                        }
                        return "there is " + air.getFluidState().getType() + " above "
                                + soil.toShortString();
                    }
                }
                if (!level.canSeeSky(soil.above())) {
                    openSky = false;
                }
            }
        }
        if (tillable < side * side) {
            return "only " + tillable + " of " + (side * side) + " cells could be tilled";
        }
        // The guard's own answer, so the two can never disagree about what counts as a build. The
        // scan is 10 blocks around the point it is asked about, which covers a field of this size.
        String built = PlayerStructure.protectionReason(level, centre, centre);
        if (built != null) {
            return "that is inside something a player built (" + built + ")";
        }
        for (int dx = -radius - 1; dx <= radius + 1; dx += radius + 1) {
            for (int dz = -radius - 1; dz <= radius + 1; dz += radius + 1) {
                BlockPos corner = new BlockPos(centre.getX() + dx, groundY, centre.getZ() + dz);
                if (PlayerStructure.detectCached(level, corner) != null) {
                    return "a player-built structure reaches " + corner.toShortString()
                            + ", which is the edge of that field";
                }
            }
        }
        if (!openSky && level.getMaxLocalRawBrightness(centre.above()) < 9) {
            // Not a rejection: torches are part of building a field, and the builder places them.
            // Only said when it is genuinely dark enough to matter.
            return null;
        }
        return null;
    }

    /**
     * Find a field site near the bot, spiralling outward from where it stands.
     *
     * <p>Spiralling rather than scanning a grid so the first hit is the nearest one: a bot that walks
     * forty blocks to build a farm it could have built under its feet is a bot that looks broken.
     *
     * @param searchRadius how far from the bot to look, in blocks
     * @return the site, or null when nothing nearby qualifies
     */
    @Nullable
    public static Site find(ServerPlayer bot, int radius, int searchRadius) {
        ServerLevel level = bot.serverLevel();
        BlockPos from = bot.blockPosition();
        for (int ring = 2; ring <= searchRadius; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += 2) {
                for (int dz = -ring; dz <= ring; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = from.getX() + dx;
                    int z = from.getZ() + dz;
                    int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockPos centre = new BlockPos(x, surface, z);
                    // Stay on the bot's own level: a cliff two blocks up is not the same field.
                    if (Math.abs(surface - (from.getY() - 1)) > 1) {
                        continue;
                    }
                    if (reject(bot, centre, radius) == null) {
                        return new Site(centre, radius, surface, notes(bot, centre, radius));
                    }
                }
            }
        }
        return null;
    }

    /** What an operator would want to know about a site that passed: light, water, and shelter. */
    private static List<String> notes(ServerPlayer bot, BlockPos centre, int radius) {
        ServerLevel level = bot.serverLevel();
        List<String> notes = new ArrayList<>();
        boolean sky = level.canSeeSky(centre.above());
        notes.add(sky ? "open to the sky" : "sheltered, so it will need the torches");
        // Hydration reaches four blocks from a water block, at or one above the farmland's level.
        boolean hydrated = false;
        for (int dx = -radius - 4; dx <= radius + 4 && !hydrated; dx++) {
            for (int dz = -radius - 4; dz <= radius + 4 && !hydrated; dz++) {
                for (int dy = 0; dy <= 1 && !hydrated; dy++) {
                    BlockState state = level.getBlockState(
                            new BlockPos(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz));
                    hydrated = state.is(Blocks.WATER);
                }
            }
        }
        if (hydrated) {
            notes.add("natural water is already within reach");
        }
        return notes;
    }

    /** Is this block part of a field already - farmland or a crop? Used to avoid re-siting. */
    public static boolean isExistingField(BlockState state) {
        return Crops.isFieldBlock(state) || state.is(BlockTags.CROPS);
    }
}
