package com.melody.mcagent.rt.command;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.Config;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the operator control commands really work, by dispatching them through the server's own
 * command dispatcher rather than calling the handler methods directly.
 *
 * <p>This matters because command registration is where mistakes hide: a missing permission
 * requirement, a mis-typed argument name, or a subtree that never got attached all compile cleanly
 * and only show up when the command is actually run.
 *
 * <p>Enabled with MCAGENT_CMD_TEST=true.
 */
public final class CommandSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/cmdtest");

    private final MinecraftServer server;
    private int ticks;
    private int phase;
    private boolean started;
    private boolean finished;
    private int callsBeforePause;
    private int callsAtPauseEnd;
    private int callsAfterResume;

    public CommandSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_CMD_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("CMDTEST: armed");
        return new CommandSmokeTest(server);
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

        // Record how many LLM turns have happened so far, so pause/resume can be judged on
        // behaviour rather than on the flag we ourselves set.
        if (this.phase == 3 && this.ticks > 215) {
            this.callsAtPauseEnd = this.countTurns();
        }

        switch (this.phase) {
            case 0 -> {
                if (this.ticks > 40) {
                    this.phase = 1;
                    this.run("mcagent status");
                }
            }
            case 1 -> {
                if (this.ticks > 80) {
                    this.phase = 2;
                    this.run("mcagent goal CmdBot Build a small shelter out of oak planks");
                    this.run("mcagent goal CmdBot");
                }
            }
            case 2 -> {
                if (this.ticks > 120) {
                    this.phase = 3;
                    this.callsBeforePause = this.countTurns();
                    this.run("mcagent pause CmdBot");
                    LOG.info("CMDTEST paused at tick {} after {} LLM call(s)", this.ticks, this.callsBeforePause);
                    // The incident this guards: a paused bot looks healthy in every other field of
                    // /mcagent status (not thinking, not mining, no cooldown), so a bot frozen for
                    // fifteen minutes was read as merely idle. This is the one moment the pause can
                    // be seen in the operator's own diagnostic line, so it is checked while it lasts.
                    var brains = Agent.brainManager();
                    String status = brains == null ? "" : brains.describe().trim();
                    LOG.info("CMDTEST status while paused: {}", status);
                    LOG.info("CMDTEST status marks paused: {}", status.contains("PAUSED"));
                }
            }
            case 3 -> {
                if (this.ticks > 220) {
                    this.phase = 4;
                    LOG.info("CMDTEST during-pause LLM calls: {}", this.callsAtPauseEnd);
                    this.run("mcagent resume CmdBot");
                    LOG.info("CMDTEST resumed at tick {}", this.ticks);
                }
            }
            case 4 -> {
                if (this.ticks > 300) {
                    this.phase = 5;
                    this.run("mcagent think CmdBot");
                }
            }
            case 5 -> {
                if (this.ticks > 400) {
                    this.report();
                }
            }
            default -> {
            }
        }
    }

    /** How many LLM turns the bot has completed so far, across all brains. */
    private int countTurns() {
        var brain = brain();
        return brain == null ? 0 : brain.turnsCompleted();
    }

    private com.melody.mcagent.rt.brain.AgentBrain brain() {
        var brains = Agent.brainManager();
        var bots = Agent.botManager();
        if (brains == null || bots == null) {
            return null;
        }
        var handle = bots.get("CmdBot");
        return handle == null ? null : brains.get(handle.player().getUUID());
    }

    /** Dispatch a command exactly as a player or the console would. */
    private void run(String command) {
        try {
            CommandSourceStack source = this.server.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withPermission(4);
            this.server.getCommands().performPrefixedCommand(source, command);
            LOG.info("CMDTEST ran: /{}", command);
        } catch (Throwable t) {
            LOG.error("CMDTEST command failed: /{}", command, t);
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        Vec3 pos = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        var handle = Agent.botManager().spawn("CmdBot", level, pos, false);
        if (handle == null) {
            LOG.error("CMDTEST: FAIL - could not spawn bot");
            this.finish();
            return;
        }

        // Configure the LLM from the environment so the bot has a brain to control.
        String key = System.getenv("MCAGENT_TEST_KEY");
        if (key != null && !key.isBlank()) {
            AgentConfig.LLM.baseUrl.set("http://10.0.6.6:7863/v1");
            AgentConfig.LLM.apiKey.set(key);
            AgentConfig.LLM.model.set("deepseek-v4.1-flash");
            Config.apply();
        }

        boolean attached = Agent.attachBrain(handle.player());
        LOG.info("CMDTEST: bot spawned, brain attached={}", attached);

        // Sanity check that our commands are actually registered.
        // findNode takes the path as a list of literals, and returns null when absent.
        var dispatcher = this.server.getCommands().getDispatcher();
        for (String path : new String[]{"mcagent", "mcagent pause", "mcagent resume",
                "mcagent goal", "mcagent think", "mcagent llm", "mcagent reload",
                "mcagent stop", "mcagent goto", "mcagent inventory", "mcagent spawn"}) {
            boolean present = dispatcher.findNode(java.util.List.of(path.split(" "))) != null;
            LOG.info("CMDTEST registered '{}': {}", path, present);
        }
    }

    private void report() {
        var handle = Agent.botManager() == null ? null : Agent.botManager().get("CmdBot");
        var brain = brain();

        LOG.info("CMDTEST ================ RESULT ================");
        if (brain == null) {
            LOG.error("CMDTEST FAIL: no brain found");
        } else {
            LOG.info("CMDTEST brain present      : true");
            LOG.info("CMDTEST paused now         : {}", brain.isPaused());
            LOG.info("CMDTEST standing goal      : {}", brain.standingGoal());
            LOG.info("CMDTEST context tokens     : {}", brain.contextTokens());
            this.callsAfterResume = brain.turnsCompleted();
            LOG.info("CMDTEST LLM turns          : before-pause={} during-pause={} after-resume={}",
                    this.callsBeforePause, this.callsAtPauseEnd - this.callsBeforePause,
                    this.callsAfterResume - this.callsAtPauseEnd);
            boolean pauseWorked = (this.callsAtPauseEnd - this.callsBeforePause) == 0;
            boolean actedBefore = this.callsBeforePause > 0;
            LOG.info("CMDTEST acted before pause : {}", actedBefore);
            LOG.info("CMDTEST silent while paused: {}", pauseWorked);
        }
        if (handle != null) {
            LOG.info("CMDTEST bot still in world : {}", !handle.player().isRemoved());
            LOG.info("CMDTEST bot position       : {}", handle.player().position());
        }
        LOG.info("CMDTEST ======================================");
        this.finish();
    }

    private void finish() {
        this.finished = true;
        try {
            if (Agent.botManager() != null) {
                Agent.botManager().remove("CmdBot");
            }
        } catch (Throwable ignored) {
            // Cleanup best-effort.
        }
        LOG.info("CMDTEST: done, stopping server");
        this.server.halt(false);
    }
}
