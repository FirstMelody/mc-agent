package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persistent mining skill must own a whole trip for the price of one planning call.
 *
 * <p>Production shape this replaces: a player says "go mining", the model answers with a handful of
 * per-block {@code mine}/{@code escape_up} steps, the plan dies on the first surprise, and the bot
 * buys another ~11k-token planning turn to re-derive what it was already doing. The skill exists so
 * the model says <em>what</em> once and the runtime owns <em>how</em> until the trip ends.
 *
 * <p>Two things are therefore asserted, and neither is visible from reading the code: that the trip
 * actually finishes (ore leaves the ground and enters the pack), and that <em>no second request is
 * sent while it runs</em>. The second is the whole point, so it is measured, not assumed.
 *
 * <p>Phase two is the interrupt contract: a player asking the bot to stop must actually stop the
 * runtime-owned trip rather than queue behind it. The trip must end <em>without</em> a completion
 * report, which is how "cancelled" is told apart from "finished".
 *
 * <pre>
 *   MCAGENT_MINING_GOAL_TEST=true ... runServer                 # expect MINEGOALTEST VERDICT: PASS
 *   MCAGENT_MINING_GOAL_TEST=true MCAGENT_MINING_GOAL=off ... runServer
 *                                                    # expect CONTROL-PASS: no trip ever starts
 * </pre>
 */
