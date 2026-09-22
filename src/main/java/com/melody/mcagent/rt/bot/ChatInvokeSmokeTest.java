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
    /** Deliberately does not name the bot: a player talking to the only bot around should not have
     * to repeat its name every sentence. */
    private static final String MESSAGE = "继续挖钻石 多挖点钻石";
    /** The message a production player sent the bot from out of earshot, and got no answer to. */
    private static final String FAR_MESSAGE = "转中文";

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean asked;
    private boolean finished;
    private int ticks;
    private int requestCountBeforeChat;
    private int chatTick;
    /** Phase two: the forced-recovery turn must not consume the message it was shown. */
    private boolean forcedTurnArmed;
    private int forcedTurnRequestCountBefore;
    private int forcedTurnTick;
    /** Phase three: a player talking to the only bot is heard and addressed from any distance. */
    private boolean farTurnArmed;
    private int farTurnRequestCountBefore;
    private int farTurnTick;

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
        if (this.farTurnArmed) {
            this.checkFarMessage(brain);
            return;
        }
        if (this.forcedTurnArmed) {
            this.checkForcedTurn(brain);
            return;
        }
        if (!this.asked && this.model != null && this.model.requestCount() >= 1 && brain != null
                && !Boolean.TRUE.equals(brain.debugState().get("thinking"))) {
            this.asked = true;
            this.requestCountBeforeChat = this.model.requestCount();
            this.chatTick = this.ticks;
            BotManager.BotHandle speaker = Agent.botManager().get(SPEAKER);
            NeoForge.EVENT_BUS.post(new ServerChatEvent(
                    speaker.player(), MESSAGE, Component.literal(MESSAGE)));
            LOG.info("CHATINVOKETEST posted an unnamed chat line from the only other player, inside "
                    + "an autonomous cooldown");
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
            // Unnamed, but the bot is the only one who could have heard it: it must count as being
            // addressed, or its answer would be refused as unprompted narration and the bot would
            // look dead to the player talking to it.
            // Read from the transcript, not from ChatLog: the turn that is being asserted has
            // already marked the message read, so the unheard list is empty by now. What matters is
            // what the model was shown - the direct-address banner.
            boolean addressed = this.model.maxOccurrences("IS TALKING TO YOU") >= 1;
            // The other half of the rule: a line that names somebody else is for them, even when the
            // bot is the only one in earshot. The name has to belong to an online player, and the
            // speaker is the only other one here, so it names itself - what matters is that it is a
            // name that is not the bot's.
            String other = SPEAKER + " 你在干嘛";
            NeoForge.EVENT_BUS.post(new ServerChatEvent(
                    Agent.botManager().get(SPEAKER).player(), other, Component.literal(other)));
            boolean notForTheBot = ChatLog.unheardDirected(
                    Agent.botManager().get(BOT).player()).isEmpty();
            boolean pass = latency <= 10 && message && self && world && allInventory
                    && activity && silent && addressed && notForTheBot;
            LOG.info("CHATINVOKETEST latency={} message={} self={} world={} allInventory={} "
                    + "activity={} modelStayedSilent={} unnamedCountsAsAddressed={} "
                    + "namingAnotherPlayerIsNotForTheBot={}", latency, message, self, world,
                    allInventory, activity, silent, addressed, notForTheBot);
            if (!pass) {
                this.finish(false);
                return;
            }
            this.askAgainAfterAForcedTurn();
        } else if (this.ticks > 240) {
            this.finish(false);
        }
    }

    /**
     * The failure a production player actually hit: the endpoint hung, the bot never got a
     * conversational turn, the watchdog forced a recovery turn, and that turn marked the player's
     * messages read - the bot was told nothing and the questions were gone.
     *
     * <p>Reproduced without the minute-long hang by starting the same turn the watchdog starts. The
     * assertion is that the message is still unheard afterwards, so the very next turn shows it.
     */
    private void askAgainAfterAForcedTurn() {
        BotManager.BotHandle speaker = Agent.botManager().get(SPEAKER);
        AgentBrain brain = this.brain();
        NeoForge.EVENT_BUS.post(new ServerChatEvent(
                speaker.player(), MESSAGE, Component.literal(MESSAGE)));
        this.forcedTurnRequestCountBefore = this.model.requestCount();
        this.forcedTurnTick = this.ticks;
        brain.forceStuckDecisionForTest();
        this.forcedTurnArmed = true;
        LOG.info("CHATINVOKETEST posted the message again and forced a watchdog-labelled decision; "
                + "the message must survive it");
    }

    /**
     * Wait for the forced turn to be answered, then look for the bot's own report that it kept the
     * message. The report travels in the next observation, which is also the proof that a next turn
     * exists to answer it - a swallowed message produces no such line.
     *
     * <p>Positive control: with {@code MCAGENT_CHAT_KEEP=off} the report never appears and this test
     * reports CONTROL-PASS, which is the evidence that it can detect the bug it is for.
     */
    private void checkForcedTurn(AgentBrain brain) {
        boolean forcedTurnAnswered = this.model.requestCount() > this.forcedTurnRequestCountBefore
                && brain != null && !Boolean.TRUE.equals(brain.debugState().get("thinking"));
        boolean kept = this.model.maxOccurrences("chat message(s) unheard") >= 1;
        if (kept || (forcedTurnAnswered && this.ticks - this.forcedTurnTick > 200)) {
            boolean keepEnabled = AgentBrain.keepChatThroughForcedTurn();
            LOG.info("CHATINVOKETEST forcedTurnAnswered={} keptTheMessage={} requests={}",
                    forcedTurnAnswered, kept, this.model.requestCount());
            if (!keepEnabled) {
                LOG.info("CHATINVOKETEST VERDICT: {}", kept
                        ? "CONTROL-FAIL (rule disabled but the message was kept anyway; this test "
                                + "cannot detect the bug it is for)"
                        : "CONTROL-PASS (rule disabled and the forced turn consumed the message, "
                                + "as it did in production)");
                this.finishQuietly();
                return;
            }
            if (!kept) {
                LOG.info("CHATINVOKETEST VERDICT: FAIL");
                this.finishQuietly();
                return;
            }
            this.postFromOutOfEarshot();
        }
    }

    /**
     * The production incident this phase exists for: the bot asked a player a question, walked away
     * doing chores, the player answered "转中文" from just outside earshot, and the bot never heard
     * it - no chat turn, no speech-gate decision, nothing in the log at all. Two rules are asserted
     * here: a message is recorded whether or not the bot is within 64 blocks, and the only bot on the
     * server counts as addressed from any distance.
     */
    private void postFromOutOfEarshot() {
        BotManager.BotHandle speaker = Agent.botManager().get(SPEAKER);
        BotManager.BotHandle bot = Agent.botManager().get(BOT);
        Vec3 far = bot.player().position().add(200.0D, 0.0D, 0.0D);
        speaker.player().teleportTo((ServerLevel) bot.player().level(), far.x, far.y, far.z,
                java.util.Set.of(), 0.0F, 0.0F);
        NeoForge.EVENT_BUS.post(new ServerChatEvent(
                speaker.player(), FAR_MESSAGE, Component.literal(FAR_MESSAGE)));
        this.farTurnRequestCountBefore = this.model.requestCount();
        this.farTurnTick = this.ticks;
        this.farTurnArmed = true;
        LOG.info("CHATINVOKETEST the speaker moved {} blocks away and said '{}'; the only bot must "
                + "still hear it and treat it as addressed",
                (int) Math.sqrt(speaker.player().distanceToSqr(bot.player())), FAR_MESSAGE);
    }

    private void checkFarMessage(AgentBrain brain) {
        boolean answered = this.model.requestCount() > this.farTurnRequestCountBefore;
        if (!answered && this.ticks - this.farTurnTick <= 200) {
            return;
        }
        String request = this.model.lastRequest();
        boolean heard = request.contains(FAR_MESSAGE);
        // The banner for THIS message, not any banner: the transcript still carries the one from the
        // first phase, so a plain contains() would pass for the wrong reason - which is exactly what
        // the positive control caught.
        int banner = request.lastIndexOf("IS TALKING TO YOU RIGHT NOW:");
        boolean addressed = banner >= 0 && request
                .substring(banner, Math.min(request.length(), banner + 140))
                .contains(FAR_MESSAGE);
        LOG.info("CHATINVOKETEST farTurn: request={} heard={} addressed={}", answered, heard,
                addressed);
        if (!ChatLog.soleBotHearsEverywhere()) {
            LOG.info("CHATINVOKETEST VERDICT: {}", addressed
                    ? "CONTROL-FAIL (rule disabled but the far message was still addressed; this "
                            + "test cannot detect the bug it is for)"
                    : "CONTROL-PASS (rule disabled: the far message is not addressed, which is how "
                            + "production lost it)");
            this.finishQuietly();
            return;
        }
        LOG.info("CHATINVOKETEST VERDICT: {}", answered && heard && addressed ? "PASS" : "FAIL");
        this.finishQuietly();
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
        LOG.info("CHATINVOKETEST VERDICT: {}", pass ? "PASS" : "FAIL");
        this.finishQuietly();
    }

    /** Teardown without a verdict line, for phases that state their own. */
    private void finishQuietly() {
        if (this.finished) {
            return;
        }
        this.finished = true;
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
