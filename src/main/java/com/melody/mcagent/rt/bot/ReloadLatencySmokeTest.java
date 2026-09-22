package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.llm.JevClient;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves a hot reload does not park the server thread on a model call that cannot answer in time.
 *
 * <p>{@code /mcagent reload} runs on the server thread, removes every bot, and then shuts the shared
 * model executor down - and an in-flight model call holds the class loader that is about to be
 * closed, so the shutdown has to deal with it. It used to do that by waiting: {@code shutdown()},
 * {@code awaitTermination(2 s)}, and only then {@code shutdownNow()}. That wait could never succeed,
 * because a model call is a blocking HTTP request whose own timeout is tens of seconds: a call in
 * flight when the reload began was certain to still be in flight two seconds later. Every reload
 * issued while a bot was mid-thought therefore froze the server for 2.0 s - production logged 18 of
 * 51 reloads at 2005 +/- 5 ms, each followed by "Can't keep up! ... 50 ticks behind" - and then
 * interrupted the call anyway, which unwinds it in about 8 ms.
 *
 * <p>This test puts one call in flight against a model that answers after {@link #MODEL_DELAY_MS}
 * and times {@link AgentBrain#shutdown()} - the same call, on the same thread, that the reload makes.
 * It deliberately measures that call and not the whole reload: the rest of the reload is Minecraft
 * work (removing bots, re-registering commands) that has to stay on the server thread and is
 * reported by the reload's own log lines.
 *
 * <p>Enabled with {@code MCAGENT_RELOAD_TEST=true}. Positive control:
 * {@code MCAGENT_RELOAD_TEST=true MCAGENT_RELOAD_INTERRUPT=off} restores the old wait-then-interrupt
 * order, and this test must then report CONTROL-PASS - that is what proves it can see the stall at
 * all rather than passing because nothing was ever in flight.
 */
public final class ReloadLatencySmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/reloadtest");

    private static final String BOT = "ReloadBot";

    /**
     * How long the scripted model takes to answer. Anything longer than a reload may wait will do;
     * a real endpoint takes seconds, and this is deliberately far beyond that so the test cannot
     * pass by the call happening to finish first.
     */
    private static final long MODEL_DELAY_MS = 30_000L;

    /** A shutdown this fast did not wait for anything. The interrupt path costs about 10 ms. */
    private static final long FAST_ENOUGH_MILLIS = 250L;

    /** The wait this replaced cost 2005 +/- 5 ms; anything near that is the old behaviour. */
    private static final long SLOW_PATH_MILLIS = 1900L;

    /** Ticks to wait for the bot to get a call out to the model before giving up on the run. */
    private static final int WAIT_FOR_CALL_TICKS = 600;

    private final MinecraftServer server;

    private ScriptedLlmServer model;
    /** Released on the way out so the stub's handler thread does not sit in its sleep for 30 s. */
    private CountDownLatch release;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private int ticks;

    private ReloadLatencySmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_RELOAD_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("RELOADTEST: armed");
        return new ReloadLatencySmokeTest(server);
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

        if (this.model == null) {
            return;
        }
        if (this.model.requestCount() >= 1) {
            this.measure();
            return;
        }
        if (this.ticks > WAIT_FOR_CALL_TICKS) {
            LOG.error("RELOADTEST VERDICT: FAIL - the bot never sent a model call, so nothing was in "
                    + "flight and this run proves nothing about the reload");
            this.finishQuietly();
        }
    }

    /**
     * Time the shutdown with a call in flight.
     *
     * <p>The stub records a request as soon as its body has been read and before its script runs, so
     * a recorded request that has not been answered yet is exactly "the client is blocked waiting" -
     * which is the state a reload finds a thinking bot in.
     */
    private void measure() {
        int inFlight = this.model.requestCount();
        long start = System.nanoTime();
        AgentBrain.shutdown();
        long ms = (System.nanoTime() - start) / 1_000_000L;
        boolean interruptFirst = !"off".equalsIgnoreCase(System.getenv("MCAGENT_RELOAD_INTERRUPT"));

        LOG.info("RELOADTEST {} model call(s) in flight; the reload's shutdown of the model executor "
                + "took {} ms (interrupt-first={})", inFlight, ms, interruptFirst);

        if (!interruptFirst) {
            LOG.info("RELOADTEST VERDICT: {}", ms >= SLOW_PATH_MILLIS
                    ? "CONTROL-PASS (with the old wait-then-interrupt order the shutdown costs "
                            + ms + " ms of server thread, which is the stall this test is for)"
                    : "CONTROL-FAIL (the wait was restored but the shutdown was still fast, so this "
                            + "test cannot detect the stall it exists for)");
            this.finishQuietly();
            return;
        }
        LOG.info("RELOADTEST VERDICT: {}", ms < FAST_ENOUGH_MILLIS
                ? "PASS (the server thread is not held while a model call unwinds)"
                : "FAIL - the shutdown still cost " + ms + " ms of server thread; an in-flight call "
                        + "is being waited on rather than interrupted");
        this.finishQuietly();
    }

    private void begin() {
        this.release = new CountDownLatch(1);
        try {
            // A model that has received the request and is thinking: exactly the state a bot is in
            // when an operator reloads the jar.
            this.model = new ScriptedLlmServer(body -> {
                try {
                    this.release.await(MODEL_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("RELOADTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }
        // Jev would answer some decisions without the planning model ever being called, which would
        // leave nothing in flight to measure. Disabled in memory only: the properties file is left
        // alone, and restoring the LLM settings re-reads it.
        if (Agent.brainManager() != null) {
            Agent.brainManager().applyJevSettings(JevClient.Settings.disabled());
        }

        ServerLevel level = this.server.overworld();
        Vec3 at = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        BotManager.BotHandle bot = Agent.botManager() == null
                ? null : Agent.botManager().spawn(BOT, level, at, true);
        if (bot == null || !Agent.attachBrain(bot.player())) {
            LOG.error("RELOADTEST: FAIL - could not spawn a bot with a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("RELOADTEST bot '{}' spawned with a brain; waiting for its first model call to reach "
                + "the scripted endpoint, which answers only after {} ms", BOT, MODEL_DELAY_MS);
    }

    private void finishQuietly() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        // Puts the operator's LLM settings back, which also re-reads the Jev file this test
        // overrode in memory.
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        // Idempotent: the measurement above already stopped the executor.
        AgentBrain.shutdown();
        if (this.release != null) {
            this.release.countDown();
        }
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
