package com.melody.mcagent.rt.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A* pathfinding over the block grid, for a player-shaped agent.
 *
 * <p>Vanilla's own {@code PathFinder} cannot be reused: its entry point is
 * {@code findPath(PathNavigationRegion, Mob, ...)} — hard-typed to {@code Mob} — and a
 * {@code ServerPlayer} is not a {@code Mob}. It also drives movement through {@code MoveControl},
 * which players do not have. Since we drive a player by writing input fields, we need our own
 * search that produces waypoints a player can actually walk between.
 *
 * <p>The search models what a walking player can do:
 * <ul>
 *   <li>step between the four cardinal neighbours and the four diagonals;</li>
 *   <li>step <b>up</b> one block (a slab or single block, which the engine auto-steps);</li>
 *   <li>drop <b>down</b> up to {@link #MAX_FALL} blocks, which the engine also handles;</li>
 *   <li>never enter a column without room for the player's full height;</li>
 *   <li>avoid hazardous blocks (lava, fire, cactus, magma, powder snow).</li>
 * </ul>
 *
 * <p>Per the project's design, pathfinding is allowed to consult full world data and is
 * deliberately <b>not</b> restricted by the perception layer: the bot may know the terrain well
 * enough to navigate it, while only being able to <em>report</em> what it can see.
 *
 * <p>Diagonal moves additionally require both orthogonal neighbours to be passable, so the bot
 * never cuts a corner through a wall.
 */
public final class PathFinder {

    /** Maximum number of blocks the agent will willing drop. Mirrors vanilla's mob behaviour. */
    public static final int MAX_FALL = 3;
    /** Hard cap on expanded nodes, so a pathological request cannot stall the server tick. */
    private static final int MAX_EXPANDED = 6000;
    /** How far from the requested goal we will accept a standable cell (goal may be inside a block). */
    private static final int GOAL_TOLERANCE = 3;
    /**
     * How far up or down from the requested Y a standable cell may be found.
     *
     * <p>Models name a Y they read off a block they saw, or simply guess, and being a few blocks out
     * is completely normal. The search below must forgive that; if it does not, the bot is told
     * "no path" for a target it is standing six blocks away from.
     */
    private static final int GOAL_VERTICAL_TOLERANCE = 8;

    private static final double CARDINAL_COST = 1.0D;
    private static final double DIAGONAL_COST = 1.4142135623730951D;
    /** Slight penalty for changing height, so the search prefers flat routes when costs tie. */
    private static final double HEIGHT_PENALTY = 0.35D;

    private PathFinder() {
    }

    /** A found path, as a list of positions the agent should walk through in order. */
    public record Path(List<BlockPos> waypoints, double cost, int expandedNodes) {
        public boolean isEmpty() {
            return this.waypoints.isEmpty();
        }
    }

    /** A search node. */
    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g;
        final double f;
        @Nullable
        final Node parent;

        Node(BlockPos pos, double g, double f, @Nullable Node parent) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f, other.f);
        }
    }

    @Nullable
    public static Path findPath(ServerLevel level, BlockPos start, BlockPos goal, int maxRange) {
        BlockPos resolvedGoal = resolveGoal(level, start, goal);
        if (resolvedGoal == null) {
            return null;
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<BlockPos, Double> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(start, 0.0D, heuristic(start, resolvedGoal), null);
        open.add(startNode);
        best.put(start, 0.0D);

        int expanded = 0;

        while (!open.isEmpty()) {
            Node current = open.poll();

            if (closed.contains(current.pos)) {
                continue;
            }
            closed.add(current.pos);

            if (++expanded > MAX_EXPANDED) {
                // Budget exhausted: give up rather than stalling the server tick.
                return null;
            }

            if (isGoal(current.pos, resolvedGoal)) {
                return new Path(reconstruct(current), current.g, expanded);
            }

            for (BlockPos next : neighbours(level, current.pos)) {
                if (closed.contains(next)) {
                    continue;
                }
                // Stay within the requested search radius of the start.
                if (next.distSqr(start) > (double) maxRange * maxRange) {
                    continue;
                }

                double step = stepCost(current.pos, next);
                double tentative = current.g + step;

                Double known = best.get(next);
                if (known != null && tentative >= known) {
                    continue;
                }

                best.put(next, tentative);
                open.add(new Node(next, tentative, tentative + heuristic(next, resolvedGoal), current));
            }
        }

        return null;
    }

    /**
     * Find a standable cell near the requested goal.
     *
     * <p>Callers naturally name the block they care about (a chest, a machine), whose own cell is
     * occupied — the bot has to stand <em>next to</em> it. Models also pass a Y read off a block or
     * simply guessed, so the requested height is frequently wrong by several blocks. We therefore
     * search outward in horizontal rings, and within each ring scan the whole vertical column for
     * the standable cell closest to what was asked for.
     *
     * <p>This used to search {@code dy} from 0 to +2 only — <b>upward, never down</b> — with a
     * horizontal tolerance of 2. A model that asked for a spot four blocks below the floor it was
     * standing on got no candidate at all, the whole search returned "no path", and the bot fell
     * back to walking straight at the target and grinding into the nearest wall. Observed in
     * production as a bot that would not leave a house through an open door.
     */
    @Nullable
    public static BlockPos resolveGoal(ServerLevel level, BlockPos start, BlockPos goal) {
        if (canStandAt(level, goal)) {
            return goal;
        }

        // Ring by ring, so a cell beside the requested spot always beats one further away.
        for (int ring = 1; ring <= GOAL_TOLERANCE; ring++) {
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;

            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the perimeter of this ring is new; the inside was covered already.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = goal.getX() + dx;
                    int z = goal.getZ() + dz;
                    for (int dy = -GOAL_VERTICAL_TOLERANCE; dy <= GOAL_VERTICAL_TOLERANCE; dy++) {
                        BlockPos candidate = new BlockPos(x, goal.getY() + dy, z);
                        if (!canStandAt(level, candidate)) {
                            continue;
                        }
                        // Prefer the cell nearest the requested point, penalising height a little so
                        // a flat miss beats a climb of the same horizontal distance.
                        double score = (double) (dx * dx + dz * dz) + dy * dy * 1.5D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean isGoal(BlockPos pos, BlockPos goal) {
        return pos.equals(goal);
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        // Octile distance: admissible for 8-way movement, so A* stays optimal.
        double horizontal = Math.max(Math.abs(dx), Math.abs(dz)) + (DIAGONAL_COST - 1.0D) * Math.min(Math.abs(dx), Math.abs(dz));
        return horizontal + Math.abs(dy) * HEIGHT_PENALTY;
    }

    private static double stepCost(BlockPos from, BlockPos to) {
        boolean diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
        double base = diagonal ? DIAGONAL_COST : CARDINAL_COST;
        return base + Math.abs(to.getY() - from.getY()) * HEIGHT_PENALTY;
    }

    /**
     * Every cell the agent can move to from {@code from} in one step.
     *
     * <p>For each horizontal direction we look for a landing spot: the same height (walk), one
     * block up (step), or up to {@link #MAX_FALL} blocks down (drop).
     */
    private static List<BlockPos> neighbours(ServerLevel level, BlockPos from) {
        List<BlockPos> out = new ArrayList<>(16);

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = from.getX() + dir.getStepX();
            int nz = from.getZ() + dir.getStepZ();

            for (int dy = 1; dy >= -MAX_FALL; dy--) {
                BlockPos candidate = new BlockPos(nx, from.getY() + dy, nz);

                if (!canStandAt(level, candidate)) {
                    continue;
                }
                // Walking straight into a block at head height is not a step-up, it is a wall.
                if (dy == 0 && !canPass(level, candidate.above())) {
                    continue;
                }
                if (dy > 0 && !canPass(level, candidate.above())) {
                    continue;
                }

                out.add(candidate);
                // Only take the first (highest) landing spot per direction: dropping further when a
                // nearer ledge exists would be a strictly worse route.
                break;
            }
        }

        // Diagonals, with corner-cut protection.
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                BlockPos sideA = from.offset(dx, 0, 0);
                BlockPos sideB = from.offset(0, 0, dz);

                // Both orthogonal cells must be passable, otherwise the agent would clip a corner.
                if (!canPass(level, sideA) || !canPass(level, sideB)
                        || !canPass(level, sideA.above()) || !canPass(level, sideB.above())) {
                    continue;
                }

                int nx = from.getX() + dx;
                int nz = from.getZ() + dz;

                for (int dy = 1; dy >= -MAX_FALL; dy--) {
                    BlockPos candidate = new BlockPos(nx, from.getY() + dy, nz);
                    if (!canStandAt(level, candidate)) {
                        continue;
                    }
                    if (!canPass(level, candidate.above())) {
                        continue;
                    }
                    out.add(candidate);
                    break;
                }
            }
        }

        return out;
    }

    /** Is this cell empty enough for the agent's body? */
    public static boolean canPass(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // Doors and fence gates are ways through, not walls.
        //
        // Their collision shapes are never empty: an open door's panel still occupies a thin slice of
        // its own cell (DoorBlock.getShape returns, for example, EAST_AABB whether it is open or
        // shut), and DoorBlock does not override getCollisionShape. So the "is the collision box
        // empty" test below rejected every door in the game, open or closed, and a bot that found
        // itself indoors could not path out of the building at all - it would grind against the
        // doorway and report no route, over and over.
        //
        // A doorway is passable by definition; whether the door is currently open is a separate
        // problem, solved by having the bot open it (see the door reflex in MovementDriver).
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES)) {
            return !isHazardous(state);
        }

        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        // Fluids that are not water are impassable for a walking agent (lava).
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return !isHazardous(state);
    }

    /** Hazards a naive pathfinder would happily route a player through. */
    private static boolean isHazardous(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE);
    }

    /**
     * Can the agent's feet occupy this cell (with headroom and ground beneath)?
     *
     * <p>Water is treated as a wall when it would cover the agent's head, and as ordinary floor when
     * it would not. The distinction is the difference between wading a stream and drowning in a
     * river, and it is measured by the block the agent's <em>eyes</em> are in: eye height is 1.62,
     * so the eyes always sit in {@code pos.above()}. If that cell is water, the agent is submerged.
     *
     * <p>The earlier version accepted any non-lava fluid, so A* happily routed the bot along the bed
     * of a river — a perfectly valid-looking path that ends in drowning. Swapping a longer walk for
     * a shortcut through deep water is never the trade a player would make.
     */
    public static boolean canStandAt(ServerLevel level, BlockPos pos) {
        if (!canPass(level, pos) || !canPass(level, pos.above())) {
            return false;
        }
        // Eyes under water: this route would drown the agent.
        if (!level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);

        // Must have something to stand on.
        if (belowState.getCollisionShape(level, below).isEmpty()) {
            return false;
        }
        // Do not stand on a hazard.
        if (isHazardous(belowState) || belowState.is(BlockTags.FIRE)) {
            return false;
        }
        return true;
    }

    private static List<BlockPos> reconstruct(Node node) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            out.add(n.pos);
        }
        Collections.reverse(out);

        // The first entry is where the agent already is; walking to it is a no-op.
        if (!out.isEmpty()) {
            out.remove(0);
        }
        return out;
    }

    /** Convert a waypoint list into the world positions a movement driver should aim at. */
    public static List<Vec3> toVec3(List<BlockPos> waypoints) {
        List<Vec3> out = new ArrayList<>(waypoints.size());
        for (BlockPos pos : waypoints) {
            out.add(new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D));
        }
        return out;
    }
}
