package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.Config;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end proof of the routing layer: Jev decides whether an ordinary decision needs the planning
 * model at all.
 *
 * <p>Before routing, "the cooldown expired" was answered with a 12k-token planning turn every couple
 * of seconds, whether or not the bot was already in the middle of a job. The routing layer asks the
 * cheap typed question instead - is there work in flight worth carrying on with, or does this need a
 * fresh plan - and a confident CONTINUE skips the turn entirely.
 *
 * <p>This harness drives the real loop against a stub System One endpoint whose answer it controls
 * and a scripted planning model that only counts requests, so every phase can be measured:
 * <pre>
 *   0 routing=active, nothing queued   : the adviser is NOT asked, and the bot plans
 *   1 routing=shadow, work in flight   : asked, and the planning turn still happens
 *   2 routing=active, CONTINUE 0.95    : no planning turn, and the job keeps making progress
 *   3 routing=active, ESCALATE_LLM     : the planning turn happens
 *   4 COMMAND (/mcagent think)         : never routed, straight to the planning model
 *   5 RESUME with nothing to continue  : plans without asking
 *   6 routing=off positive control     : repeated planning calls return
 *   7 zero-yield standing goal         : one re-plan, then bounded local backoff
 * </pre>
 * The stub answers by question id, because the speech gate, the mining recovery and the routing layer
 * share one endpoint and a stub that answered whatever arrived first would mis-answer silently.
 */
