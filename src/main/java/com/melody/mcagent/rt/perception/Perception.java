package com.melody.mcagent.rt.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What a bot can see, decided strictly by line of sight.
 *
 * <p>Per the project's realism rule, a bot may only know about things it could actually observe.
 * There is no facing requirement — a bot is assumed to notice anything it has a clear line to,
 * matching how a person is aware of their surroundings without staring at each object — but
 * <b>occlusion is enforced</b>: a block or entity behind a wall, inside terrain, or around a corner
 * is not visible.
 *
 * <p>Pathfinding is deliberately exempt from these rules; see {@code DESIGN.md}. Only the
 * perception layer — what gets reported to the LLM — is restricted.
 */
public final class Perception {

    /** Maximum distance at which a block can be perceived. */
    public static final double BLOCK_RANGE = 48.0D;
    /** Maximum distance at which an entity can be perceived. */
    public static final double ENTITY_RANGE = 48.0D;
    /**
     * Hard cap on how many visible blocks we collect.
     *
     * <p>Scanning is O(radius^3) cells, so an unbounded scan is a genuine server-tick hazard in a
     * dense modded world. The cap also bounds the prompt: a model cannot use a list of four hundred
     * stone blocks anyway, and the observation builder aggregates by block type, so the closest N
     * give a faithful picture of the surroundings.
     */
    public static final int MAX_VISIBLE_BLOCKS = 600;
    /** Default time budget for one visibility scan, in milliseconds. */
    public static final long DEFAULT_BUDGET_MILLIS = 25L;
    /**
     * How many solid blocks a line of sight may pass through and still count as seeing.
     *
     * <p>A deliberate relaxation of the strict occlusion rule, on the operator's instruction: a
     * person notices a tree's silhouette through its leaves and spots an ore seam through a little
     * rock, whereas a mathematically exact line of sight reports "nothing here" for both. In play
     * that difference was crippling — a bot standing in a birch grove could not find a tree trunk,
     * because every trunk was behind a leaf.
     *
     * <p>Five blocks is the requested allowance. It is a real budget rather than a fudge: a bot
     * cannot see through a mountain, only through the sort of clutter that would not stop a player
     * noticing something either.
     */
    public static final int SEE_THROUGH_BLOCKS = 5;
    /** Hard cap on distinct block types collected by a landmark scan. */
    public static final int MAX_LANDMARK_TYPES = 24;

    private Perception() {
    }

    // --- scan order -----------------------------------------------------------------------------

    /**
     * The last computed offset table, kept in a single field rather than a map.
     *
     * <p>Only one radius is in use at a time, and a table for radius 48 is 48³ entries — caching
     * every radius an operator ever typed would be a slow leak for no benefit.
     */
    private static volatile int cachedRadius = -1;
    private static volatile int[] cachedOffsets = new int[0];

