package com.melody.mcagent.rt;

import java.util.ArrayList;
import java.util.List;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.AgentRuntime;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.brain.BrainManager;
import com.melody.mcagent.rt.bot.BotManager;
import com.melody.mcagent.rt.bot.AnvilEnchantSmokeTest;
import com.melody.mcagent.rt.bot.ArmorEquipSmokeTest;
import com.melody.mcagent.rt.bot.BotSmokeTest;
import com.melody.mcagent.rt.bot.BrainSmokeTest;
import com.melody.mcagent.rt.bot.ChatThrottleSmokeTest;
import com.melody.mcagent.rt.bot.ChatInvokeSmokeTest;
import com.melody.mcagent.rt.bot.CombatSmokeTest;
import com.melody.mcagent.rt.command.CommandUxSmokeTest;
import com.melody.mcagent.rt.bot.DangerReflexSmokeTest;
import com.melody.mcagent.rt.bot.EscapeSmokeTest;
import com.melody.mcagent.rt.bot.FarmBuildSmokeTest;
import com.melody.mcagent.rt.bot.FarmSmokeTest;
import com.melody.mcagent.rt.bot.JevSpeechGateSmokeTest;
import com.melody.mcagent.rt.bot.JevMiningRecoverySmokeTest;
import com.melody.mcagent.rt.bot.JevRoutingSmokeTest;
import com.melody.mcagent.rt.bot.PlanSmokeTest;
import com.melody.mcagent.rt.bot.MineDropSmokeTest;
import com.melody.mcagent.rt.bot.MiningGoalSmokeTest;
import com.melody.mcagent.rt.bot.PersistenceSmokeTest;
import com.melody.mcagent.rt.bot.ReloadLatencySmokeTest;
import com.melody.mcagent.rt.bot.SableCompatTest;
import com.melody.mcagent.rt.bot.SelfClearedStepSmokeTest;
import com.melody.mcagent.rt.bot.StructureGuardSmokeTest;
import com.melody.mcagent.rt.bot.TunnelSmokeTest;
import com.melody.mcagent.rt.command.BotCommands;
import com.melody.mcagent.rt.command.CommandSmokeTest;
import com.melody.mcagent.rt.command.InventoryCommandSmokeTest;
import com.melody.mcagent.rt.knowledge.KnowledgeManager;
import com.melody.mcagent.rt.memory.BotMemory;
import com.melody.mcagent.rt.perception.ChatLog;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime half of the mod: everything that used to be in {@code McAgentMod}.
 *
 * <p>One instance is created per load by {@code RuntimeHost}, through the public no-arg constructor.
 * It owns the managers, registers the runtime's commands, and ticks the bots. It holds no static
 * state: a reload creates a new instance in a new class loader, and anything static here would be
 * just as disposable — but state that survives a reload by accident is far harder to reason about
 * than a field that visibly belongs to one generation.
 */
public final class RuntimeEntry implements AgentRuntime {

    /** Reported by {@link #status()} and logged by the core when this jar is loaded. */
    public static final String VERSION = "0.1.0";

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/runtime");

    /** Gated smoke tests currently armed, ticked from {@link #onServerTick}. */
    private final List<TestHook> tests = new ArrayList<>();

    @Nullable
    private BotManager botManager;
    @Nullable
    private BrainManager brainManager;
    private boolean live;

    public RuntimeEntry() {
        // First thing on every load: everything after this line goes to the mod's own log file
        // instead of the server console. See Logging for why it is done from here.
        Logging.routeToOwnFile();
    }

    @Override
    public void onServerStarted(MinecraftServer server) {
        this.botManager = new BotManager(server);
        this.brainManager = new BrainManager(server);
        Agent.install(this.botManager, this.brainManager);
        this.live = true;

        // Build the item/recipe index now: recipes are guaranteed available at server start, and
        // building it later risks reading ingredients before tags are bound.
        //
        // It builds in the background. The recipe list is snapshotted on this thread and the indexing
        // runs on a worker, because this method is also the reload path and indexing is the most
        // expensive step in it (107-591 ms in production). Until the worker publishes, the item and
        // recipe tools answer "the item/recipe index is not ready yet; try again shortly" - a far
        // better trade than freezing the server thread for it on every reload.
        KnowledgeManager.rebuild(server);

        // Pull in whatever the config files currently say. This also runs on every reload.
        Config.apply();

        if (!this.brainManager.isConfigured() && AgentConfig.COMMON.brainsEnabled.get()) {
            LOG.warn("Bots will exist but will not think, because the LLM settings are incomplete: {}. "
                    + "Set them with /mcagent llm endpoint|key|model, or edit config/mcagent-llm.toml "
                    + "and run /mcagent reloadconfig.",
                    Config.settings().problem());
        }

        LOG.info("MC Agent ready. {}", Config.policy().describe());

        armTests(server);
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        BotManager bots = Agent.botManager();
        if (bots != null) {
            bots.tick();
        }
        BrainManager brains = Agent.brainManager();
        if (brains != null) {
            brains.tick();
        }
        for (TestHook test : this.tests) {
            try {
                test.onTick();
            } catch (Throwable t) {
                LOG.error("Smoke test tick failed", t);
            }
        }
    }

    @Override
    public void onServerStopping(MinecraftServer server) {
        shutdown();
    }

