package com.melody.mcagent.rt.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.melody.mcagent.rt.action.Actions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Plans a short, player-sized excavation from reachable space to interaction range of a block.
 *
 * <p>Perception deliberately notices targets through a few blockers. That is useful knowledge, but
 * the target coordinate is not necessarily a place A* can walk to. This planner bridges those two
 * facts: ordinary A* remains the first choice, while this bounded search may cross solid feet/head
 * cells by explicitly returning the blocks which have to be mined before each movement step.
 * Floors are never invented or removed, block entities and fluids are never tunnelled through, and
 * every step is cardinal with at most one block of height change, so the result can be executed by
 * the same timed mine and vanilla movement actions as any other job.
 */
public final class MiningAccessPlanner {

    private static final int MAX_EXPANDED = 1800;
    private static final double MOVE_COST = 1.0D;
    private static final double HEIGHT_COST = 0.35D;
    private static final double BREAK_BASE_COST = 2.0D;

    private MiningAccessPlanner() {
    }

    /** One position to enter, after mining every block in {@link #clear}. */
    public record Step(BlockPos feet, List<BlockPos> clear) {
    }

    /** A bounded access route. An empty step list means the target is already in reach. */
    public record Route(List<Step> steps, int blocksToClear, int expandedNodes) {
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g;
        final double f;
        @Nullable final Node parent;
        final List<BlockPos> clear;

        Node(BlockPos pos, double g, double f, @Nullable Node parent, List<BlockPos> clear) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.clear = clear;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f, other.f);
        }
    }

    /** Find a short excavation route, or {@code null} when no safe bounded route exists. */
    @Nullable
    public static Route find(ServerPlayer bot, BlockPos target, int maxRange) {
        return find(bot, target, maxRange, null);
    }

    /**
     * Find a short excavation route while treating {@code forbidden} cells as undiggable.
     *
     * <p>The caller supplies player-built structure blocks here. They are rejected in
     * {@link #clearance} rather than after planning, so a route through the player's house is never
     * proposed in the first place; the bot reports that no safe access exists instead of quietly
     * demolishing a wall.
     */
    @Nullable
    public static Route find(ServerPlayer bot, BlockPos target, int maxRange,
                             @Nullable java.util.function.Predicate<BlockPos> forbidden) {
        if (!(bot.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos start = bot.blockPosition().immutable();
        if (withinReach(start, target)) {
            return new Route(List.of(), 0, 0);
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<BlockPos, Double> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        Node first = new Node(start, 0.0D, heuristic(start, target), null, List.of());
        open.add(first);
        best.put(start, 0.0D);
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_EXPANDED) {
            Node current = open.poll();
            if (!closed.add(current.pos)) {
                continue;
            }
            expanded++;
            if (withinReach(current.pos, target)) {
                return reconstruct(current, expanded);
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                // Flat first, then a descending or rising stair. A player can traverse all three.
                for (int dy : new int[] {0, -1, 1}) {
                    BlockPos next = current.pos.relative(direction).offset(0, dy, 0).immutable();
                    if (next.distSqr(start) > (double) maxRange * maxRange || closed.contains(next)) {
                        continue;
                    }
                    List<BlockPos> clear = clearance(bot, level, current.pos, next, target, forbidden);
                    if (clear == null) {
                        continue;
                    }
                    double edge = MOVE_COST + Math.abs(dy) * HEIGHT_COST;
                    for (BlockPos block : clear) {
                        int ticks = Actions.ticksToBreak(bot, block);
                        edge += BREAK_BASE_COST + Math.min(ticks, 1200) / 20.0D;
                    }
                    double tentative = current.g + edge;
                    if (tentative >= best.getOrDefault(next, Double.MAX_VALUE)) {
                        continue;
                    }
                    best.put(next, tentative);
                    open.add(new Node(next, tentative, tentative + heuristic(next, target),
                            current, clear));
                }
            }
        }
        return null;
    }

    @Nullable
    private static List<BlockPos> clearance(ServerPlayer bot, ServerLevel level, BlockPos from,
                                             BlockPos feet, BlockPos target,
                                             @Nullable java.util.function.Predicate<BlockPos> forbidden) {
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        if (floorState.getCollisionShape(level, floor).isEmpty()
                || !floorState.getFluidState().isEmpty()
                || floorState.getBlock() instanceof FallingBlock
                || isHazard(floorState)) {
            return null;
        }

        List<BlockPos> cells = new ArrayList<>(3);
        cells.add(feet);
        cells.add(feet.above());
        if (feet.getY() > from.getY()) {
            // A rising player briefly needs the third cell while stepping/jumping up.
            cells.add(feet.above(2));
        }

        List<BlockPos> clear = new ArrayList<>(3);
        for (BlockPos cell : cells) {
            if (PathFinder.canPass(level, cell)) {
                continue;
            }
            // The desired resource is the endpoint, not disposable tunnel clearance.
            if (cell.equals(target)) {
                return null;
            }
            // Player-built structure blocks are not terrain: no route may be planned through them.
            if (forbidden != null && forbidden.test(cell)) {
                return null;
            }
            BlockState state = level.getBlockState(cell);
            if (!state.getFluidState().isEmpty()
                    || state.getBlock() instanceof FallingBlock
                    || level.getBlockEntity(cell) != null
                    || isHazard(state)
                    || Actions.ticksToBreak(bot, cell) == Integer.MAX_VALUE) {
                return null;
            }
            clear.add(cell.immutable());
        }
        return List.copyOf(clear);
    }

    private static boolean withinReach(BlockPos feet, BlockPos target) {
        Vec3 predictedEye = new Vec3(feet.getX() + 0.5D, feet.getY() + 1.62D,
                feet.getZ() + 0.5D);
        return predictedEye.distanceTo(Vec3.atCenterOf(target)) <= Actions.REACH;
    }

    private static double heuristic(BlockPos feet, BlockPos target) {
        Vec3 predictedEye = new Vec3(feet.getX() + 0.5D, feet.getY() + 1.62D,
                feet.getZ() + 0.5D);
        return Math.max(0.0D, predictedEye.distanceTo(Vec3.atCenterOf(target)) - Actions.REACH);
    }

    private static Route reconstruct(Node end, int expanded) {
        List<Step> reverse = new ArrayList<>();
        int blocks = 0;
        for (Node node = end; node.parent != null; node = node.parent) {
            reverse.add(new Step(node.pos, node.clear));
            blocks += node.clear.size();
        }
        Collections.reverse(reverse);
        return new Route(List.copyOf(reverse), blocks, expanded);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE);
    }
}