public final class JevRoutingSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/routingtest");
    private static final String BOT = "RouteBot";
    /** The question id the routing layer must use; the stub answers by id, not by arrival order. */
    private static final String ROUTING = "routing";
    /** How long a phase may take before the harness gives up on it. */
    private static final int PHASE_CAP_TICKS = 400;
    /** Long enough for at least three idle decision cycles (the busy cooldown is 40 ticks). */
    private static final int SETTLE_TICKS = 140;
    /** Radius of the mining job used as "work in flight"; deliberately far too big to finish. */
    private static final int WORK_RADIUS = 6;

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private StubSystemOne jev;
    private String[] savedLlmSettings;
    private Path configPath;
    private String originalConfig;
    private ServerLevel level;
    private BlockPos base;
    private final List<BlockPos> workTargets = new ArrayList<>();
    private int nextWorkTarget;

    private boolean started;
    private boolean finished;
    private int phase;
    private int phaseTick;
    private int ticks;
    private boolean phaseStarted;
    private Snapshot before = new Snapshot(0, 0, 0, -1);
    private Map<String, Object> lastCounters = Map.of();
    private final List<String> failures = new ArrayList<>();

    private JevRoutingSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_ROUTING_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("ROUTINGTEST: armed");
        return new JevRoutingSmokeTest(server);
    }

    /** One measurement, taken while the bot is idle and no decision is in flight. */
    private record Snapshot(int routingCalls, int llmCalls, int turns, int mineBroken) {
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
            this.finish("bot disappeared");
            return;
        }
        Map<String, Object> state = brain.debugState();
        Object counters = state.get("jevRouting");
        if (counters instanceof Map<?, ?> map) {
            this.lastCounters = rawCast(map);
        }
        if (this.ticks - this.phaseTick > PHASE_CAP_TICKS) {
            this.finish("phase " + this.phase + " timed out");
            return;
        }
        // Never reconfigure the Jev endpoint while an answer is in flight: a reload re-points the
        // brains at a new client, and the in-flight answer is then correctly treated as stale.
        if (Boolean.TRUE.equals(state.get("thinking"))) {
            return;
        }
        switch (this.phase) {
            case 0 -> this.phaseNothingToContinue();
            case 1 -> this.phaseShadow();
            case 2 -> this.phaseActiveContinue();
            case 3 -> this.phaseActiveEscalate();
            case 4 -> this.phaseCommandBypass();
            case 5 -> this.phaseResume();
            case 6 -> this.phaseRoutingOffControl();
            case 7 -> this.phaseZeroYieldBackoff();
            default -> this.finish(null);
        }
    }

    /**
     * Phase 0: an idle bot with nothing queued and nothing running must plan without being asked
     * about.
     *
     * <p>This is the guard, and it is the phase that matters most: if the adviser were consulted here
     * and answered CONTINUE, the bot would have nothing to continue and would stand still forever.
     */
    private void phaseNothingToContinue() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.setRouting("active", "CONTINUE", 0.95);
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 0: routing=active, empty queue, nothing running (before={})",
                    this.before);
            return;
        }
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        LOG.info("ROUTINGTEST guard      : routingAsked={} modelTurns={} queued={} longAction={} "
                + "busy={} (an idle bot with nothing to continue must plan without asking)",
                now.routingCalls() - this.before.routingCalls(),
                now.llmCalls() - this.before.llmCalls(), this.queueSize(), this.longActionRunning(),
                this.busy());
        if (now.routingCalls() != this.before.routingCalls()) {
            this.fail("the adviser was asked although there was nothing to continue");
        }
        if (now.llmCalls() <= this.before.llmCalls()) {
            this.fail("an idle bot with no work produced no planning turn at all");
        }
        if (this.counter("IDLE", "asked") != 0) {
            this.fail("IDLE routing counters show an ask, but nothing was queued");
        }
        this.startWork();
        this.nextPhase("routing=shadow with work in flight");
    }

    /** Phase 1: shadow mode asks and logs, and must still let the planning turn happen. */
    private void phaseShadow() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.setRouting("shadow", "CONTINUE", 0.95);
            this.keepWorking();
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 1: routing=shadow, work in flight (before={})", this.before);
            return;
        }
        this.keepWorking();
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        int asked = now.routingCalls() - this.before.routingCalls();
        int turns = now.llmCalls() - this.before.llmCalls();
        LOG.info("ROUTINGTEST shadow     : routingAsked={} modelTurns={} continued={} escalated={} "
                + "(shadow must observe and still escalate)", asked, turns,
                this.counter("IDLE", "continued"), this.counter("IDLE", "escalated"));
        if (asked < 1) {
            this.fail("shadow mode did not ask the adviser about work in flight");
        }
        if (turns < asked) {
            this.fail("shadow mode suppressed a planning turn it was only supposed to observe ("
                    + asked + " asked, " + turns + " model turns)");
        }
        if (this.counter("IDLE", "continued") != 0) {
            this.fail("shadow mode applied a CONTINUE");
        }
        String state = this.jev.lastState(ROUTING);
        LOG.info("ROUTINGTEST state      : [{} chars] {}", state == null ? -1 : state.length(), state);
        for (String required : new String[] {"bot=", "dimension=", "current_action=", "queue_size=",
                "long_action_running=true", "seconds_since_last_completed_turn="}) {
            if (state == null || !state.contains(required)) {
                this.fail("the routing state is missing " + required);
            }
        }
        if (state == null || state.length() > 600) {
            this.fail("the routing state is not the compact string it is supposed to be ("
                    + (state == null ? -1 : state.length()) + " chars)");
        }
        this.nextPhase("routing=active, CONTINUE 0.95, work in flight");
    }

    /**
     * Phase 2: the point of the layer - a confident CONTINUE skips the planning turn while the bot
     * keeps doing what it was already doing.
     */
    private void phaseActiveContinue() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.setRouting("active", "CONTINUE", 0.95);
            this.keepWorking();
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 2: routing=active, stub answers CONTINUE 0.95 (before={})",
                    this.before);
            return;
        }
        this.keepWorking();
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        int asked = now.routingCalls() - this.before.routingCalls();
        int turns = now.llmCalls() - this.before.llmCalls();
        LOG.info("ROUTINGTEST continue   : routingAsked={} modelRequests={} continued={} mining={} "
                + "blocksBroken={} busy={} (not one planning request in the window)",
                asked, turns, this.counter("IDLE", "continued"),
                this.brain() != null && this.brain().isMining(), now.mineBroken(), this.busy());
        if (asked != 1) {
            this.fail("one physical mining job should need exactly one Jev decision, not "
                    + asked);
        }
        // The request count is the measure, not a completed-turn count: the scripted model never
        // emits a tool call, so turnsCompleted would stay at zero in every phase and prove nothing.
        if (turns != 0) {
            this.fail("a confident CONTINUE still cost " + turns + " planning request(s)");
        }
        if (this.counter("IDLE", "continued") <= 0) {
            this.fail("the routing counters do not show an applied CONTINUE");
        }
        Object leaseHits = brainState("statsLeaseContinuations");
        if (!(leaseHits instanceof Integer hits) || hits < 1) {
            this.fail("the CONTINUE lease never handled a later idle decision locally");
        }
        AgentBrain brain = this.brain();
        if (brain == null || !brain.isMining()) {
            this.fail("the bot lost the job it was told to carry on with");
        }
        if (now.mineBroken() <= 0) {
            this.fail("the bot stopped making progress on the job it was told to carry on with ("
                    + now.mineBroken() + " blocks broken in the window)");
        }
        this.nextPhase("routing=active, stub answers ESCALATE_LLM");
    }

    /** Phase 3: ESCALATE_LLM must reach the planning model. */
    private void phaseActiveEscalate() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.setRouting("active", "ESCALATE_LLM", 0.95);
            this.keepWorking();
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 3: routing=active, stub answers ESCALATE_LLM 0.95 (before={})",
                    this.before);
            return;
        }
        this.keepWorking();
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        int asked = now.routingCalls() - this.before.routingCalls();
        int turns = now.llmCalls() - this.before.llmCalls();
        LOG.info("ROUTINGTEST escalate   : routingAsked={} modelTurns={} escalated={}", asked, turns,
                this.counter("IDLE", "escalated"));
        if (asked < 1) {
            this.fail("ESCALATE_LLM was never asked");
        }
        if (turns < asked) {
            this.fail("ESCALATE_LLM did not reach the planning model (" + asked + " asked, " + turns
                    + " model turns)");
        }
        // The action report a CONTINUE leaves behind must reach the bot's next observation, not just
        // the log: it is what tells the planning model, when it is finally asked, that the gap was a
        // deliberate "keep going" rather than the bot having gone quiet.
        int reports = this.model.maxOccurrences("Jev routing said CONTINUE");
        LOG.info("ROUTINGTEST report     : planningModelSawContinueReport={} (the CONTINUE action "
                + "report must be in the next observation)", reports);
        if (reports < 1) {
            this.fail("the CONTINUE action report never reached the planning model's observation");
        }
        this.nextPhase("/mcagent think must never be second-guessed");
    }

    /**
     * Phase 4: an operator's explicit request for a decision goes straight to the planning model.
     *
     * <p>The pause first, so no ordinary idle decision can be in flight or due while the bypass is
     * measured: the point of the assertion is that this one decision never touched the adviser.
     */
    private void phaseCommandBypass() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            AgentBrain brain = this.brain();
            brain.setPaused(true, "routing test");
            brain.setPaused(false, "routing test");
            this.before = this.snapshot();
            // Same tick, so the resume marker cannot start a decision of its own first.
            brain.requestDecisionNow();
            LOG.info("ROUTINGTEST phase 4: requestDecisionNow with routing=active (before={})",
                    this.before);
            return;
        }
        if (this.ticks - this.phaseTick < 30) {
            return;
        }
        Snapshot now = this.snapshot();
        LOG.info("ROUTINGTEST command    : routingAsked={} modelTurns={} COMMAND.asked={} "
                + "pendingTrigger={} (an explicit instruction is never routed)",
                now.routingCalls() - this.before.routingCalls(),
                now.llmCalls() - this.before.llmCalls(), this.counter("COMMAND", "asked"),
                this.brain() == null ? "?" : this.brain().debugState().get("pendingTrigger"));
        if (now.routingCalls() != this.before.routingCalls()) {
            this.fail("the adviser was asked about a COMMAND decision");
        }
        if (now.llmCalls() <= this.before.llmCalls()) {
            this.fail("a COMMAND decision did not reach the planning model");
        }
        if (this.counter("COMMAND", "asked") != 0) {
            this.fail("COMMAND routing counters show an ask");
        }
        this.nextPhase("an unpause with nothing to carry on with");
    }

    /**
     * Phase 5: a resume is routed too - and with nothing queued it must plan without asking.
     *
     * <p>Pausing abandons the job and the queue, so a resumed bot genuinely has nothing to continue:
     * the guard fires, and the honest answer is a fresh plan rather than a question whose only safe
     * answer would be ESCALATE_LLM anyway.
     */
    private void phaseResume() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            AgentBrain brain = this.brain();
            brain.setPaused(true, "routing test");
            brain.setPaused(false, "routing test");
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 5: paused and resumed with no work (before={})", this.before);
            return;
        }
        if (this.ticks - this.phaseTick < 60) {
            return;
        }
        Snapshot now = this.snapshot();
        LOG.info("ROUTINGTEST resume     : routingAsked={} modelTurns={} RESUME.asked={} queued={} "
                + "longAction={} busy={}", now.routingCalls() - this.before.routingCalls(),
                now.llmCalls() - this.before.llmCalls(), this.counter("RESUME", "asked"),
                this.queueSize(), this.longActionRunning(), this.busy());
        if (now.routingCalls() != this.before.routingCalls()) {
            this.fail("the adviser was asked about a resume with nothing to continue");
        }
        if (now.llmCalls() <= this.before.llmCalls()) {
            this.fail("a resumed bot with no work produced no planning turn");
        }
        this.nextPhase("routing=off positive control");
    }

    /**
     * Phase 7: the production hot loop. A completed mining trip brought back no requested ore, so
     * the runtime permits one fresh plan immediately but must not buy another one seconds later.
     */
    private void phaseZeroYieldBackoff() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            AgentBrain brain = this.brain();
            brain.setPaused(true, "routing zero-yield test");
            this.setRouting("active", "CONTINUE", 0.95);
            brain.setStandingGoal("去挖矿，铁矿石优先，带回 8 个");
            brain.recordZeroYieldMiningGoalForTest();
            this.before = this.snapshot();
            brain.setPaused(false, "routing zero-yield test");
            LOG.info("ROUTINGTEST phase 7: zero-yield goal, one immediate re-plan then backoff "
                    + "(before={})", this.before);
            return;
        }
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        int asked = now.routingCalls() - this.before.routingCalls();
        int turns = now.llmCalls() - this.before.llmCalls();
        Object avoided = brainState("statsBackoffAvoided");
        LOG.info("ROUTINGTEST zero-yield : routingAsked={} modelRequests={} backoffAvoided={} "
                + "(one immediate re-plan, no several-second loop)", asked, turns, avoided);
        if (asked != 0) {
            this.fail("zero-yield idle backoff unnecessarily consulted Jev");
        }
        if (turns != 1) {
            this.fail("zero-yield goal used " + turns
                    + " planning requests in one settle window instead of exactly one");
        }
        if (!(avoided instanceof Integer count) || count < 1) {
            this.fail("zero-yield backoff did not record an avoided repeated planning turn");
        }
        this.nextPhase("done");
    }

    /**
     * Phase 6: the positive control - with routing off, the very same situation spends planning turns.
     *
     * <p>Without it, phase 2's "not one planning request" could be explained by anything at all: a
     * bot that cannot reach the model, a job that had already ended, a harness that stopped
     * measuring. Here the work is running exactly as it was there and the only difference is that the
     * adviser is not consulted, so the turn has to happen. A fix is not verified until the failure it
     * prevents has been seen.
     */
    private void phaseRoutingOffControl() {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.setRouting("off", "CONTINUE", 0.95);
            this.keepWorking();
            this.before = this.snapshot();
            LOG.info("ROUTINGTEST phase 6: routing=off with work in flight (control; before={})",
                    this.before);
            return;
        }
        this.keepWorking();
        if (this.ticks - this.phaseTick < SETTLE_TICKS) {
            return;
        }
        Snapshot now = this.snapshot();
        int asked = now.routingCalls() - this.before.routingCalls();
        int turns = now.llmCalls() - this.before.llmCalls();
        LOG.info("ROUTINGTEST control    : routingAsked={} modelRequests={} longAction={} mining={} "
                + "(routing=off: the same situation must spend planning turns)",
                asked, turns, this.longActionRunning(),
                this.brain() != null && this.brain().isMining());
        if (asked != 0) {
            this.fail("routing=off still consulted the adviser");
        }
        if (turns < 1) {
            this.fail("routing=off suppressed a planning turn, so the phase-2 result proves nothing");
        }
        this.nextPhase("zero-yield standing goal uses one re-plan and then backs off");
    }

    private void begin() {
        try {
            // A scripted planning model that only counts requests, and a stub System One endpoint
            // whose answer the test chooses. Nothing else stands between a trigger and the model.
            this.model = new ScriptedLlmServer(body -> ScriptedLlmServer.silent());
            this.savedLlmSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            this.jev = new StubSystemOne();
            this.setRouting("active", "CONTINUE", 0.95);
        } catch (IOException e) {
            this.finish("could not start stubs: " + e.getMessage());
            return;
        }

        this.level = this.server.overworld();
        this.prepareScene();
        Vec3 at = Vec3.atBottomCenterOf(this.base.offset(1, 0, 0));
        BotManager.BotHandle bot = Agent.botManager().spawn(BOT, this.level, at, true);
        if (bot == null) {
            this.finish("could not spawn the bot");
            return;
        }
        if (!Agent.attachBrain(bot.player())) {
            this.finish("could not attach a brain");
            return;
        }
        AgentBrain attached = this.brain();
        if (attached != null) {
            // A previous harness run may have persisted phase 7's standing goal by bot name.
            attached.setStandingGoal(null);
        }
        LOG.info("ROUTINGTEST: {} spawned at {} beside a dirt face and a stone floor at {}", BOT,
                bot.player().blockPosition(), this.base);
        this.phaseTick = this.ticks;
    }

    /**
     * Build a scene that can supply "work in flight" for far longer than the test runs.
     *
     * <p>A dirt cube (27 blocks, ~50 ticks each by hand) is the working face and the stone floor
     * underneath is the backup, so no phase can be left without something to continue because the
     * previous one finished the job. Nothing here has a block entity, so the player-structure guard
     * has no fixture to protect and the mining is genuinely allowed.
     */
    private void prepareScene() {
        this.base = this.level.getSharedSpawnPos().offset(28, 0, 28);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    this.level.setBlockAndUpdate(this.base.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
                this.level.setBlockAndUpdate(this.base.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
        for (int dx = 3; dx <= 5; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = 0; dz <= 2; dz++) {
                    this.level.setBlockAndUpdate(this.base.offset(dx, dy, dz),
                            Blocks.DIRT.defaultBlockState());
                }
            }
        }
        this.workTargets.clear();
        this.workTargets.add(this.base.offset(3, 0, 0));
        this.workTargets.add(this.base.offset(1, -1, 1));
        this.workTargets.add(this.base.offset(-3, -1, 3));
        this.workTargets.add(this.base.offset(3, -1, -3));
        this.nextWorkTarget = 0;
    }

    /** Start the first mining job; a failure here fails the test, because no phase can be measured. */
    private void startWork() {
        AgentBrain brain = this.brain();
        if (brain == null) {
            return;
        }
        String result = brain.mineAsTool(this.workTargets.get(0), WORK_RADIUS);
        LOG.info("ROUTINGTEST work       : mineAsTool({}, {}) -> {}",
                this.workTargets.get(0).toShortString(), WORK_RADIUS, result);
        this.nextWorkTarget = 1;
        if (result.startsWith("failed:")) {
            this.fail("could not start the mining job the routing guard needs: " + result);
        }
    }

    /** Keep a real job running, so the guard has something to allow while a phase is measured. */
    private void keepWorking() {
        AgentBrain brain = this.brain();
        if (brain == null || brain.isMining()) {
            return;
        }
        while (this.nextWorkTarget < this.workTargets.size()) {
            BlockPos target = this.workTargets.get(this.nextWorkTarget++);
            String result = brain.mineAsTool(target, WORK_RADIUS);
            LOG.info("ROUTINGTEST work       : re-armed with mineAsTool({}, {}) -> {}",
                    target.toShortString(), WORK_RADIUS, result);
            if (!result.startsWith("failed:")) {
                return;
            }
        }
        this.fail("the bot ran out of work to continue, so the routing guard can no longer be tested");
    }

    private Snapshot snapshot() {
        AgentBrain brain = this.brain();
        Map<String, Object> state = brain == null ? Map.of() : brain.debugState();
        return new Snapshot(this.jev == null ? -1 : this.jev.calls(ROUTING),
                this.model == null ? -1 : this.model.requestCount(),
                brain == null ? -1 : brain.turnsCompleted(),
                state.get("mineBroken") instanceof Integer broken ? broken : -1);
    }

    private int queueSize() {
        AgentBrain brain = this.brain();
        Map<String, Object> state = brain == null ? Map.of() : brain.debugState();
        return state.get("queueSize") instanceof Integer size ? size : -1;
    }

    private boolean longActionRunning() {
        AgentBrain brain = this.brain();
        Map<String, Object> state = brain == null ? Map.of() : brain.debugState();
        return Boolean.TRUE.equals(state.get("longActionRunning"));
    }

    private String busy() {
        AgentBrain brain = this.brain();
        return brain == null ? "?" : String.valueOf(brain.debugState().get("busy"));
    }

    private Object brainState(String key) {
        AgentBrain brain = this.brain();
        return brain == null ? null : brain.debugState().get(key);
    }

    /** One routing tally out of {@code debugState()}, or -1 when it is not there. */
    private int counter(String trigger, String name) {
        Object one = this.lastCounters.get(trigger);
        if (!(one instanceof Map<?, ?> counts)) {
            return -1;
        }
        Object value = counts.get(name);
        return value instanceof Integer number ? number : -1;
    }

    private AgentBrain brain() {
        BotManager.BotHandle handle = Agent.botManager() == null ? null
                : Agent.botManager().get(BOT);
        return handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    /**
     * Point the runtime-only Jev config at the stub, keeping the original file to put back.
     *
     * <p>{@code speechGate=off} on purpose: this test posts no chat, and the speech gate must not be
     * able to explain a difference in the model-call count. {@code shadowMode} stays true because the
     * mining recovery is a physical decision and is not what is being measured here.
     */
    private void setRouting(String routing, String choice, double confidence) {
        try {
            this.configPath = Path.of("config", "mcagent-jev.properties");
            if (this.originalConfig == null && Files.isRegularFile(this.configPath)) {
                this.originalConfig = Files.readString(this.configPath, StandardCharsets.UTF_8);
            }
            String body = "# Written by JevRoutingSmokeTest; restored when the test ends.\n"
                    + "enabled=true\n"
                    + "protocol=systemone\n"
                    + "endpoint=" + this.jev.endpoint() + "\n"
                    + "apiKey=stub\n"
                    + "model=stub-model\n"
                    + "timeoutMillis=2000\n"
                    + "maxTokens=800\n"
                    + "shadowMode=true\n"
                    + "speechGate=off\n"
                    + "routing=" + routing + "\n";
            Files.createDirectories(this.configPath.getParent());
            Files.writeString(this.configPath, body, StandardCharsets.UTF_8);
            this.jev.answer(ROUTING, choice, confidence);
            Config.apply();
        } catch (IOException e) {
            this.fail("could not write the Jev config: " + e.getMessage());
        }
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        this.phaseStarted = false;
        LOG.info("ROUTINGTEST phase {} : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures.add(why);
        LOG.warn("ROUTINGTEST problem  : {}", why);
    }

    private void finish(String abort) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        boolean pass = abort == null && this.failures.isEmpty();
        LOG.info("ROUTINGTEST counters   : {}", this.lastCounters);
        LOG.info("ROUTINGTEST stub calls : {}", this.jev == null ? "none" : this.jev.describe());
        for (String failure : this.failures) {
            LOG.info("ROUTINGTEST failure    : {}", failure);
        }
        LOG.info("ROUTINGTEST result     : {}", abort == null ? "completed" : abort);
        LOG.info("ROUTINGTEST VERDICT    : {}", pass ? "PASS" : "FAIL");

        if (Agent.botManager() != null) {
            AgentBrain brain = this.brain();
            if (brain != null) {
                brain.setStandingGoal(null);
            }
            Agent.botManager().remove(BOT);
        }
        try {
            if (this.originalConfig != null) {
                Files.writeString(this.configPath, this.originalConfig, StandardCharsets.UTF_8);
            } else if (this.configPath != null && Files.isRegularFile(this.configPath)) {
                Files.delete(this.configPath);
            }
            Config.apply();
        } catch (IOException e) {
            LOG.warn("ROUTINGTEST could not restore the Jev config: {}", e.toString());
        }
        ScriptedLlmServer.restoreSettings(this.savedLlmSettings);
        AgentBrain.shutdown();
        if (this.model != null) {
            try {
                this.model.close();
            } catch (Throwable ignored) {
                // Best effort in a gated test process.
            }
        }
        if (this.jev != null) {
            this.jev.close();
        }
        this.server.halt(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawCast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * A stub System One endpoint that answers <em>by question id</em>.
     *
     * <p>One endpoint serves the speech gate, the mining recovery and the routing layer, and a stub
     * that answered whatever arrived first would silently mis-answer as soon as a second question
     * type appeared. Calls are therefore counted per question, and the state each question was asked
     * with is kept so the test can assert what the adviser was actually shown.
     */
    private static final class StubSystemOne {

        private final HttpServer http;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> perQuestion = new ConcurrentHashMap<>();
        private final Map<String, String> states = new ConcurrentHashMap<>();
        private final Map<String, String[]> answers = new ConcurrentHashMap<>();

        StubSystemOne() throws IOException {
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/systemone", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                this.calls.incrementAndGet();
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                String state = request.has("state") ? request.get("state").getAsString() : "";
                JsonObject out = new JsonObject();
                for (String id : request.getAsJsonObject("questions").keySet()) {
                    this.perQuestion.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
                    this.states.put(id, state);
                    String[] answer = this.answers.getOrDefault(id,
                            new String[] {"ESCALATE_LLM", "0.50"});
                    double confidence = Double.parseDouble(answer[1]);
                    JsonObject one = new JsonObject();
                    one.addProperty("choice", answer[0]);
                    one.addProperty("confidence", confidence);
                    JsonObject probabilities = new JsonObject();
                    probabilities.addProperty(answer[0], confidence);
                    probabilities.addProperty(
                            "CONTINUE".equals(answer[0]) ? "ESCALATE_LLM" : "CONTINUE",
                            Math.max(0.0D, 1.0D - confidence));
                    one.add("probabilities", probabilities);
                    out.add(id, one);
                    LOG.info("ROUTINGTEST stub       : question={} answer={} confidence={} state={}",
                            id, answer[0], answer[1], state);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("model", "stub-model");
                payload.add("answers", out);
                byte[] response = payload.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream stream = exchange.getResponseBody()) {
                    stream.write(response);
                }
            });
            this.http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcagent-stub-routing");
                thread.setDaemon(true);
                return thread;
            }));
            this.http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort() + "/systemone";
        }

        /** What this stub answers for one question id, from now on. */
        void answer(String questionId, String choice, double confidence) {
            this.answers.put(questionId, new String[] {choice, String.valueOf(confidence)});
        }

        int calls(String questionId) {
            AtomicInteger count = this.perQuestion.get(questionId);
            return count == null ? 0 : count.get();
        }

        String lastState(String questionId) {
            return this.states.get(questionId);
        }

        String describe() {
            return "total=" + this.calls.get() + " perQuestion=" + this.perQuestion;
        }

        void close() {
            this.http.stop(0);
        }
    }
}
