package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.perception.ChatLog;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves that the bot stops narrating, by giving it a model that will not stop talking.
 *
 * <p>Enabled with {@code MCAGENT_CHAT_TEST=true}. The model is a scripted local endpoint that asks
 * for a fresh {@code say} on every turn - a caricature of the model that flooded a player's chat with
 * seven near-identical lines. What is measured is what actually reaches the chat:
 * <ol>
 *   <li>how many turns the bot took, against how many lines it got out, and how many refusals were
 *       fed back to the model;</li>
 *   <li>that a player speaking gets an answer straight away, cooldown or no cooldown;</li>
 *   <li>that repeating the last line word for word is refused even when the bot was addressed.</li>
 * </ol>
 *
 * <p>The refusal text is returned as the tool result, so it appears in the log as
 * {@code Bot ChatBot called say({...}) -> not sent: ...}.
 */
public final class ChatThrottleSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/chattest");

    private static final String BOT = "ChatBot";
    private static final String LISTENER = "ChatListener";

    /** Turns the scripted model gets before the tally is judged. */
    private static final int TURNS_TO_OBSERVE = 10;
    /** The question a stand-in player asks mid-run, which must not be throttled. It names the bot,
     * which is what makes the mod treat the bot as addressed. */
    private static final String QUESTION = BOT + ", are you there? Please answer me.";
    /** The distinct line the scripted bot answers it with. */
    private static final String ANSWER = "I am here - answering you now.";

    private final MinecraftServer server;

    private ScriptedLlmServer model;
    /** The LLM settings this run found, restored on the way out so the config file is untouched. */
    private String[] savedSettings;
    private int ticks;
    private int step;
    private int linesSoFar;
    private int phaseStartTick;
    private int lastLineTick;
    private int questionAskedAtTick = -1;
    private int answerSeenAtTick = -1;
    private int turnsWhenAnswered;
    private int quietTicksAtQuestion = -1;
    private int linesBeforeQuestion;
    private boolean started;
    private boolean finished;
    private boolean failed;

    public ChatThrottleSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_CHAT_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("CHATTEST: armed");
        return new ChatThrottleSmokeTest(server);
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
        int lines = linesHeard().size();
        if (lines > this.linesSoFar) {
            this.linesSoFar = lines;
            this.lastLineTick = this.ticks;
        }

        switch (this.step) {
            case 0 -> {
                if (turns() >= TURNS_TO_OBSERVE) {
                    this.judgeNarration();
                    this.askQuestion();
                    this.step = 2;
                    this.phaseStartTick = this.ticks;
                }
            }
            case 2 -> {
                if (linesHeard().contains(ANSWER)) {
                    this.answerSeenAtTick = this.ticks;
                    this.turnsWhenAnswered = turns();
                    LOG.info("CHATTEST the bot answered {} tick(s) after the question, while only {} "
                            + "tick(s) had passed since its own last line (the unprompted cooldown "
                            + "is 2400)", this.answerSeenAtTick - this.questionAskedAtTick,
                            this.quietTicksAtQuestion);
                    this.step = 3;
                } else if (this.timedOut(600, "a spoken-to bot did not answer")) {
                    // handled by timedOut
                }
            }
            case 3 -> {
                // Two more turns are enough for the scripted model to ask for the same answer again,
                // which is exactly the repeat the bot must refuse.
                if (turns() >= this.turnsWhenAnswered + 2 || this.ticks - this.answerSeenAtTick > 400) {
                    this.step = 4;
                    this.report();
                }
            }
            default -> { }
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        Vec3 spawn = Vec3.atBottomCenterOf(level.getSharedSpawnPos());

        try {
            // One fresh, distinct line per turn, plus a fixed answer once a player has spoken: the
            // scripted model is deliberately as chatty as the one that caused the complaint.
            int[] turn = { 0 };
            this.model = new ScriptedLlmServer(request -> {
                if (request.contains("are you there")) {
                    return ScriptedLlmServer.say("call_answer", ANSWER);
                }
                turn[0]++;
                String line = turn[0] == 1 ? "Starting work."
                        : "还没挖到钻石，我这就去978,242那边挖 " + turn[0];
                return ScriptedLlmServer.say("call_" + turn[0], line);
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            LOG.info("CHATTEST scripted model listening at {} (the real LLM settings are put back "
                    + "when this test stops)", this.model.baseUrl());
        } catch (IOException e) {
            LOG.error("CHATTEST: FAIL - could not start the scripted model", e);
            this.failed = true;
            this.finish();
            return;
        }

        var handle = Agent.botManager().spawn(BOT, level, spawn.add(0.5D, 0.0D, 0.5D), true);
        var listener = Agent.botManager().spawn(LISTENER, level, spawn.add(2.5D, 0.0D, 0.5D), true);
        if (handle == null || listener == null) {
            LOG.error("CHATTEST: FAIL - could not spawn the bots");
            this.failed = true;
            this.finish();
            return;
        }
        ChatLog.clear(listener.player().getUUID());

        boolean attached = Agent.attachBrain(handle.player());
        LOG.info("CHATTEST {} joined with a brain attached={}", BOT, attached);
        if (!attached) {
            LOG.error("CHATTEST: FAIL - no brain attached, so no turns can happen");
            this.failed = true;
            this.finish();
        }
    }

    /** The tally the whole backstop exists for: many turns, almost no chat. */
    private void judgeNarration() {
        List<String> lines = linesHeard();
        int turns = turns();
        this.linesBeforeQuestion = lines.size();
        int refusals = this.model.maxOccurrences("not sent:");

        LOG.info("CHATTEST ================ NARRATION TALLY ================");
        LOG.info("CHATTEST turns taken by the bot          : {}", turns);
        LOG.info("CHATTEST chat lines it got out           : {}", lines.size());
        LOG.info("CHATTEST refusals reported to the model  : {}", refusals);
        LOG.info("CHATTEST what the stand-in player heard  : {}", lines);
        this.check("the bot took several turns (" + turns + ")", turns >= TURNS_TO_OBSERVE);
        this.check("it produced far fewer chat lines than turns (" + lines.size() + " line(s) from "
                + turns + " turns)", lines.size() < turns / 2);
        this.check("the refusals reached the model as tool results (" + refusals + ")", refusals >= 5);
    }

    /** Speak to the bot while it is inside the cooldown its own narration put it in. */
    private void askQuestion() {
        BotManager manager = Agent.botManager();
        BotManager.BotHandle handle = manager == null ? null : manager.get(LISTENER);
        if (handle == null) {
            this.check("the stand-in player is available to speak", false);
            this.finish();
            return;
        }
        ServerPlayer speaker = handle.player();
        this.questionAskedAtTick = this.ticks;
        this.quietTicksAtQuestion = this.ticks - this.lastLineTick;
        LOG.info("CHATTEST <{}> {}", LISTENER, QUESTION);
        LOG.info("CHATTEST the bot last spoke {} tick(s) ago, so it is deep inside its {} tick "
                + "cooldown", this.quietTicksAtQuestion, 2400);
        // Through the real event, exactly as a client's chat packet would arrive.
        NeoForge.EVENT_BUS.post(new ServerChatEvent(speaker, QUESTION, Component.literal(QUESTION)));
        LOG.info("CHATTEST after the question: shouldRespondPromptly={} unheardDirected={}",
                ChatLog.shouldRespondPromptly(bot()), ChatLog.unheardDirected(bot()).size());
    }

    private void report() {
        List<String> lines = linesHeard();
        int repeats = this.model.maxOccurrences("word for word");

        LOG.info("CHATTEST ================ ANSWER TO A PLAYER ================");
        LOG.info("CHATTEST question at tick {}, the bot had last spoken {} tick(s) earlier (cooldown "
                + "is 2400 ticks)", this.questionAskedAtTick, this.quietTicksAtQuestion);
        LOG.info("CHATTEST answer heard after {} tick(s): \"{}\"",
                this.answerSeenAtTick < 0 ? -1 : this.answerSeenAtTick - this.questionAskedAtTick,
                ANSWER);
        LOG.info("CHATTEST repeat-of-its-own-line refusals after that: {}", repeats);
        LOG.info("CHATTEST every line heard in the run: {}", lines);
        this.check("the question arrived while the bot was still inside its cooldown ("
                + this.quietTicksAtQuestion + " tick(s) since its last line)", this.quietTicksAtQuestion < 2400);
        this.check("the answer went out anyway - being addressed is never throttled",
                this.answerSeenAtTick >= 0);
        this.check("the answer was prompt ("
                + (this.answerSeenAtTick < 0 ? "never" : (this.answerSeenAtTick - this.questionAskedAtTick) + " ticks")
                + ")", this.answerSeenAtTick >= 0 && this.answerSeenAtTick - this.questionAskedAtTick <= 40);
        this.check("the bot answered even though the model asks to talk on every single turn",
                lines.contains(ANSWER));
        this.check("repeating the last line word for word was refused (" + repeats + ")", repeats >= 1);

        LOG.info("CHATTEST ================ RESULT ================");
        LOG.info("CHATTEST turns: {}, chat lines: {}", turns(), lines.size());
        LOG.info("CHATTEST VERDICT : {}", this.failed ? "FAIL" : "PASS");
        LOG.info("CHATTEST ======================================");
        this.finish();
    }

    private int turns() {
        var brain = brain();
        return brain == null ? 0 : brain.turnsCompleted();
    }

    private com.melody.mcagent.rt.brain.AgentBrain brain() {
        var brains = Agent.brainManager();
        var bots = Agent.botManager();
        if (brains == null || bots == null) {
            return null;
        }
        BotManager.BotHandle handle = bots.get(BOT);
        return handle == null ? null : brains.get(handle.player().getUUID());
    }

    private ServerPlayer bot() {
        BotManager manager = Agent.botManager();
        BotManager.BotHandle handle = manager == null ? null : manager.get(BOT);
        return handle == null ? null : handle.player();
    }

    /** Everything the bot has said, as the stand-in player standing next to it heard it. */
    private List<String> linesHeard() {
        BotManager manager = Agent.botManager();
        BotManager.BotHandle handle = manager == null ? null : manager.get(LISTENER);
        if (handle == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (ChatLog.Heard heard : ChatLog.recent(handle.player(), 100)) {
            if (heard.speaker().equals(BOT)) {
                lines.add(heard.text());
            }
        }
        return lines;
    }

    /** True (and reported as a failure) when a phase has run past its patience. */
    private boolean timedOut(int limit, String what) {
        if (this.ticks - this.phaseStartTick <= limit) {
            return false;
        }
        LOG.error("CHATTEST: FAIL - {}", what);
        this.check(what, false);
        this.report();
        return true;
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            this.failed = true;
        }
        LOG.info("CHATTEST {} : {}", ok ? "PASS" : "FAIL", what);
    }

    private void finish() {
        this.finished = true;
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        if (this.model != null) {
            try {
                this.model.close();
            } catch (Throwable ignored) {
                // Best effort; the process is about to stop anyway.
            }
        }
        LOG.info("CHATTEST: done, stopping server");
        this.server.halt(false);
    }
}
