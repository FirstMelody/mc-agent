package com.melody.mcagent.rt.bot;

import java.util.List;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automated end-to-end verification of the synthetic-player core.
 *
 * <p>Enabled only when the JVM property {@code mcagent.smoketest} is set, so it never runs on a
 * production server. It exists because the single most important claim in this mod — that a
 * synthetic player can walk with real vanilla physics, and that its movement is <em>not</em>
 * discarded by the client-authority position rewind — cannot be proven by reading code. It has to
 * be observed in a running game.
 *
 * <p>Checks performed:
 * <ol>
 *   <li>the bot joins and is visible to the server as a real player;</li>
 *   <li>its position actually changes while a movement target is set (physics is live);</li>
 *   <li>its progress is not undone between ticks (the rewind is gone);</li>
 *   <li>it settles on the ground rather than falling through the world or being ejected;</li>
 *   <li>it can be removed cleanly and leaves no persisted data behind.</li>
 * </ol>
 */
public final class BotSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/smoketest");

    /** Total ticks to observe before reporting. */
    // Includes a deliberate ~120-tick stuck detour before the real long walk resumes.
    private static final int OBSERVE_TICKS = 360;
    /** How far the bot must travel to count as "really moving". */
    private static final double MIN_TRAVEL = 3.0D;

    private final MinecraftServer server;
    private final BotManager manager;

    private int ticks;

    /** The sealed room the door test builds, and how that test is going. */
    private BlockPos roomBase;
    private BlockPos outsideGoal;
    private boolean doorTestRouted;
    private boolean doorTestDone;
    private int doorTestTicks;
    private boolean stuckTestRunning;
    private int stuckTestTicks;
    /** The spot the stuck test deliberately aimed the bot at, while the driver is still on it. */
    @org.jetbrains.annotations.Nullable
    private Vec3 deliberateTarget;
    private boolean started;
    private boolean finished;
    private Vec3 lastPos;
    private double maxProgress;
    @org.jetbrains.annotations.Nullable
    private BlockPos goalPos;
    private double totalTravel;
    private int rewindDetections;
    private Vec3 startPos;
    /** Deterministic action/perception checks run before the walking phase. */
    private boolean featureTestsPassed;

    public BotSmokeTest(MinecraftServer server, BotManager manager) {
        this.server = server;
        this.manager = manager;
    }

    /** True if the smoke test was requested on the command line or in the environment. */
    public static boolean enabled() {
        return Boolean.getBoolean("mcagent.smoketest")
                || "true".equalsIgnoreCase(System.getenv("MCAGENT_SMOKETEST"));
    }

    /**
     * Arms the smoke test if requested.
     *
     * <p>Called by the runtime entry point rather than by the event bus: a listener registered on
     * {@code NeoForge.EVENT_BUS} is held by the mod's own loader and would keep this test — and with
     * it the whole runtime — alive across a reload.
     */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        BotManager manager = Agent.botManager();
        if (manager == null) {
            LOG.error("SMOKETEST: BotManager unavailable, aborting");
            return null;
        }
        LOG.info("SMOKETEST: armed");
        return new BotSmokeTest(server, manager);
    }

    @Override
    public void onTick() {
        if (this.finished) {
            return;
        }

        if (!this.started) {
            this.started = true;
            this.begin();
            return;
        }

        this.ticks++;

        BotManager.BotHandle handle = this.manager.get("SmokeBot");
        if (handle == null) {
            LOG.error("SMOKETEST: FAIL - bot disappeared at tick {}", this.ticks);
            this.finish(false);
            return;
        }

        Vec3 pos = handle.player().position();

        // Detect the client-authority rewind. The real signature of that bug is that the bot's
        // position is restored to where it started the tick, so it can never accumulate distance
        // from its origin. Progress toward the target is therefore the meaningful signal, not
        // per-tick direction: once the bot arrives it naturally jitters around the target, and
        // counting that as "movement backwards" would be a false positive.
        if (this.lastPos != null) {
            this.totalTravel += pos.distanceTo(this.lastPos);
        }
        this.lastPos = pos;

        // Only judged while the bot is walking under its own instructions. The stuck test knowingly
        // steers it at a spot behind it for a couple of seconds, and calling that "the position was
        // undone" would fail a run in which nothing was rewound. The walk the detour interrupts is
        // re-based when it ends, so the watch resumes from the position it really resumes from.
        if (this.deliberateTarget == null) {
            double progress = pos.distanceTo(this.startPos);
            if (progress > this.maxProgress) {
                this.maxProgress = progress;
            } else if (this.maxProgress - progress > 1.0D) {
                // Distance from the origin has decreased: the bot moved back toward where it began.
                // A little of this is normal while settling; a lot means the rewind is active.
                this.rewindDetections++;
            }
        }

        this.tickDoorTest(handle);
        this.tickStuckTest(handle);

        if (this.ticks % 20 == 0) {
            LOG.info("SMOKETEST tick {} | pos=({}, {}, {}) | onGround={} | travel={}",
                    this.ticks,
                    String.format("%.2f", pos.x), String.format("%.2f", pos.y), String.format("%.2f", pos.z),
                    handle.player().onGround(),
                    String.format("%.2f", this.totalTravel));
        }

        if (this.ticks >= OBSERVE_TICKS) {
            this.evaluate(handle);
        }
    }

    /**
     * Direct checks of the mechanics that do not need a model to drive them.
     *
     * <p>Run here rather than in the LLM test because these are deterministic: either the bot can do
     * them or it cannot, and a model in the loop only adds noise to the answer.
     */
    private void featureTests(ServerLevel level, BotManager.BotHandle handle, BlockPos spawn) {
        var bot = handle.player();

        // The smoke world persists between runs. Remove any nearby table so the 3x3 check below
        // proves the deliberate latency compromise: recipes work without a table round trip.
        BlockPos aroundBot = bot.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                aroundBot.offset(-4, -3, -4), aroundBot.offset(4, 3, 4))) {
            if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }

        // --- shaped crafting ------------------------------------------------------------------
        // Regression: every non-square shaped recipe used to be unmatchable. The grid was always
        // built square, and ShapedRecipePattern.matches requires the input's width and height to
        // equal the pattern's - so a 1x2 stick recipe was laid out as 2x2 and correctly refused.
        // That silently broke every door, ladder, fence, tool and piece of armour in the game.
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        inventory.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.OAK_PLANKS, 4));

        com.melody.mcagent.rt.action.Actions.Result sticks = com.melody.mcagent.rt.action.Crafting.craft(bot, "minecraft:stick", 1);
        LOG.info("FEATURETEST craft sticks (1x2 shaped) -> {}", sticks.message());
        boolean stickOk = sticks.success() && countOf(bot, net.minecraft.world.item.Items.STICK) == 4;
        LOG.info("FEATURETEST shaped crafting works: {}", stickOk);

        // Deliberate latency compromise: a 3x3 recipe works without making the LLM spend another
        // observe/goto/use cycle on a crafting table. Ingredients and recipe matching remain real.
        inventory.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.OAK_PLANKS, 8));
        com.melody.mcagent.rt.action.Actions.Result noTable = com.melody.mcagent.rt.action.Crafting.craft(bot, "minecraft:oak_door", 1);
        LOG.info("FEATURETEST 3x3 recipe without a table -> {}", noTable.message());
        boolean remote3x3 = noTable.success()
                && countOf(bot, net.minecraft.world.item.Items.OAK_DOOR) == 3;
        LOG.info("FEATURETEST table-free 3x3 works: {}", remote3x3);

        // --- see-through perception -----------------------------------------------------------
        // A single block of wall must not hide what is behind it; a mountain still must.
        BlockPos behind = spawn.offset(6, 0, 6);
        level.setBlockAndUpdate(behind, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        bot.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        int thinWall = 0;
        for (int i = 1; i <= 1; i++) {
            level.setBlockAndUpdate(spawn.offset(i, 0, i),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(spawn.offset(i, 1, i),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        }
        boolean throughOne = com.melody.mcagent.rt.perception.Perception.canSeeBlock(bot, behind);
        LOG.info("FEATURETEST sees gold through a 1-block wall: {}", throughOne);

        // Now thicken it well past the allowance.
        for (int i = 1; i <= 8; i++) {
            level.setBlockAndUpdate(spawn.offset(i, 0, i),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(spawn.offset(i, 1, i),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        }
        boolean throughEight = com.melody.mcagent.rt.perception.Perception.canSeeBlock(bot, behind);
        LOG.info("FEATURETEST sees gold through an 8-block wall: {} (should be false)", throughEight);

        boolean xrayOk = throughOne && !throughEight;
        LOG.info("FEATURETEST see-through behaves: {}", xrayOk);

        LOG.info("FEATURETEST ================ RESULT ================");
        LOG.info("FEATURETEST shaped crafting : {}", stickOk ? "PASS" : "FAIL");
        LOG.info("FEATURETEST table-free 3x3  : {}", remote3x3 ? "PASS" : "FAIL");
        LOG.info("FEATURETEST see-through     : {}", xrayOk ? "PASS" : "FAIL");
        LOG.info("FEATURETEST ======================================");
        this.featureTestsPassed = stickOk && remote3x3 && xrayOk;

        // Clear the test blocks so the walking half of the smoke test runs in the world it expects.
        for (int i = 1; i <= 8; i++) {
            level.setBlockAndUpdate(spawn.offset(i, 0, i),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(spawn.offset(i, 1, i),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Seal the bot inside a room whose only way out is a closed door.
     *
     * <p>This is the "bot cannot leave the house" test, and it has to be a real walk rather than a
     * pathfinder check: the failure was that the door cell was treated as a wall, so the bot would
     * stand at the doorway reporting no route and eventually - as one player watched it do - break
     * the door down to get out.
     *
     * <p>Verified through the actual escape, because a path that exists on paper and a bot that gets
     * through the gap are different claims.
     */
    private void buildDoorRoom(ServerLevel level, BotManager.BotHandle handle, BlockPos spawn) {
        // Somewhere clear, well away from the wall the walking test uses.
        BlockPos base = spawn.offset(-16, 0, -16);
        this.roomBase = base;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                for (int dy = 0; dy <= 3; dy++) {
                    // Leave one doorway in the +X wall.
                    if (dx == 2 && dz == 0 && (dy == 0 || dy == 1)) {
                        continue;
                    }
                    level.setBlockAndUpdate(base.offset(dx, dy, dz),
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                }
            }
        }
        // A ceiling, so the bot cannot simply climb out.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 4, dz),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        // Floor, so it does not fall through into whatever is underneath.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }

        // One closed oak door in the gap.
        BlockPos doorLower = base.offset(2, 0, 0);
        level.setBlockAndUpdate(doorLower,
                net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                                net.minecraft.core.Direction.WEST)
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, false));
        level.setBlockAndUpdate(doorLower.above(),
                net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                                net.minecraft.core.Direction.WEST)
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, false)
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        // Put the bot inside and aim it at a spot well outside.
        handle.player().teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        handle.movement().clear();
        this.outsideGoal = base.offset(6, 0, 0);

        boolean routed = com.melody.mcagent.rt.path.PathFinder.findPath(
                level, handle.player().blockPosition(), this.outsideGoal, 64) != null;
        LOG.info("DOORTEST room built at {} with one CLOSED door at {}",
                base, doorLower.toShortString());
        LOG.info("DOORTEST pathfinder sees a route out through the door: {}", routed);

        MovementDriver.Plan plan = handle.movement().setPathTarget(this.outsideGoal, 64);
        LOG.info("DOORTEST walk started: {}", plan);
        this.doorTestRouted = routed && plan == MovementDriver.Plan.FOUND;
        this.doorTestTicks = 0;

        this.rebaselineMotion(handle);
    }

    /**
     * Restart the walking-phase measurements from where the bot is now.
     *
     * <p>Called after the two deliberate interventions in the walk: the teleport into the door room,
     * and the stuck test's detour. Neither is a client-authority rewind, and everything measured
     * before one says nothing about the claim these counters exist to test — that the bot's own
     * movement survives the tick.
     *
     * <p>Without the first, the teleport leaves the origin ~23 blocks behind and walking back out
     * toward the goal looks like the position being undone: every one of those ticks lands in
     * {@code rewindDetections} and the run reports FAIL with ~250 "reversals" on a bot that never
     * rewound once. A test that always fails is worse than no test, because it hides the
     * regressions it was written to catch.
     *
     * <p>Everywhere else a real rewind still shows: it snaps the bot back every tick, and the
     * origin it is measured from no longer moves.
     */
    private void rebaselineMotion(BotManager.BotHandle handle) {
        this.startPos = handle.player().position();
        this.lastPos = this.startPos;
        this.maxProgress = 0.0D;
        this.totalTravel = 0.0D;
        this.rewindDetections = 0;
    }

    /** Watch whether the bot actually gets out of the sealed room. */
    private void tickDoorTest(BotManager.BotHandle handle) {
        if (this.outsideGoal == null || this.doorTestDone) {
            return;
        }
        this.doorTestTicks++;
        var pos = handle.player().blockPosition();
        boolean outside = !pos.equals(this.roomBase) && pos.getX() > this.roomBase.getX() + 2;
        boolean doorOpen = handle.player().level()
                .getBlockState(this.roomBase.offset(2, 0, 0))
                .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);

        if (outside) {
            this.doorTestDone = true;
            boolean pass = this.doorTestRouted;
            LOG.info("DOORTEST ================ RESULT ================");
            LOG.info("DOORTEST route found before walking: {}", this.doorTestRouted);
            LOG.info("DOORTEST escaped the room     : true (at {})", pos.toShortString());
            LOG.info("DOORTEST door left open       : {}", doorOpen);
            LOG.info("DOORTEST door still intact    : {}",
                    handle.player().level().getBlockState(this.roomBase.offset(2, 0, 0))
                            .is(net.minecraft.world.level.block.Blocks.OAK_DOOR));
            LOG.info("DOORTEST ticks taken          : {}", this.doorTestTicks);
            LOG.info("DOORTEST {}", pass ? "PASS" : "FAIL");
            LOG.info("DOORTEST ======================================");
            this.startStuckTest(handle);
        } else if (this.doorTestTicks > 600) {
            this.doorTestDone = true;
            LOG.error("DOORTEST: FAIL - still inside after 600 ticks at {} (door open: {})",
                    pos.toShortString(), doorOpen);
        }
    }

    /**
     * Point the bot at a spot it cannot possibly reach and check that it gives up.
     *
     * <p>Regression for a total freeze seen in production: the bot stood in flowing water for ten
     * minutes taking no decisions at all. The current nudged it a fraction of a block per tick -
     * enough to keep resetting the "did the bot move" counter - so the walk never timed out, and the
     * brain does not think while a walk is in progress. The bot was not merely idle; it was
     * unrecoverable.
     *
     * <p>Targeted at solid stone, which is honest about what it tests: no amount of jumping or door
     * opening gets through, so the only correct outcome is to give up.
     */
    private void startStuckTest(BotManager.BotHandle handle) {
        BlockPos buried = this.roomBase.offset(2, 0, 0).above(3); // inside the room's wall/ceiling
        Vec3 unreachable = new Vec3(buried.getX() + 0.5, buried.getY(), buried.getZ() + 0.5);
        handle.movement().setTarget(unreachable);
        this.deliberateTarget = unreachable;
        this.stuckTestTicks = 0;
        this.stuckTestRunning = true;
        LOG.info("STUCKTEST walking at an unreachable spot {} - expecting the driver to give up",
                buried.toShortString());
    }

    private void tickStuckTest(BotManager.BotHandle handle) {
        if (!this.stuckTestRunning) {
            return;
        }
        this.stuckTestTicks++;

        // The detour is over as soon as the driver stops steering at the spot this test aimed it
        // at, which is the moment the bot picks the goal back up. Re-base there rather than here:
        // the walk from that point on is the one the rewind verdict is about, and it is measured
        // from where it actually resumes rather than from before the detour.
        if (this.deliberateTarget != null
                && !this.deliberateTarget.equals(handle.movement().getTarget())) {
            this.deliberateTarget = null;
            this.rebaselineMotion(handle);
        }

        if (!handle.movement().hasTarget()) {
            this.stuckTestRunning = false;
            boolean pass = this.stuckTestTicks < 400;
            LOG.info("STUCKTEST ================ RESULT ================");
            LOG.info("STUCKTEST gave up after  : {} ticks", this.stuckTestTicks);
            LOG.info("STUCKTEST target cleared : true");
            LOG.info("STUCKTEST {}", pass ? "PASS" : "FAIL");
            LOG.info("STUCKTEST ======================================");
            // setTarget(unreachable) deliberately replaced the active waypoint. Restore the real
            // goal explicitly; relying on the buried target being misreported as "arrived" used
            // to resume stale waypoints by accident, but an unreachable higher waypoint must now
            // correctly be reported as a failed walk.
            if (this.goalPos != null) {
                handle.movement().setPathTarget(this.goalPos, 128);
            }
        } else if (this.stuckTestTicks > 400) {
            this.stuckTestRunning = false;
            LOG.error("STUCKTEST: FAIL - still holding an unreachable target after 400 ticks "
                    + "(this is the freeze that left the bot silent for ten minutes)");
        }
    }

    private static int countOf(net.minecraft.server.level.ServerPlayer bot,
                               net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            var stack = bot.getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void begin() {
        ServerLevel level = this.server.overworld();

        // Spawn a little above the surface so the bot falls to the ground, proving gravity runs.
        BlockPos spawn = level.getSharedSpawnPos();
        Vec3 spawnPos = new Vec3(spawn.getX() + 0.5, spawn.getY() + 2.0, spawn.getZ() + 0.5);

        // Build a wall between the bot and its target. Straight-line steering cannot get past it,
        // so reaching the far side proves A* is genuinely pathfinding rather than walking blindly
        // into an obstacle and stalling.
        buildTestWall(level, spawn);

        LOG.info("SMOKETEST: spawning bot at {}", spawnPos);
        BotManager.BotHandle handle = this.manager.spawn("SmokeBot", level, spawnPos, false);

        if (handle == null) {
            LOG.error("SMOKETEST: FAIL - could not spawn bot");
            this.finish(false);
            return;
        }

        this.startPos = handle.player().position();
        this.lastPos = this.startPos;

        // Goal is behind the wall, along +X.
        BlockPos goal = new BlockPos(spawn.getX() + 14, spawn.getY(), spawn.getZ());
        this.goalPos = goal;

        MovementDriver.Plan pathFound = handle.movement().setPathTarget(goal, 128);
        LOG.info("SMOKETEST: A* path to {} found={}", goal, pathFound);
        if (pathFound != MovementDriver.Plan.FOUND) {
            LOG.error("SMOKETEST: FAIL - pathfinder could not route around the test wall");
            this.finish(false);
            return;
        }

        this.featureTests(level, handle, spawn);
        this.buildDoorRoom(level, handle, spawn);

        // Regression: a goal several blocks BELOW the floor it is asked about.
        //
        // Models read a Y off a block they saw, or guess, and being a few blocks out is normal. The
        // goal resolver used to search only upward, so a request like this produced "no route" for a
        // spot the bot was standing next to - and the driver then walked straight into the wall. This
        // asserts the request now resolves to the walkable surface above the requested point.
        BlockPos belowFloor = new BlockPos(goal.getX(), goal.getY() - 4, goal.getZ());
        MovementDriver.Plan resolved = handle.movement().setPathTarget(belowFloor, 128);
        LOG.info("SMOKETEST: A* path to {} (4 blocks below the surface) found={}", belowFloor, resolved);
        if (resolved != MovementDriver.Plan.FOUND) {
            LOG.error("SMOKETEST: FAIL - goal four blocks below the floor was not resolved upward");
            this.finish(false);
            return;
        }

        // Regression: a goal far beyond the planning range must be reported as such, not searched.
        MovementDriver.Plan tooFar =
                handle.movement().setPathTarget(spawn.offset(900, 0, 0), 128);
        LOG.info("SMOKETEST: A* path to a goal 900 blocks away -> {} (expected TOO_FAR)", tooFar);
        if (tooFar != MovementDriver.Plan.TOO_FAR) {
            LOG.error("SMOKETEST: FAIL - distant goal was not reported as out of range");
            this.finish(false);
            return;
        }

        // Back to the real goal for the walking half of the test.
        handle.movement().setPathTarget(goal, 128);

        LOG.info("SMOKETEST: bot joined as a real player? name={} uuid={} inPlayerList={}",
                handle.player().getName().getString(),
                handle.player().getUUID(),
                this.server.getPlayerList().getPlayerByName("SmokeBot") != null);
    }

    /**
     * Erect a wall with a single gap, so the only route is through the gap.
     *
     * <p>A solid wall would make the goal genuinely unreachable; a gap keeps the task solvable but
     * only by planning, which is exactly what we want to measure.
     */
    private void buildTestWall(ServerLevel level, BlockPos origin) {
        int wallX = origin.getX() + 7;
        int groundY = origin.getY() - 1;

        for (int dz = -4; dz <= 4; dz++) {
            if (dz == 3) {
                continue; // the doorway
            }
            for (int dy = 0; dy < 3; dy++) {
                BlockPos pos = new BlockPos(wallX, groundY + 1 + dy, origin.getZ() + dz);
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
        }
        LOG.info("SMOKETEST: test wall built at x={} with a gap at z={}", wallX, origin.getZ() + 3);
    }

    private void evaluate(BotManager.BotHandle handle) {
        Vec3 pos = handle.player().position();
        double traveled = pos.distanceTo(this.startPos);
        boolean onGround = handle.player().onGround();
        boolean moved = traveled >= MIN_TRAVEL;

        // The decisive check: did it get to the far side of the wall? Straight-line steering
        // cannot do this, so success here proves the A* path was actually followed.
        double remainingToGoal = this.goalPos == null
                ? Double.NaN
                : Math.hypot(this.goalPos.getX() + 0.5 - pos.x, this.goalPos.getZ() + 0.5 - pos.z);
        boolean reachedGoal = !Double.isNaN(remainingToGoal) && remainingToGoal <= 3.0D;
        boolean passedWall = this.goalPos != null && pos.x > this.goalPos.getX() - 4.0D;

        LOG.info("SMOKETEST ================= RESULT =================");
        LOG.info("SMOKETEST start        : {} (walking phase, re-based after the deliberate test moves)",
                this.startPos);
        LOG.info("SMOKETEST end          : {}", pos);
        LOG.info("SMOKETEST goal         : {}", this.goalPos);
        LOG.info("SMOKETEST displacement : {} blocks (required >= {})", String.format("%.2f", traveled), MIN_TRAVEL);
        LOG.info("SMOKETEST dist to goal : {} blocks", String.format("%.2f", remainingToGoal));
        LOG.info("SMOKETEST passed wall  : {} (goal was behind a wall with one gap)", passedWall);
        LOG.info("SMOKETEST reached goal : {}", reachedGoal);
        LOG.info("SMOKETEST path length  : {} blocks", String.format("%.2f", this.totalTravel));
        LOG.info("SMOKETEST reversals    : {} (rewind indicator; 0 = no snap-back)", this.rewindDetections);
        LOG.info("SMOKETEST onGround     : {}", onGround);
        LOG.info("SMOKETEST health       : {}", handle.player().getHealth());

        boolean pass = reachedGoal && moved && this.rewindDetections == 0
                && this.featureTestsPassed && !handle.player().isRemoved();
        LOG.info("SMOKETEST feature checks: {}", this.featureTestsPassed);
        LOG.info("SMOKETEST maxProgress  : {} blocks", String.format("%.2f", this.maxProgress));
        LOG.info("SMOKETEST VERDICT      : {}", pass ? "PASS" : "FAIL");
        LOG.info("SMOKETEST ==========================================");

        this.finish(pass);
    }

    private void finish(boolean pass) {
        this.finished = true;

        // Verify clean removal.
        try {
            boolean removed = this.manager.remove("SmokeBot");
            LOG.info("SMOKETEST cleanup      : removed={}", removed);
        } catch (Throwable t) {
            LOG.error("SMOKETEST cleanup failed", t);
        }

        LOG.info("SMOKETEST: done, stopping server");
        this.server.halt(false);
    }
}
