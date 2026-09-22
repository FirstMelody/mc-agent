package com.melody.mcagent.rt.perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.melody.mcagent.rt.path.PathFinder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic geometry-to-semantics layer for one local scene.
 *
 * <p>The language model should not have to infer "I am in a closed tunnel with an east exit" from
 * hundreds of unrelated block names. This bounded analysis uses the same collision rules as
 * movement to describe enclosure, connected walkable space, frontier directions and nearby
 * hazards, and it delegates the one human concept the action layer enforces - "a player built this"
 * - to {@link PlayerStructure}.
 */
public final class SemanticScene {

    private static final int TOPOLOGY_RADIUS = 8;
    private static final int MAX_REACHABLE_CELLS = 512;
    private static final int HAZARD_RADIUS = 6;

    private SemanticScene() {
    }

    public static String describe(ServerPlayer bot) {
        ServerLevel level = bot.serverLevel();
        BlockPos origin = bot.blockPosition().immutable();
        long observedAt = level.getGameTime();

        int ceiling = ceilingDistance(level, origin, 10);
        int solidSides = solidSides(level, origin);
        boolean skyVisible = level.canSeeSky(origin.above());
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                origin.getX(), origin.getZ());
        boolean belowSurface = origin.getY() + 4 < surfaceY;

        String enclosure;
        if (!skyVisible && belowSurface && ceiling >= 0) {
            enclosure = solidSides >= 3 ? "enclosed underground/tunnel" : "underground cavity";
        } else if (!skyVisible && ceiling >= 0) {
            enclosure = solidSides >= 3 ? "enclosed/indoors" : "covered or partly enclosed";
        } else {
            enclosure = solidSides >= 3 ? "open-roof enclosure" : "outdoors/open sky";
        }

        Topology topology = topology(level, origin);
        List<Hazard> hazards = hazards(level, origin);
        StringBuilder out = new StringBuilder();
        out.append("Semantic scene [deterministic, observed_at=").append(observedAt).append("]:\n")
           .append("  - space: ").append(enclosure)
           .append("; sky_visible=").append(skyVisible)
           .append("; ceiling=").append(ceiling < 0 ? "none within 10" : ceiling + " blocks")
           .append("; solid_sides=").append(solidSides).append("/4\n")
           .append("  - local topology: ").append(topology.reachableCells)
           .append(" reachable standing cells within ").append(TOPOLOGY_RADIUS)
           .append(" blocks; frontier directions=")
           .append(topology.frontiers.isEmpty() ? "none (locally enclosed)"
                   : String.join(",", topology.frontiers))
           .append('\n')
           .append(PlayerStructure.describe(level, origin));
        if (hazards.isEmpty()) {
            out.append("  - hazards: none detected within ").append(HAZARD_RADIUS).append(" blocks\n");
        } else {
            out.append("  - hazards:");
            for (Hazard hazard : hazards.stream().limit(5).toList()) {
                out.append(' ').append(hazard.name).append('@')
                   .append(hazard.pos.toShortString()).append(String.format("(%.1f)", hazard.distance));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private record Topology(int reachableCells, List<String> frontiers) {
    }

    private record Hazard(String name, BlockPos pos, double distance) {
    }

    private static Topology topology(ServerLevel level, BlockPos origin) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        Set<String> frontiers = new LinkedHashSet<>();
        open.add(origin);
        seen.add(origin);

        while (!open.isEmpty() && seen.size() < MAX_REACHABLE_CELLS) {
            BlockPos current = open.removeFirst();
            int dx = current.getX() - origin.getX();
            int dz = current.getZ() - origin.getZ();
            if (Math.max(Math.abs(dx), Math.abs(dz)) >= TOPOLOGY_RADIUS - 1) {
                if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
                    frontiers.add(dx > 0 ? "east" : "west");
                }
                if (Math.abs(dz) >= Math.abs(dx) && dz != 0) {
                    frontiers.add(dz > 0 ? "south" : "north");
                }
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[] {0, 1, -1, -2, -3}) {
                    BlockPos next = current.relative(direction).offset(0, dy, 0).immutable();
                    if (next.distSqr(origin) > TOPOLOGY_RADIUS * TOPOLOGY_RADIUS
                            || seen.contains(next) || !PathFinder.canStandAt(level, next)) {
                        continue;
                    }
                    seen.add(next);
                    open.addLast(next);
                    break;
                }
            }
        }
        return new Topology(seen.size(), List.copyOf(frontiers));
    }

    private static int ceilingDistance(ServerLevel level, BlockPos feet, int max) {
        for (int dy = 2; dy <= max + 1; dy++) {
            BlockPos pos = feet.above(dy);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return dy - 1;
            }
        }
        return -1;
    }

    private static int solidSides(ServerLevel level, BlockPos feet) {
        int blocked = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            boolean sideBlocked = true;
            for (int distance = 1; distance <= 3; distance++) {
                BlockPos pos = feet.relative(direction, distance);
                if (PathFinder.canPass(level, pos) && PathFinder.canPass(level, pos.above())) {
                    sideBlocked = false;
                    break;
                }
            }
            if (sideBlocked) {
                blocked++;
            }
        }
        return blocked;
    }

    private static List<Hazard> hazards(ServerLevel level, BlockPos origin) {
        List<Hazard> found = new ArrayList<>();
        Set<String> seenKinds = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-HAZARD_RADIUS, -3, -HAZARD_RADIUS),
                origin.offset(HAZARD_RADIUS, 3, HAZARD_RADIUS))) {
            BlockState state = level.getBlockState(pos);
            String name = hazardName(state);
            if (name == null || !seenKinds.add(name)) {
                continue;
            }
            found.add(new Hazard(name, pos.immutable(), Math.sqrt(pos.distSqr(origin))));
        }
        found.sort(Comparator.comparingDouble(Hazard::distance));
        return found;
    }

    private static String hazardName(BlockState state) {
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            return "lava";
        }
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            return "fire";
        }
        if (state.is(Blocks.CACTUS)) {
            return "cactus";
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return "magma";
        }
        if (state.is(Blocks.POWDER_SNOW)) {
            return "powder_snow";
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            return "campfire";
        }
        return null;
    }
}
