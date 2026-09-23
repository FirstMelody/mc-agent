package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.Config;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import com.sun.net.httpserver.HttpServer;
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
 * End-to-end proof of the mining-recovery decision point, including every failure path.
 *
 * <p>The decision itself (JEV choosing RETRY / SKIP / BACKTRACK / GATHER_PERCEPTION / ESCALATE_LLM)
 * was calibrated against eight realistic states, but nothing exercised the *code* that acts on it:
 * the whitelist, the one-retry-per-target cap, the confidence floor, the staleness guard and the
 * fail-open paths. This harness injects a real, deterministic mining failure and drives each of them.
 *
 * <p>The failure is injected rather than hoped for: most phases report
 * {@code NO_REACHABLE_STAND}, for which an access retry is legitimately among the offered choices.
 * Protected/rejected breaks deliberately do not offer retry; the unknown-choice phase separately
 * proves that System One cannot smuggle an unoffered action through the typed endpoint.
 *
 * <p>Enabled with {@code MCAGENT_JEV_MINE_TEST=true}.
 */
public final class JevMiningRecoverySmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/jevminetest");
    private static final String BOT = "MineJevBot";

    private final MinecraftServer server;
    private StubSystemOne jev;
    private ScriptedLlmServer model;
    private String[] savedLlmSettings;
    private Path configPath;
    private String originalConfig;
    private BlockPos plot;
    private BlockPos target;
    private ServerLevel level;
    private boolean started;
    private boolean finished;
    private int phase;
    private int phaseTick;
    private int ticks;
    private boolean staleWorkQueued;
    private volatile long modelDelayMillis;
    private boolean raceFired;
    private int raceFiredAt;
    private int raceSkippedBefore;
    private int staleQueuedAt;
    private int jevCallsBeforePhase;
    private int failures;

    private JevMiningRecoverySmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_JEV_MINE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("JEVMINETEST: armed");
        return new JevMiningRecoverySmokeTest(server);
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
        if (this.ticks - this.phaseTick > 600) {
            this.finish("phase " + this.phase + " timed out");
            return;
        }
        switch (this.phase) {
            case 0 -> this.startFailurePhase();
            case 1 -> this.checkShadowDidNotAct();
            case 2 -> this.startActiveRetry();
            case 3 -> this.checkRetryHappened();
            case 4 -> this.startSecondFailure();
            case 5 -> this.checkRetryCap();
            case 6 -> this.checkRetryCapOffered();
            case 7 -> this.startLowConfidence();
            case 8 -> this.checkLowConfidenceAbstained();
            case 9 -> this.startUnknownChoice();
            case 10 -> this.checkUnknownChoiceEscalated();
            case 11 -> this.startStaleAdvice();
            case 12 -> this.checkStaleAdviceDiscarded();
            case 13 -> this.startRaceWithPlanningTurn();
            case 14 -> this.checkRecoveryBeatTheTurn();
            case 15 -> this.startRepeatedFailure();
            case 16 -> this.secondRepeatedFailure();
            case 17 -> this.thirdRepeatedFailure();
            case 18 -> this.checkRepeatedFailureSkipped();
            default -> this.finish(null);
        }
    }

    /** Phase 0: shadow mode. Jev answers, the bot must not act. */
    private void startFailurePhase() {
        this.script("RETRY_DIFFERENT_ACCESS", 0.90D);
        this.setMode(true, "active");
        this.beginCase("shadow: a failure, Jev consulted, nothing physical");
    }

    private void checkShadowDidNotAct() {
        this.expectCase(false, "shadow", "shadow must not act");
    }

    /** Phase 2: active mode, a confident RETRY - the target must actually be mined. */
    private void startActiveRetry() {
        this.script("RETRY_DIFFERENT_ACCESS", 0.90D);
        this.setMode(false, "active");
        this.beginCase("active: RETRY must mine the target");
    }

    private void checkRetryHappened() {
        this.expectCase(true, "active RETRY", "a confident RETRY is applied");
    }

    /**
     * Phase 4: the same target fails again while its retry is still running.
     *
     * <p>The cap is about what Jev is *offered*: once a retry has been spent on an exact target, RETRY
     * must no longer be among the candidates, or the loop can repeat forever. Asserting the physical
     * outcome instead would be wrong - a retry that succeeds removes the block, and a block that is
     * gone is deliberately treated as a new incident (see finishMineJob).
     */
    private void startSecondFailure() {
        this.script("RETRY_DIFFERENT_ACCESS", 0.90D);
        this.target = this.target.offset(1, 0, 0);
        this.level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());
        // Wall it in with bedrock first, so the retry that is about to be applied cannot succeed. A
        // retry that DOES succeed removes the block, and a coordinate whose block is gone is
        // deliberately treated as a new incident - the guard would be cleared and the next failure
        // would legitimately be offered a retry again. This is the scenario the cap exists for: the
        // target survives its retry, so the guard has to survive too.
        this.wallIn();
        this.jevCallsBeforePhase = this.jev.calls();
        this.failTarget();
        this.nextPhase("a retry was spent on a target that survived it");
    }

    /** Surround the target with unbreakable blocks, so no retry can ever reach or break it. */
    private void wallIn() {
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            this.level.setBlockAndUpdate(this.target.relative(direction),
                    Blocks.BEDROCK.defaultBlockState());
        }
        this.level.setBlockAndUpdate(this.target.above(), Blocks.BEDROCK.defaultBlockState());
    }

    /** The first retry must have been spent (and failed) before the cap can be judged. */
    private void checkRetryCap() {
        if (this.jev.calls() <= this.jevCallsBeforePhase || this.ticks - this.phaseTick < 80) {
            return;
        }
        boolean survived = !this.level.getBlockState(this.target).isAir();
        LOG.info("JEVMINETEST cap setup   : targetSurvivedItsRetry={} (so the guard must still hold)",
                survived);
        if (!survived) {
            this.fail("the walled-in target was mined, so the cap scenario did not run");
        }
        this.jevCallsBeforePhase = this.jev.calls();
        Object skipped = this.brain().debugState().get("jevSkippedMiningTargets");
        this.raceSkippedBefore = skipped instanceof Integer count ? count : 0;
        this.failTarget();
        this.nextPhase("the third failure of that target skips locally");
    }

    private void checkRetryCapOffered() {
        if (this.ticks - this.phaseTick < 60) {
            return;
        }
        Object after = this.brain().debugState().get("jevSkippedMiningTargets");
        boolean skipped = after instanceof Integer count && count > this.raceSkippedBefore;
        int extraCalls = this.jev.calls() - this.jevCallsBeforePhase;
        LOG.info("JEVMINETEST retry cap   : extraJevCalls={} locallySkipped={}",
                extraCalls, skipped);
        if (extraCalls != 0 || !skipped) {
            this.fail("third failure bought Jev instead of skipping a target that spent its retry");
        }
        this.nextPhase("next case");
    }

    /** Phase 6: below the floor, the bot must not act. */
    private void startLowConfidence() {
        this.script("RETRY_DIFFERENT_ACCESS", 0.30D);
        this.beginCase("low confidence must not move the bot");
    }

    private void checkLowConfidenceAbstained() {
        this.expectCase(false, "low conf", "below the floor: abstain");
    }

    /** Phase 8: the model names an option it was not given. */
    private void startUnknownChoice() {
        this.script("DO_SOMETHING_ELSE", 0.99D);
        this.beginCase("an unoffered choice is a failure, not an action");
    }

    private void checkUnknownChoiceEscalated() {
        this.expectCase(false, "unknown", "not offered => rejected");
    }

    /** Phase 10: the answer comes back after the bot has been given other work. */
    private void startStaleAdvice() {
        this.script("RETRY_DIFFERENT_ACCESS", 0.95D);
        this.jev.delayMillis = 1500L;
        this.beginCase("stale advice must not interrupt newer work");
    }

    private void checkStaleAdviceDiscarded() {
        // Queue the newer work while Jev is still thinking (the stub sleeps 1.5 s = 30 ticks), so the
        // answer genuinely arrives late. The first version gated this on the answer having arrived,
        // which queued the work *after* it was applied and therefore proved nothing.
        if (!this.staleWorkQueued && this.ticks - this.phaseTick > 10) {
            // Newer work, queued while Jev is still thinking: the tunnel macro must survive and the
            // late RETRY must be dropped rather than stacked on top of it.
            this.staleWorkQueued = true;
            this.staleQueuedAt = this.ticks;
            this.brain().digTunnel("north", "level", 3, "diamond_pickaxe", true);
            return;
        }
        if (!this.staleWorkQueued || this.ticks - this.staleQueuedAt < 140) {
            return;
        }
        if (this.jev.calls() <= this.jevCallsBeforePhase) {
            this.fail("the delayed answer never arrived, so the stale case proved nothing");
            this.nextPhase("done");
            return;
        }
        boolean broken = this.level.getBlockState(this.target).isAir();
        LOG.info("JEVMINETEST stale       : targetMinedByStaleAdvice={} (newer work must win)", broken);
        if (broken) {
            this.fail("stale advice was applied over newer work");
        }
        this.jev.delayMillis = 0L;
        this.nextPhase("a recovery must still land while a planning turn is running");
    }

    private void startRepeatedFailure() {
        this.script("ESCALATE_LLM", 0.90D);
        this.target = this.target.offset(1, 0, 0);
        this.level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());
        this.jevCallsBeforePhase = this.jev.calls();
        this.failTarget();
        this.nextPhase("first failure was evaluated");
    }

    private void secondRepeatedFailure() {
        if (this.jev.calls() <= this.jevCallsBeforePhase || this.ticks - this.phaseTick < 20) {
            return;
        }
        this.jevCallsBeforePhase = this.jev.calls();
        this.failTarget();
        this.nextPhase("second failure was evaluated");
    }

    private void thirdRepeatedFailure() {
        if (this.jev.calls() <= this.jevCallsBeforePhase || this.ticks - this.phaseTick < 20) {
            return;
        }
        this.jevCallsBeforePhase = this.jev.calls();
        Object before = this.brain().debugState().get("jevSkippedMiningTargets");
        this.raceSkippedBefore = before instanceof Integer count ? count : 0;
        this.failTarget();
        this.nextPhase("third failure must skip locally");
    }

    private void checkRepeatedFailureSkipped() {
        if (this.ticks - this.phaseTick < 20) {
            return;
        }
        Object after = this.brain().debugState().get("jevSkippedMiningTargets");
        boolean skipped = after instanceof Integer count && count > this.raceSkippedBefore;
        int extraCalls = this.jev.calls() - this.jevCallsBeforePhase;
        LOG.info("JEVMINETEST repeated   : extraJevCalls={} locallySkipped={}", extraCalls, skipped);
        if (extraCalls != 0 || !skipped) {
            this.fail("third failure bought another Jev call instead of skipping the target");
        }
        this.nextPhase("done");
    }

    /**
     * Phase 13: the real production race - a planning turn is in flight when the answer lands.
     *
     * <p>Production showed the recovery being discarded with {@code not_applied=newer_work
     * mineJob=false combat=false moving=false queue=0 thinking=true}: the bot starts a ten-to-fifteen
     * second planning turn the moment the job ends, so an answer that arrives a second later was
     * always overtaken. The harness could not see it because its scripted model answers instantly, so
     * this phase makes the model slow on purpose.
     */
    private void startRaceWithPlanningTurn() {
        this.script("SKIP_TARGET", 0.90D);
        this.modelDelayMillis = 2500L;
        // The answer must take long enough that a planning turn has time to start underneath it,
        // which is what happens in production: the stub answers instantly, so an un-delayed answer
        // always wins the race and the phase would pass whether or not the fix is present.
        this.jev.delayMillis = 2500L;
        this.target = this.target.offset(1, 0, 0);
        this.level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());
        this.jevCallsBeforePhase = this.jev.calls();
        // Let the bot start planning first, then report the failure underneath it.
        this.nextPhase("a recovery must still land while a planning turn is running");
    }

    private void checkRecoveryBeatTheTurn() {
        // Fire when the bot is about to plan: not thinking, cooldown nearly expired. That is the
        // production moment - the job has just ended and the next tick wants to start a turn - and it
        // is what makes the race deterministic. Firing at an arbitrary moment makes the phase a coin
        // flip that passes whether or not the fix is present.
        Object thinking = this.brain().debugState().get("thinking");
        Object cooldown = this.brain().debugState().get("cooldownTicks");
        boolean aboutToPlan = !Boolean.TRUE.equals(thinking)
                && cooldown instanceof Integer ticks && ticks <= 5;
        if (!this.raceFired && !aboutToPlan) {
            return;
        }
        if (!this.raceFired) {
            this.raceFired = true;
            this.raceFiredAt = this.ticks;
            // Count from here: SKIP_TARGET is a selector-state change, deliberately not a physical
            // applied=true action. The marker itself is the evidence that the recovery landed.
            Object before = this.brain().debugState().get("jevSkippedMiningTargets");
            this.raceSkippedBefore = before instanceof Integer count ? count : 0;
            this.failTarget();
            return;
        }
        if (this.ticks - this.raceFiredAt < 200) {
            return;
        }
        Object after = this.brain().debugState().get("jevSkippedMiningTargets");
        boolean marked = after instanceof Integer count && count > this.raceSkippedBefore;
        String repeat = this.brain().mineAsTool(this.target, 0);
        boolean rejected = repeat.contains("temporarily marked unreachable");
        LOG.info("JEVMINETEST race       : skipMarkedWhileThinking={} repeatRejected={} "
                + "(a turn in flight must not swallow it)", marked, rejected);
        if (!marked || !rejected) {
            this.fail("the recovery was discarded because a planning turn was in flight");
        }
        this.modelDelayMillis = 0L;
        this.jev.delayMillis = 0L;
        this.nextPhase("repeated failure should stop buying Jev evaluations");
    }

    private void script(String choice, double confidence) {
        this.jev.script = choice;
        this.jev.confidence = confidence;
    }

    /**
     * Start one case: put the target back, note the counters, and report the failure.
     *
     * <p>Every case re-places the block, so "did the bot act?" is answered by the world rather than
     * by a flag that may already have been reset.
     */
    private void beginCase(String what) {
        this.target = this.target.offset(1, 0, 0);
        this.level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());
        this.jevCallsBeforePhase = this.jev.calls();
        this.failTarget();
        this.nextPhase(what);
    }

    /** Wait for the case to play out, then judge it by whether the target block is gone. */
    private void expectCase(boolean expectMined, String label, String why) {
        if (this.jev.calls() <= this.jevCallsBeforePhase) {
            return;
        }
        if (this.ticks - this.phaseTick < 140) {
            return;
        }
        boolean mined = this.level.getBlockState(this.target).isAir();
        LOG.info("JEVMINETEST {}: targetMined={} ({})", String.format("%-11s", label), mined, why);
        if (mined != expectMined) {
            this.fail(why + " - expected targetMined=" + expectMined + " but saw " + mined);
        }
        this.nextPhase("next case");
    }

    /** One deterministic mining failure, exactly as the loop reports it. */
    private void failTarget() {
        this.brain().simulateMiningFailureForTest(this.target, 0,
                java.util.Map.of("NO_REACHABLE_STAND", 1), 0, 1);
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() - 60;
        int z = spawn.getZ() + 60;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = Math.max(spawn.getY() + 24, terrain + 16);
        this.plot = new BlockPos(x, ground, z);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= -1; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
                for (int dy = 0; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.target = this.plot.offset(2, 0, 0);
        this.level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());

        try {
            this.model = new ScriptedLlmServer(body -> {
                long delay = this.modelDelayMillis;
                if (delay > 0L) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ScriptedLlmServer.silent();
            });
            this.savedLlmSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            this.jev = new StubSystemOne();
            this.setMode(true, "active");
        } catch (IOException e) {
            this.finish("could not start stubs: " + e.getMessage());
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(BOT, this.level,
                Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            this.finish("could not spawn bot");
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        if (!Agent.attachBrain(handle.player())) {
            this.finish("could not attach brain");
        } else {
            // The race case intentionally needs an idle planning turn. A goal distinguishes it
            // from an unassigned bot, which now waits for an external event by default.
            this.brain().setStandingGoal("校验恢复流程");
        }
        this.phaseTick = this.ticks;
    }

    /** Write the Jev config the runtime reads, with the mining-recovery mode under test. */
    private void setMode(boolean shadow, String routing) {
        try {
            this.configPath = Path.of("config", "mcagent-jev.properties");
            if (this.originalConfig == null && Files.isRegularFile(this.configPath)) {
                this.originalConfig = Files.readString(this.configPath, StandardCharsets.UTF_8);
            }
            String body = "# Written by JevMiningRecoverySmokeTest; restored when the test ends.\n"
                    + "enabled=true\n"
                    + "protocol=systemone\n"
                    + "endpoint=" + this.jev.endpoint() + "\n"
                    + "apiKey=stub\n"
                    + "model=stub-model\n"
                    + "timeoutMillis=5000\n"
                    + "shadowMode=" + shadow + "\n"
                    + "speechGate=off\n"
                    + "routing=off\n";
            Files.createDirectories(this.configPath.getParent());
            Files.writeString(this.configPath, body, StandardCharsets.UTF_8);
            Config.apply();
        } catch (IOException e) {
            this.fail("could not write the Jev config: " + e.getMessage());
        }
    }

    private AgentBrain brain() {
        BotManager.BotHandle handle = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        return handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        LOG.info("JEVMINETEST phase {} : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures++;
        LOG.warn("JEVMINETEST problem     : {}", why);
    }

    private void finish(String abort) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        boolean pass = abort == null && this.failures == 0;
        LOG.info("JEVMINETEST failures    : {}", this.failures);
        LOG.info("JEVMINETEST result      : {}", abort == null ? "completed" : abort);
        LOG.info("JEVMINETEST VERDICT     : {}", pass ? "PASS" : "FAIL");

        if (Agent.botManager() != null) {
            AgentBrain brain = this.brain();
            if (brain != null) {
                brain.setStandingGoal(null);
            }
            Agent.botManager().remove(BOT);
            Agent.botManager().wipeSavedData(BOT);
        }
        if (this.plot != null) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        try {
            if (this.originalConfig != null) {
                Files.writeString(this.configPath, this.originalConfig, StandardCharsets.UTF_8);
            } else if (this.configPath != null && Files.isRegularFile(this.configPath)) {
                Files.delete(this.configPath);
            }
            Config.apply();
        } catch (IOException e) {
            LOG.warn("JEVMINETEST could not restore the Jev config: {}", e.toString());
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

    /** A stub System One endpoint whose answer the test scripts. */
    private static final class StubSystemOne {

        private final HttpServer http;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> bodies = new ArrayList<>();
        private volatile String script = "RETRY_DIFFERENT_ACCESS";
        private volatile double confidence = 0.9D;
        /** Delay before answering, so the test can make the advice stale while it is in flight. */
        private volatile long delayMillis;

        StubSystemOne() throws IOException {
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/systemone", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                synchronized (this.bodies) {
                    this.bodies.add(body);
                }
                this.calls.incrementAndGet();
                if (this.delayMillis > 0L) {
                    try {
                        Thread.sleep(this.delayMillis);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                String payload = "{\"answers\":{\"mining_recovery\":{\"choice\":\""
                        + this.script + "\",\"confidence\":" + this.confidence
                        + ",\"probabilities\":{}}}}";
                byte[] response = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            this.http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcagent-stub-jev-mine");
                thread.setDaemon(true);
                return thread;
            }));
            this.http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort() + "/systemone";
        }

        int calls() {
            return this.calls.get();
        }

        /**
         * How many times a recovery was applied, judged from the server log the runtime writes.
         *
         * <p>Read back from the log rather than from brain state: "applied" is exactly the line the
         * production failure was missing, so the assertion uses the same evidence an operator has.
         */
        int appliedLines() {
            try {
                java.nio.file.Path log = java.nio.file.Path.of("logs", "latest.log");
                if (!java.nio.file.Files.isRegularFile(log)) {
                    return 0;
                }
                int count = 0;
                for (String line : java.nio.file.Files.readAllLines(log)) {
                    if (line.contains("event=MINING_RECOVERY applied=true")) {
                        count++;
                    }
                }
                return count;
            } catch (Throwable t) {
                return 0;
            }
        }

        /** The most recent request body, for diagnostics. */
        String lastBody() {
            synchronized (this.bodies) {
                return this.bodies.isEmpty() ? "" : this.bodies.get(this.bodies.size() - 1);
            }
        }

        /**
         * The candidate choices offered in the most recent request.
         *
         * <p>Read from {@code questions.<id>.criteria}, because the instruction text itself names
         * every option - a substring search over the whole body would always match.
         */
        java.util.Set<String> lastOfferedCandidates() {
            try {
                com.google.gson.JsonObject root =
                        com.google.gson.JsonParser.parseString(lastBody()).getAsJsonObject();
                com.google.gson.JsonObject questions = root.getAsJsonObject("questions");
                for (String id : questions.keySet()) {
                    com.google.gson.JsonObject question = questions.getAsJsonObject(id);
                    if (question.has("criteria")) {
                        return question.getAsJsonObject("criteria").keySet();
                    }
                }
            } catch (Throwable t) {
                LOG.warn("JEVMINETEST could not read the offered candidates: {}", t.toString());
            }
            return java.util.Set.of();
        }

        void close() {
            this.http.stop(0);
        }
    }
}