    /**
     * Offsets within {@code radius} of the origin, ordered by increasing distance.
     *
     * <p>The order is the whole point. The previous scan walked the block volume in coordinate order
     * and abandoned the rest when its time budget ran out, which meant it always kept the low-x,
     * low-y, low-z corner of the world and never reached anything beyond it — the comment in that
     * code acknowledged the bias and tried to compensate by over-collecting, which is not a fix.
     * Sorting afterwards cannot repair a sample that was never taken.
     *
     * <p>With distance ordering, a budget cut keeps the <em>nearest</em> blocks, which is both the
     * honest answer ("what is around me") and the stable one (the same view regardless of which way
     * the bot happens to be facing).
     *
     * <p>Offsets are packed 7 bits per axis, so the supported radius is 63; callers stay well under.
     */
    private static int[] offsetsByDistance(int radius) {
        int[] cached = cachedOffsets;
        if (cachedRadius == radius && cached.length > 0) {
            return cached;
        }
        int side = radius * 2 + 1;
        long[] keys = new long[side * side * side];
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distanceSq = dx * dx + dy * dy + dz * dz;
                    int packed = ((dx + 64) << 14) | ((dy + 64) << 7) | (dz + 64);
                    // Distance in the high half, packed offset in the low half: sorting the longs
                    // sorts by distance without boxing a single Integer.
                    keys[n++] = ((long) distanceSq << 32) | (packed & 0xFFFFFFFFL);
                }
            }
        }
        java.util.Arrays.sort(keys);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = (int) (keys[i] & 0xFFFFFFFFL);
        }
        cachedOffsets = out;
        cachedRadius = radius;
        return out;
    }

    private static int unpackX(int packed) {
        return ((packed >> 14) & 0x7F) - 64;
    }

    private static int unpackY(int packed) {
        return ((packed >> 7) & 0x7F) - 64;
    }

    private static int unpackZ(int packed) {
        return (packed & 0x7F) - 64;
    }

    // --- line of sight --------------------------------------------------------------------------

    /**
     * Is there an unobstructed line between two points?
     *
     * <p>Uses {@code COLLIDER} shapes so that non-solid decoration never blocks sight, which
     * matches how the game itself resolves whether one entity can see another.
     */
    public static boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to, Entity viewer) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Can the bot see the given entity? */
    public static boolean canSee(Player bot, Entity target) {
        if (target == bot || target.isRemoved() || !target.isAlive()) {
            return false;
        }
        if (target.level() != bot.level()) {
            return false;
        }
        Vec3 eye = bot.getEyePosition();
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        if (eye.distanceTo(targetCenter) > ENTITY_RANGE) {
            return false;
        }
        return hasLineOfSight((ServerLevel) bot.level(), eye, targetCenter, bot);
    }

    /**
     * Can the bot see the given block?
     *
     * <p>Casts toward the block's centre and counts how many <em>other</em> solid blocks the line
     * passes through on the way. Reaching the block within {@link #SEE_THROUGH_BLOCKS} blockers
     * counts as seeing it; more than that and it is genuinely hidden.
     *
     * <p>The count is gathered by re-casting from just beyond each blocking block rather than by
     * walking the ray in small steps. Stepping would cost hundreds of samples per line, and the
     * overwhelming majority of lines are unobstructed and answered by the very first cast, so the
     * loop almost always exits after one iteration.
     *
     * <p>This replaced a strictly exact test — one ray, miss or nothing. That version was correct and
     * useless: a trunk behind a single leaf was invisible, so a bot in a birch grove reported no
     * trees at all and wandered off looking for one.
     */
    public static boolean canSeeBlock(Player bot, BlockPos pos, Vec3 eye) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 center = Vec3.atCenterOf(pos);
        double targetDistanceSq = eye.distanceToSqr(center);
        if (targetDistanceSq > BLOCK_RANGE * BLOCK_RANGE) {
            return false;
        }

        Vec3 aim = center.subtract(eye);
        double length = aim.length();
        if (length < 0.05D) {
            return true;
        }
        Vec3 direction = aim.normalize();
        Vec3 stopJustBefore = center.subtract(direction.scale(0.05D));

        Vec3 from = eye;
        for (int blockers = 0; blockers <= SEE_THROUGH_BLOCKS; blockers++) {
            BlockHitResult hit = level.clip(new ClipContext(
                    from, stopJustBefore, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.MISS) {
                // Nothing at all between here and the block.
                return true;
            }
            if (hit.getBlockPos().equals(pos)) {
                return true;
            }
            // Something else is in the way. If it is past the target then the target was simply not
            // on this line, which means it is not visible from here.
            if (from.distanceToSqr(hit.getLocation()) >= targetDistanceSq) {
                return false;
            }
            // Carry on from beyond the blocking block.
            //
            // Stepping a fixed small distance along the ray is not enough: on a diagonal, a ray that
            // enters a block near a corner can travel a full 0.05 without leaving it, so the next
            // cast reports the same blocker again and the whole allowance is spent on one block.
            // (Found by the see-through regression test, which failed for exactly this reason.)
            // Instead, walk forward until the probe is genuinely in a different block.
            Vec3 probe = hit.getLocation();
            for (int step = 0; step < 64 && BlockPos.containing(probe).equals(hit.getBlockPos()); step++) {
                probe = probe.add(direction.scale(0.05D));
            }
            from = probe;
        }
        return false;
    }

    /** Convenience overload for callers that do not already hold the eye position. */
    public static boolean canSeeBlock(Player bot, BlockPos pos) {
        return canSeeBlock(bot, pos, bot.getEyePosition());
    }

    // --- observations ---------------------------------------------------------------------------

    /** A block the bot can currently see. */
    public record SeenBlock(BlockPos pos, BlockState state, double distance) {
    }

    /** An entity the bot can currently see. */
    public record SeenEntity(Entity entity, double distance, String description) {
    }

    /**
     * Every block within range that the bot has a clear line to.
     *
     * <p>This is O(range³) in the worst case and is intended for event-driven use rather than
     * every tick. Callers should cache results and refresh on a schedule.
     */
    public static List<SeenBlock> visibleBlocks(Player bot, int radius) {
        return visibleBlocks(bot, radius, DEFAULT_BUDGET_MILLIS);
    }

    /**
     * Visible blocks, with an explicit time budget.
     *
     * <p>Measured cost is 7-18 ms for a radius-12 scan in a flat test world; a dense modded build
     * can only be worse, and this runs on the server thread. Rather than let a single observation
     * stall a tick, the scan abandons the remainder once the budget is spent, keeping the blocks it
     * has already proven visible. Partial knowledge now beats a lag spike, and more importantly a
     * wrong answer at all: the sort by distance means what we keep is the nearest part of the view.
     */
    public static List<SeenBlock> visibleBlocks(Player bot, int radius, long budgetMillis) {
        return scan(bot, 0, radius, budgetMillis, null, -1);
    }

    /**
     * Nearby blocks that a player would notice as <em>objects</em> rather than as ground, out to a
     * longer range.
     *
     * <p>Why this exists: a plain "everything within N blocks" scan cannot answer "where is the
     * nearest tree", because in any natural terrain the result is swamped by grass, stone and
     * leaves. Raising the radius alone does not help — the cap fills up with the same close-range
     * filler, and the bot still reports that there is nothing worth walking to. Observed in play: a
     * bot standing in open country with a forest plainly in view announced it could see no trees and
     * set off to look for some.
     *
     * <p>So the far field is filtered before any ray is cast. Only logs, leaves, fluids and blocks
     * carrying a block entity are considered — the things with a silhouette: trees, water, and
     * anything built or placed. Types already visible in the near field are skipped too, since the
     * model has them and repeating them adds prompt length without adding information.
     *
     * @param alreadySeen block types the caller has already reported; may be empty
     */
    public static List<SeenBlock> visibleLandmarks(Player bot, int fromRadius, int toRadius,
                                                   java.util.Set<net.minecraft.world.level.block.Block> alreadySeen,
                                                   long budgetMillis) {
        return scan(bot, fromRadius, toRadius, budgetMillis, alreadySeen, MAX_LANDMARK_TYPES);
    }

    /**
     * The shared scan.
     *
     * @param fromRadius    skip cells nearer than this (0 for a plain near-field scan)
     * @param toRadius      scan out to here
     * @param alreadySeen   when non-null, only these "interesting" types are considered, and types
     *                      in this set are skipped entirely
     * @param maxTypes      stop after this many distinct types, or -1 for no such limit
     */
    private static List<SeenBlock> scan(Player bot, int fromRadius, int toRadius, long budgetMillis,
                                        @Nullable java.util.Set<net.minecraft.world.level.block.Block> alreadySeen,
                                        int maxTypes) {
        long startNanos = System.nanoTime();
        long budgetNanos = budgetMillis * 1_000_000L;
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos origin = bot.blockPosition();
        // Hoisted: getEyePosition() allocates, and it used to be called once per cell.
        Vec3 eye = bot.getEyePosition();
        List<SeenBlock> out = new ArrayList<>();

        int visited = 0;
        int iterations = 0;
        boolean budgetExceeded = false;
        java.util.Set<net.minecraft.world.level.block.Block> foundTypes = new java.util.HashSet<>();
        int fromRadiusSq = fromRadius * fromRadius;
        int toRadiusSq = toRadius * toRadius;
        int[] offsets = offsetsByDistance(toRadius);
        double rangeSq = BLOCK_RANGE * BLOCK_RANGE;

        for (int packed : offsets) {
            int dx = unpackX(packed);
            int dy = unpackY(packed);
            int dz = unpackZ(packed);
            int distanceSq = dx * dx + dy * dy + dz * dz;

            // Offsets are distance-ordered, so crossing either bound means every remaining cell is
            // out of play and the loop can stop rather than continue filtering.
            if (distanceSq > toRadiusSq) {
                break;
            }
            if (distanceSq <= fromRadiusSq) {
                continue;
            }
            iterations++;

            BlockPos pos = origin.offset(dx, dy, dz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            visited++;

            net.minecraft.world.level.block.Block block = state.getBlock();
            if (alreadySeen != null) {
                // Filter before the raycast: this is what keeps a 48-block scan affordable, because
                // the overwhelming majority of cells in any landscape are ordinary terrain.
                if (alreadySeen.contains(block) || !isLandmark(state)) {
                    continue;
                }
            }

            Vec3 center = Vec3.atCenterOf(pos);
            double distanceSqToEye = eye.distanceToSqr(center);
            if (distanceSqToEye > rangeSq) {
                continue;
            }
            if (!canSeeBlock(bot, pos, eye)) {
                continue;
            }

            BlockPos immutable = pos.immutable();
            out.add(new SeenBlock(immutable, state, Math.sqrt(distanceSqToEye)));
            foundTypes.add(block);

            if (alreadySeen == null && out.size() >= MAX_VISIBLE_BLOCKS) {
                break;
            }
            if (maxTypes > 0 && foundTypes.size() >= maxTypes) {
                break;
            }
            // Check the clock in blocks of work, not per block, so the check itself stays cheap.
            if ((iterations & 0x1FF) == 0 && System.nanoTime() - startNanos > budgetNanos) {
                budgetExceeded = true;
                break;
            }
        }

        // Already in distance order, but ordering is a correctness property here rather than a
        // convenience, so it is asserted rather than assumed.
        out.sort(Comparator.comparingDouble(SeenBlock::distance));

        if (TIMING) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            org.slf4j.LoggerFactory.getLogger("mcagent/perf").info(
                    "scan {}-{} -> {} visible, {} types (visited={}, budgetHit={}) in {} ms",
                    fromRadius, toRadius, out.size(), foundTypes.size(), visited, budgetExceeded, ms);
        }
        return out;
    }

    /**
     * Would a player notice this block as an object rather than as part of the ground?
     *
     * <p>Deliberately tag-driven rather than a list of block names, so it keeps working across a
     * 200-mod pack: trees, fluids, and anything with a block entity (chests, furnaces, machines,
     * signs) are exactly the things worth walking to.
     */
    public static boolean isLandmark(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        return state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /** Enable per-call timing of the (expensive) visibility scan. Set from the smoke tests. */
    public static boolean TIMING = Boolean.getBoolean("mcagent.perf")
            || "true".equalsIgnoreCase(System.getenv("MCAGENT_PERF"));

    /** Every entity the bot can currently see. */
    public static List<SeenEntity> visibleEntities(Player bot) {
        ServerLevel level = (ServerLevel) bot.level();
        AABB area = bot.getBoundingBox().inflate(ENTITY_RANGE);
        List<SeenEntity> out = new ArrayList<>();

        for (Entity entity : level.getEntities(bot, area, e -> e != bot && !e.isRemoved())) {
            if (!canSee(bot, entity)) {
                continue;
            }
            double dist = bot.getEyePosition().distanceTo(entity.position());
            out.add(new SeenEntity(entity, dist, describe(entity)));
        }

        out.sort(Comparator.comparingDouble(SeenEntity::distance));
        return out;
    }

    /** A compact, LLM-friendly description of an entity. */
    public static String describe(Entity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getType().toShortString());

        if (entity instanceof Player player) {
            sb.append(" (player ").append(player.getGameProfile().getName()).append(')');
        }

        if (entity instanceof LivingEntity living) {
            sb.append(String.format(" hp=%.0f/%.0f", living.getHealth(), living.getMaxHealth()));
            if (entity instanceof Monster) {
                sb.append(" hostile");
            }
        } else if (entity instanceof ItemEntity item) {
            sb.append(" x").append(item.getItem().getCount())
              .append(' ').append(item.getItem().getHoverName().getString());
        }
        return sb.toString();
    }
}
