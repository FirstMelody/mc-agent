package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.perception.ChatLog;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves that one model tool call can keep a bot working through a slow following inference.
 *
 * <p>Enabled only with {@code MCAGENT_PLAN_TEST=true}. The first scripted reply contains one
 * {@code plan} call with four sequential actions: go east, go west, return, speak. The next LLM
 * request deliberately takes eight seconds. Passing means the bot completes every queued action
 * before that slow response arrives; without server-side planning it would stop after the first
 * movement and wait the full eight seconds.
 */
public final class PlanSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/plantest");
    private static final String BOT = "PlanBot";
    private static final String LISTENER = "PlanListener";
    private static final String DONE = "plan sequence complete";

    private final MinecraftServer server;
    private final AtomicBoolean slowRequestStarted = new AtomicBoolean();
    private final AtomicBoolean slowRequestFinished = new AtomicBoolean();
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private boolean failed;
    private int ticks;
    private double startX;
    private double furthestEast = Double.NEGATIVE_INFINITY;
    private double furthestWest = Double.POSITIVE_INFINITY;

    private PlanSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_PLAN_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("PLANTEST: armed");
        return new PlanSmokeTest(server);
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

        BotManager.BotHandle bot = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        if (bot != null) {
            this.furthestEast = Math.max(this.furthestEast, bot.player().getX());
            this.furthestWest = Math.min(this.furthestWest, bot.player().getX());
        }

        if (heardDone()) {
            this.report();
            return;
        }
        if (this.ticks > 600) {
            this.check("the four-step plan completed within 30 seconds", false);
            this.report();
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        Vec3 spawn = Vec3.atBottomCenterOf(level.getSharedSpawnPos()).add(0.5D, 0.0D, 0.5D);
        this.startX = spawn.x;

        String planArguments = """
                {"steps":[
                  {"tool":"goto","arguments":{"x":%f,"z":%f}},
                  {"tool":"goto","arguments":{"x":%f,"z":%f}},
                  {"tool":"goto","arguments":{"x":%f,"z":%f}},
                  {"tool":"say","arguments":{"message":"%s"}}
                ]}
                """.formatted(spawn.x + 6.0D, spawn.z,
                        spawn.x - 6.0D, spawn.z,
                        spawn.x, spawn.z, DONE);

        AtomicInteger request = new AtomicInteger();
        try {
            this.model = new ScriptedLlmServer(body -> {
                if (request.getAndIncrement() == 0) {
                    return ScriptedLlmServer.toolCall("call_plan", "plan", planArguments);
                }
                this.slowRequestStarted.set(true);
                try {
                    Thread.sleep(8_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                this.slowRequestFinished.set(true);
                return ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("PLANTEST: FAIL - could not start scripted endpoint", e);
            this.failed = true;
            this.finish();
            return;
        }

        BotManager.BotHandle bot = Agent.botManager().spawn(BOT, level, spawn, true);
        BotManager.BotHandle listener = Agent.botManager().spawn(
                LISTENER, level, spawn.add(0.0D, 0.0D, 2.0D), true);
        if (bot == null || listener == null || !Agent.attachBrain(bot.player())) {
            this.check("the plan bot and listener spawned with a brain", false);
            this.finish();
            return;
        }
        ChatLog.clear(listener.player().getUUID());
        LOG.info("PLANTEST: one plan call will walk +6, -6 and home while the next LLM request "
                + "is delayed by 8 seconds");
    }

    private boolean heardDone() {
        BotManager.BotHandle listener = Agent.botManager() == null ? null : Agent.botManager().get(LISTENER);
        if (listener == null) {
            return false;
        }
        return ChatLog.recent(listener.player(), 100).stream()
                .anyMatch(line -> line.speaker().equals(BOT) && line.text().equals(DONE));
    }

    private void report() {
        BotManager.BotHandle bot = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        double finalX = bot == null ? Double.NaN : bot.player().getX();
        this.check("the first waypoint was reached (east travel "
                + String.format("%.2f", this.furthestEast - this.startX) + ")",
                this.furthestEast - this.startX > 4.0D);
        this.check("the second waypoint was reached (west travel "
                + String.format("%.2f", this.startX - this.furthestWest) + ")",
                this.startX - this.furthestWest > 4.0D);
        this.check("the bot returned home before speaking",
                Double.isFinite(finalX) && Math.abs(finalX - this.startX) < 2.0D);
        this.check("the lookahead LLM request started while the plan was running",
                this.slowRequestStarted.get());
        this.check("all four actions completed before the 8-second LLM response",
                !this.slowRequestFinished.get());
        this.check("the final queued say action ran", heardDone());
        this.check("the plan schema was present in the real request",
                this.model != null && this.model.maxOccurrences("\"name\":\"plan\"") >= 1);
        String usage = Agent.brainManager() == null ? "" : Agent.brainManager().describe();
        this.check("provider token totals and cache hit rate were accumulated",
                usage.contains("calls=1 input=100 output=10 total=110")
                        && usage.contains("cache=64.0% (hit=64 miss=36"));

        LOG.info("PLANTEST VERDICT: {}", this.failed ? "FAIL" : "PASS");
        this.finish();
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            this.failed = true;
        }
        LOG.info("PLANTEST {}: {}", ok ? "PASS" : "FAIL", what);
    }

    private void finish() {
        this.finished = true;
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        // Cancel the deliberately slow lookahead before closing its endpoint, otherwise the test
        // produces a noisy connection-reset warning while it is already shutting down.
        BotManager.BotHandle bot = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        var brains = Agent.brainManager();
        var brain = bot == null || brains == null ? null : brains.get(bot.player().getUUID());
        if (brain != null) {
            brain.setPaused(true, "plan smoke-test shutdown");
        }
        com.melody.mcagent.rt.brain.AgentBrain.shutdown();
        if (this.model != null) {
            try {
                this.model.close();
            } catch (Throwable ignored) {
                // Best effort in a gated test process.
            }
        }
        LOG.info("PLANTEST: done, stopping server");
        this.server.halt(false);
    }
}