    /**
     * Capture player chat so bots can hear it.
     *
     * <p>Recording is per-bot and distance-filtered in {@link ChatLog}; this handler only supplies
     * the candidate bots. Bots' own speech also passes through here, which is intentional — they
     * should be able to hear each other.
     */
    @Override
    public void onServerChat(ServerChatEvent event) {
        BotManager bots = Agent.botManager();
        if (bots == null || bots.handles().isEmpty()) {
            return;
        }
        List<ServerPlayer> players = new ArrayList<>();
        for (BotManager.BotHandle handle : bots.handles()) {
            players.add(handle.player());
        }
        // Every online name, so a message that names another player is not read as being for a bot
        // that merely happens to be the only one in earshot.
        List<String> names = new ArrayList<>();
        for (ServerPlayer online : event.getPlayer().server.getPlayerList().getPlayers()) {
            names.add(online.getName().getString());
        }
        ChatLog.record(event.getPlayer(), event.getRawText(),
                event.getPlayer().level().getGameTime(), players, names);
    }

    @Override
    public void registerCommands(RegisterCommandsEvent event) {
        BotCommands.register(event);
    }

    @Override
    public void onConfigChanged() {
        Config.apply();
    }

    @Override
    public String status() {
        BotManager bots = Agent.botManager();
        BrainManager brains = Agent.brainManager();
        return "mcagent-rt " + VERSION
                + " | bots=" + (bots == null ? 0 : bots.handles().size())
                + " | brains=" + (brains == null ? 0 : brains.all().size())
                + " | llm=" + (brains != null && brains.isConfigured() ? "ready" : "not configured")
                + " | jev=" + (brains == null ? "disabled" : brains.jevMode())
                + (this.live ? "" : " | not started");
    }

    /**
     * Shut this generation down: called on reload and on server stop.
     *
     * <p>Everything the runtime owns has to go, because the core is about to close this class
     * loader. That means every bot is removed through the ordinary removal path — and the LLM thread
     * pool is stopped. A bot left behind would be a live {@code ServerPlayer} of this generation
     * that the server keeps referencing, and a running thread would keep the loader reachable on its
     * own.
     *
     * <p>What the removal deliberately does <em>not</em> do is destroy the bots' saved playerdata.
     * Bots are spawned to persist, so killing one here writes out its inventory, XP and respawn
     * point like any other logout: {@code /mcagent reload} removes the bots, and spawning them again
     * afterwards brings them back with what they were carrying.
     */
    @Override
    public void onUnload() {
        shutdown();
        LOG.info("MC Agent runtime {} unloaded", VERSION);
    }

    private void shutdown() {
        this.tests.clear();
        this.live = false;

        BrainManager brains = Agent.brainManager();
        if (brains != null) {
            brains.detachAll();
        }
        BotManager bots = Agent.botManager();
        if (bots != null) {
            // Reload is a hard reset of live state: bots do not survive it, and an orphaned
            // synthetic player would both confuse the world and pin this class loader. Their saved
            // playerdata does survive - see the class docs above - so spawning them again restores
            // what they were carrying.
            if (!bots.handles().isEmpty()) {
                LOG.info("Removing {} bot(s) as part of the runtime shutdown; their saved state is "
                        + "kept, so spawn them again to get them back", bots.handles().size());
            }
            bots.removeAll();
        }

        AgentBrain.shutdown();
        KnowledgeManager.clear();
        // Drop cached notes so a world reopened in the same JVM reloads them from disk rather than
        // serving whatever the previous world left in memory.
        BotMemory.clearCache();
        Agent.clear();
    }

    /**
     * Arm the gated test harnesses.
     *
     * <p>Each one checks its own environment variable and returns nothing when it is not enabled,
     * so a production server never runs them.
     */
    private void armTests(MinecraftServer server) {
        this.tests.clear();
        add(BotSmokeTest.arm(server));
        add(AnvilEnchantSmokeTest.arm(server));
        add(FarmSmokeTest.arm(server));
        add(FarmBuildSmokeTest.arm(server));
        add(DangerReflexSmokeTest.arm(server));
        add(ArmorEquipSmokeTest.arm(server));
        add(BrainSmokeTest.arm(server));
        add(CommandSmokeTest.arm(server));
        add(InventoryCommandSmokeTest.arm(server));
        add(SableCompatTest.arm(server));
        add(PersistenceSmokeTest.arm(server));
        add(ReloadLatencySmokeTest.arm(server));
        add(MineDropSmokeTest.arm(server));
        add(MiningGoalSmokeTest.arm(server));
        add(ChatThrottleSmokeTest.arm(server));
        add(ChatInvokeSmokeTest.arm(server));
        add(CombatSmokeTest.arm(server));
        add(PlanSmokeTest.arm(server));
        add(EscapeSmokeTest.arm(server));
        add(TunnelSmokeTest.arm(server));
        add(SelfClearedStepSmokeTest.arm(server));
        add(StructureGuardSmokeTest.arm(server));
        add(JevSpeechGateSmokeTest.arm(server));
        add(JevRoutingSmokeTest.arm(server));
        add(JevMiningRecoverySmokeTest.arm(server));
        add(CommandUxSmokeTest.arm(server));
    }

    private void add(@Nullable TestHook test) {
        if (test != null) {
            this.tests.add(test);
        }
    }
}
