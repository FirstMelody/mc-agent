package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.ChatLog;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Proves any audible chat causes an immediate, complete-state, optionally silent LLM turn. */
public final class ChatInvokeSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/chatinvoketest");
    private static final String BOT = "ChatInvokeBot";
    private static final String SPEAKER = "ChatInvokeSpeaker";
    private static final String MESSAGE = "The weather looks strange today";

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean asked;
    private boolean finished;
    private int ticks;
    private int requestCountBeforeChat;
    private int chatTick;

    private ChatInvokeSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_CHAT_INVOKE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("CHATINVOKETEST: armed");
        return new ChatInvokeSmokeTest(server);
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
        if (!this.asked && this.model != null && this.model.requestCount() >= 1 && brain != null
                && !Boolean.TRUE.equals(brain.debugState().get("thinking"))) {
            this.asked = true;
            this.requestCountBeforeChat = this.model.requestCount();
            this.chatTick = this.ticks;
            BotManager.BotHandle speaker = Agent.botManager().get(SPEAKER);
            NeoForge.EVENT_BUS.post(new ServerChatEvent(
                    speaker.player(), MESSAGE, Component.literal(MESSAGE)));
            LOG.info("CHATINVOKETEST posted undirected chat inside an autonomous cooldown");
            return;
        }

        if (this.asked && this.model.requestCount() > this.requestCountBeforeChat) {
            String request = this.model.lastRequest();
            int latency = this.ticks - this.chatTick;
            boolean message = request.contains(MESSAGE);
            boolean self = request.contains("YOUR STATE")
                    && request.contains("Health:") && request.contains("Position:");
            boolean world = request.contains("WHAT YOU CAN SEE")
                    && request.contains("Other players online:");
            boolean allInventory = request.contains("minecraft:diamond [slot 35]");
            boolean activity = request.contains("WHAT YOU ARE DOING RIGHT NOW")
                    && request.contains("idle; no actions are queued");
            boolean silent = ChatLog.recent(Agent.botManager().get(SPEAKER).player(), 20).stream()
                    .noneMatch(heard -> BOT.equals(heard.speaker()));
            boolean pass = latency <= 10 && message && self && world && allInventory
                    && activity && silent;
            LOG.info("CHATINVOKETEST latency={} message={} self={} world={} allInventory={} "
                    + "activity={} modelStayedSilent={}", latency, message, self, world,
                    allInventory, activity, silent);
            this.finish(pass);
        } else if (this.ticks > 240) {
            this.finish(false);
        }
    }

    private void begin() {
        try {
            this.model = new ScriptedLlmServer(body -> ScriptedLlmServer.silent());
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("CHATINVOKETEST could not start scripted endpoint", e);
            this.finish(false);
            return;
        }

        ServerLevel level = this.server.overworld();
        Vec3 at = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        BotManager.BotHandle bot = Agent.botManager().spawn(BOT, level, at, true);
        BotManager.BotHandle speaker = Agent.botManager().spawn(SPEAKER, level, at.add(2, 0, 0), true);
        if (bot == null || speaker == null) {
            this.finish(false);
            return;
        }
        ChatLog.clear(speaker.player().getUUID());
        for (int slot = 0; slot < 36; slot++) {
            bot.player().getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE));
        }
        bot.player().getInventory().setItem(35, new ItemStack(Items.DIAMOND));
        if (!Agent.attachBrain(bot.player())) {
            this.finish(false);
        }
    }

    private AgentBrain brain() {
        BotManager.BotHandle handle = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        return handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private void finish(boolean pass) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("CHATINVOKETEST VERDICT: {}", pass ? "PASS" : "FAIL");
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
            Agent.botManager().remove(SPEAKER);
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