public final class MiningGoalSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/minegoaltest");
    private static final String BOT = "MineGoalBot";
    /** Iron ore placed at the far end of the pocket; enough for a trip and a second one. */
    private static final int ORE_PLACED = 6;
    /** How long any single phase may take before the test calls it stuck. */
    private static final int PHASE_TIMEOUT_TICKS = 1800;

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private ServerLevel level;
    private BlockPos origin;
    private BotManager.BotHandle handle;

    /** Set by the test to hand the next model request a specific answer. */
    private final AtomicBoolean serveStart = new AtomicBoolean();
    private final AtomicBoolean serveInterrupt = new AtomicBoolean();
    private volatile int nextAmount;

    private boolean started;
    private boolean finished;
    private int ticks;
    private int phase;
    private int phaseTick;
    private int requestsAtGoalStart = -1;
    private int requestsDuringGoal = -1;
    private String tripOutcome = "";
    private int interruptedAtTick = -1;
    private String toolSchemaProblem = "no request yet";

    private MiningGoalSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_MINING_GOAL_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("MINEGOALTEST: armed (skill {})",
                AgentBrain.miningSkillEnabled() ? "on" : "OFF - positive control");
        return new MiningGoalSmokeTest(server);
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
        AgentBrain brain = this.brain();
        if (brain == null) {
            this.finish(false, "no brain");
            return;
        }
        if (this.ticks - this.phaseTick > PHASE_TIMEOUT_TICKS) {
            this.finish(false, "phase " + this.phase + " timed out after " + PHASE_TIMEOUT_TICKS
                    + " ticks; requests=" + this.model.requestCount()
                    + ", goal=" + (brain.isRunningMiningGoal() ? "running" : "none")
                    + ", ore left=" + this.oreLeft()
                    + ", saw 'started persistent mining goal'="
                    + (this.model.maxOccurrences("started persistent mining goal") > 0));
            return;
        }
        switch (this.phase) {
            case 0 -> this.waitForTripStart(brain);
            case 1 -> this.waitForTripEnd(brain);
            case 2 -> this.armSecondTrip(brain);
            case 3 -> this.waitForSecondTripStart(brain);
            case 4 -> this.interruptSecondTrip(brain);
            default -> this.checkInterrupted(brain);
        }
    }

    /** Phase 0: the model says "go mining" once and the runtime takes the trip over. */
    private void waitForTripStart(AgentBrain brain) {
        if (!brain.isRunningMiningGoal()) {
            return;
        }
        this.requestsAtGoalStart = this.model.requestCount();
        this.requestsDuringGoal = this.requestsAtGoalStart;
        this.toolSchemaProblem = findInvalidToolType(this.model.lastRequest());
        this.phase = 1;
        this.phaseTick = this.ticks;
        LOG.info("MINEGOALTEST trip start : goal active after {} tick(s); {} model request(s) spent "
                        + "so far, ore on the ground={}, tool schema check={}", this.ticks,
                this.requestsAtGoalStart, this.oreLeft(),
                this.toolSchemaProblem == null ? "all types valid" : this.toolSchemaProblem);
    }

    /** Phase 1: the trip runs to completion, and it must do so without another request. */
    private void waitForTripEnd(AgentBrain brain) {
        if (brain.isRunningMiningGoal()) {
            this.requestsDuringGoal = Math.max(this.requestsDuringGoal, this.model.requestCount());
            return;
        }
        this.tripOutcome = String.valueOf(brain.lastMiningGoalOutcome());
        boolean completed = this.tripOutcome.startsWith("persistent mining goal complete");
        int iron = this.ironInPack();
        boolean oreGone = this.oreLeft() == 0;
        boolean noExtraRequests = this.requestsDuringGoal == this.requestsAtGoalStart;
        LOG.info("MINEGOALTEST trip end   : outcome='{}'", this.tripOutcome);
        LOG.info("MINEGOALTEST evidence   : iron in pack={} ore left={} requests at start={} "
                        + "requests while running={}", iron, this.oreLeft(), this.requestsAtGoalStart,
                this.requestsDuringGoal);
        if (!completed || !oreGone || iron < 3 || !noExtraRequests
                || this.toolSchemaProblem != null) {
            this.finish(false, "completed=" + completed + " oreGone=" + oreGone + " ironInPack="
                    + iron + " noExtraRequests=" + noExtraRequests
                    + " toolSchemaProblem=" + this.toolSchemaProblem
                    + " (the trip must finish on the first request; outcome='" + this.tripOutcome
                    + "')");
            return;
        }
        this.phase = 2;
        this.phaseTick = this.ticks;
    }

    /** Phase 2: put the bot back at the site with fresh ore and arm a second trip. */
    private void armSecondTrip(AgentBrain brain) {
        // The trip ends by teleporting the bot to world spawn, which is 170-odd blocks away; the
        // second trip has to start from the site or it would search real terrain for ore.
        this.handle.player().teleportTo(this.level, this.origin.getX() + 0.5D, this.origin.getY(),
                this.origin.getZ() + 0.5D, java.util.Set.of(), 0.0F, 0.0F);
        this.placeOre(3);
        this.nextAmount = 1;
        this.serveStart.set(true);
        this.phase = 3;
        this.phaseTick = this.ticks;
        LOG.info("MINEGOALTEST second trip: bot back at {} with {} ore; arming start_mining",
                this.origin.toShortString(), this.oreLeft());
    }

    private void waitForSecondTripStart(AgentBrain brain) {
        if (!brain.isRunningMiningGoal()) {
            return;
        }
        this.phase = 4;
        this.phaseTick = this.ticks;
        LOG.info("MINEGOALTEST second trip: goal active again; now arming interrupt");
    }

    /**
     * Phase 4: ask for a decision that answers {@code interrupt}, which is what a player telling the
     * bot to stop produces.
     */
    private void interruptSecondTrip(AgentBrain brain) {
        this.serveInterrupt.set(true);
        brain.requestDecisionNow();
        this.phase = 5;
        this.phaseTick = this.ticks;
    }

    /** Phase 5: the trip is gone, and it went without reporting itself finished. */
    private void checkInterrupted(AgentBrain brain) {
        if (brain.isRunningMiningGoal()) {
            return;
        }
        if (this.interruptedAtTick < 0) {
            this.interruptedAtTick = this.ticks;
        }
        boolean interruptSeen = this.model.maxOccurrences("cancelled the persistent mining goal") >= 1;
        boolean notReportedComplete =
                String.valueOf(brain.lastMiningGoalOutcome()).equals(this.tripOutcome);
        // A tool result reaches the model only in the *next* request's transcript, and the interrupt
        // turn's own request predates it. Waiting for that request is the difference between "the
        // reply string was built" and "the model was actually told", so wait rather than assume.
        if (!interruptSeen && this.ticks - this.interruptedAtTick < 400) {
            return;
        }
        LOG.info("MINEGOALTEST interrupt  : goal gone={} interrupt result seen by the model={} "
                        + "outcome unchanged (not a completion)={} requests={} last request "
                        + "mentions the interrupt result={}",
                !brain.isRunningMiningGoal(), interruptSeen, notReportedComplete,
                this.model.requestCount(), this.model.lastRequest().contains("stopped mining"));
        this.finish(interruptSeen && notReportedComplete, interruptSeen
                ? "the interrupt cancelled the runtime-owned trip: the model was told "
                        + "'cancelled the persistent mining goal' and no completion was recorded"
                : "the goal stopped but the model was never shown the interrupt result within 400 "
                        + "ticks of it; 'cancelled' has to be told to the model, not only logged");
    }

    // --- terrain, bot and pack ---------------------------------------------------------------

    /**
     * The first tool parameter whose declared type is not a JSON Schema type, or null.
     *
     * <p>Worth asserting on the real outgoing request because nothing else here can catch its
     * absence: the scripted model ignores tool schemas, so a malformed one is invisible to every
     * deterministic test and shows up only as the production provider refusing the whole call. That
     * is exactly what {@code "array of strings"} did - one bad hint, every planning request answered
     * with {@code 11129 invalid function call parameters}.
     */
    private static String findInvalidToolType(String requestBody) {
        java.util.Set<String> allowed = java.util.Set.of(
                "string", "number", "integer", "boolean", "array", "object");
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(requestBody).getAsJsonObject();
            if (!root.has("tools") || !root.get("tools").isJsonArray()) {
                return "the request carried no tools array";
            }
            for (com.google.gson.JsonElement entry : root.getAsJsonArray("tools")) {
                com.google.gson.JsonObject function =
                        entry.getAsJsonObject().getAsJsonObject("function");
                String tool = function.get("name").getAsString();
                com.google.gson.JsonObject parameters = function.getAsJsonObject("parameters");
                if (parameters == null || !parameters.has("properties")) {
                    continue;
                }
                for (var property : parameters.getAsJsonObject("properties").entrySet()) {
                    com.google.gson.JsonElement declared = property.getValue().getAsJsonObject()
                            .get("type");
                    if (declared == null) {
                        continue;
                    }
                    String type = declared.getAsString();
                    if (!allowed.contains(type)) {
                        return tool + "." + property.getKey() + " declares '" + type + "'";
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "could not parse the request body: " + e.getMessage();
        }
    }

    private AgentBrain brain() {
        if (this.handle == null || Agent.brainManager() == null) {
            return null;
        }
        return Agent.brainManager().get(this.handle.player().getUUID());
    }

    /** How many of the placed iron ore blocks are still there. */
    private int oreLeft() {
        int left = 0;
        for (BlockPos pos : this.orePositions()) {
            if (this.level.getBlockState(pos).is(Blocks.IRON_ORE)) {
                left++;
            }
        }
        return left;
    }

    /** Items in the pack whose id mentions iron and which are not tools or armour. */
    private int ironInPack() {
        int count = 0;
        for (ItemStack stack : this.handle.player().getInventory().items) {
            if (stack.isEmpty() || stack.isDamageableItem()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("iron")) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * The ore face at the far end of the pocket.
     *
     * <p>Two cells are on the pocket floor and four are stacked in the wall, so a radius job has a
     * connected vein to spread into exactly as it would underground.
     */
    private BlockPos[] orePositions() {
        BlockPos far = this.origin.offset(4, 0, 0);
        return new BlockPos[] {
                far,
                far.offset(0, 1, 0),
                far.offset(0, 0, 1),
                far.offset(0, 0, -1),
                far.offset(-1, 0, 1),
                far.offset(-1, 0, -1),
        };
    }

    private void placeOre(int howMany) {
        BlockPos[] positions = this.orePositions();
        for (int i = 0; i < Math.min(howMany, positions.length); i++) {
            this.level.setBlockAndUpdate(positions[i], Blocks.IRON_ORE.defaultBlockState());
        }
    }

    private void begin() {
        try {
            // The answers are handed out by the test, one phase at a time. Anything not armed is
            // answered with silence, so an unexpected extra decision cannot consume a scripted reply.
            this.model = new ScriptedLlmServer(body -> {
                if (this.serveStart.compareAndSet(true, false)) {
                    return ScriptedLlmServer.toolCall("minegoal_start", "start_mining",
                            "{\"primary\":\"iron_ore\",\"amount\":" + this.nextAmount + "}");
                }
                if (this.serveInterrupt.compareAndSet(true, false)) {
                    return ScriptedLlmServer.toolCall("minegoal_stop", "interrupt", "{}");
                }
                return ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            this.finish(false, "could not start scripted endpoint: " + e.getMessage());
            return;
        }

        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        // Far enough from spawn that the base-terrain rule (96 blocks around home) cannot apply,
        // high above the real surface so the pocket is the bot's whole world.
        int x = spawn.getX() + 120;
        int z = spawn.getZ() - 120;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.origin = new BlockPos(x, Math.max(spawn.getY() + 24, terrain + 12), z);

        for (int dx = -3; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        // A corridor the bot stands in, open at the ore face.
        for (int dx = 0; dx <= 4; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.placeOre(ORE_PLACED);

        this.handle = Agent.botManager().spawn(BOT, this.level,
                Vec3.atBottomCenterOf(this.origin), true);
        if (this.handle == null) {
            this.finish(false, "could not spawn bot");
            return;
        }
        this.handle.player().getInventory().clearContent();
        this.handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        if (!Agent.attachBrain(this.handle.player())) {
            this.finish(false, "could not attach scripted brain");
            return;
        }
        this.nextAmount = 3;
        this.serveStart.set(true);
        LOG.info("MINEGOALTEST terrain    : origin={} ore face at {} ({} block(s), pocket 5x3x2)",
                this.origin.toShortString(), this.origin.offset(4, 0, 0).toShortString(),
                this.oreLeft());
    }

    private void finish(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("MINEGOALTEST result     : {}", detail);
        if (!AgentBrain.miningSkillEnabled()) {
            // The control run: with the skill switched off the model's start_mining call cannot
            // create a trip, so "no trip ever started" is the expected outcome and proves this test
            // can tell a runtime-owned trip from per-block planning.
            LOG.info("MINEGOALTEST VERDICT    : {}", pass
                    ? "CONTROL-FAIL (skill disabled but a trip still ran; this test cannot detect "
                            + "the difference it is for)"
                    : "CONTROL-PASS (skill disabled and no trip could start, as expected)");
        } else {
            LOG.info("MINEGOALTEST VERDICT    : {}", pass ? "PASS" : "FAIL");
        }

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.origin != null) {
            for (int dx = -3; dx <= 6; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -2; dy <= 3; dy++) {
                        this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        AgentBrain.shutdown();
        if (this.model != null) {
            try {
                this.model.close();
            } catch (Throwable ignored) {
                // Best effort in a gated test process.
            }
        }
        this.server.halt(false);
    }
}
