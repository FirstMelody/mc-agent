package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.Config;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.ChatLog;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end proof of the speech gate: Jev decides whether a chat line is worth a model turn at all.
 *
 * <p>This is the failure it exists for - one instruction, three answers:
 * <pre>
 *   &lt;FirstMelody&gt; agent 我让你去挖钻石这些高级矿物 必要时可以丢掉铜
 *   &lt;Agent&gt; 收到，先把背包清一下就去挖钻石
 *   &lt;Agent&gt; 收到，先把包里的石头泥土清一清，就下矿挖钻石
 *   &lt;Agent&gt; 收到,我正从营地下面的竖井继续往下挖,到深岩层就找钻石
 * </pre>
 * Every chat-triggered turn asks a 12k-token model "should I answer?", and the model keeps saying
 * yes. A stub System One endpoint is pointed at the bot with {@code speechGate=active}, and the test
 * checks that a confident STAY_SILENT really does suppress the model call, that a directly addressed
 * question is never swallowed, that {@code speechGate=shadow} only observes, and that the
 * deterministic repetition guard refuses a line the bot already sent.
 */
public final class JevSpeechGateSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/speechgatetest");
    private static final String BOT = "GateBot";
    private static final String SPEAKER = "GateSpeaker";
    private static final String INSTRUCTION = "GateBot 我让你去挖钻石这些高级矿物 必要时可以丢掉铜";
    /** The exact message production hit at 23:34 - a question that does not name the bot. */
    private static final String QUESTION = "继续挖钻石 你现在有多少钻石了";
    private static final String QUESTION_NAMED = "GateBot 你现在挖到哪了？";
    private static final String CHATTER = "GateBot 这附近的地形真奇怪";
    private static final String REPEAT_LINE = "收到，这就下矿挖钻石";
    /**
     * The exact lines production sent, one per unprompted planning turn, in a two-hour session where
     * a player had asked only for diamonds. All six say "still working, I will tell you when I find
     * some" in different words, and none of them matched the old narration phrase list.
     */
    private static final String[] PRODUCTION_NARRATION = {
        "下面挖到一片水洞，绕个方向继续往下走，挖到钻石立刻喊你",
        "到现在0颗，全是石头。刚才那条洞通到水洞了，我重开一条往下打，挖到就喊你",
        "行，我在往下挖，挖到就给你留着，一组太多得慢慢来",
        "在的，刚回到地面上重新找路下去，挖到钻石先给你留着",
    };
    /** The opposite case: a finished task is worth a line, and the guard must let it through. */
    private static final String COMPLETION_REPORT = "钻石挖到了，一组放进你基地的箱子里了";
    /** How long after a chat line a model call proves the gate did not suppress it. */
    private static final int SUPPRESSION_WINDOW_TICKS = 30;

    private final MinecraftServer server;
    private final Deque<String> llmScript = new ArrayDeque<>();
    private ScriptedLlmServer model;
    private StubSystemOne jev;
    private String[] savedLlmSettings;
    private Path configPath;
    private String originalConfig;
    private boolean started;
    private boolean finished;
    private int phase;
    private int phaseTick;
    private int ticks;
    private int jevCallsBeforePhase;
    private boolean earlyModelCall;
    private int breakerMessages;
    private boolean chatPhaseStarted;
    private boolean duplicateStarted;
    private boolean lowConfStarted;
    private int spokenBeforePhase;
    private int llmCallsBeforePhase;
    private final List<String> failures = new ArrayList<>();

    private JevSpeechGateSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_SPEECH_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("SPEECHTEST: armed");
        return new JevSpeechGateSmokeTest(server);
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
        if (this.ticks - this.phaseTick > 400) {
            this.finish("phase " + this.phase + " timed out");
            return;
        }
        if (Boolean.TRUE.equals(brain.debugState().get("thinking"))) {
            return;
        }
        switch (this.phase) {
            case 0 -> this.phaseInstruction();
            case 1 -> this.checkInstructionSuppressed();
            case 2 -> this.checkQuestionBypassed();
            case 3 -> this.checkShadowObservesOnly();
            case 4 -> this.checkRepetitionGuard();
            case 5 -> this.checkBreaker();
            case 6 -> this.checkChatProtocol();
            case 7 -> this.checkDuplicateRule();
            case 8 -> this.checkLowConfidenceSilence();
            case 9 -> this.checkNarrationGuard();
            default -> this.finish(null);
        }
    }

    /**
     * Wait for the gate's answer to have been applied.
     *
     * <p>The gate is answered on another thread and applied back on the server thread, and the brain
     * only picks the message up on its own tick, so an assertion made the instant a turn ends would
     * race it. Sixty ticks is far longer than a local stub needs, and the phase cap still catches a
     * gate that is never consulted at all.
     */
    private boolean settled() {
        return this.ticks - this.phaseTick > 60;
    }

    /** Phase 0: gate active, an instruction that is not a question. */
    private void phaseInstruction() {
        this.jevCallsBeforePhase = this.jev == null ? 0 : this.jev.calls();
        this.llmCallsBeforePhase = this.model.requestCount();
        this.say(SPEAKER, INSTRUCTION);
        this.nextPhase("instruction posted with speechGate=active");
    }

    /**
     * Phase 1: a confident STAY_SILENT must suppress the model turn entirely.
     *
     * <p>The measurement is the model-call count inside a short window after the message, not
     * "no call ever": after staying silent the bot goes on with its own work and legitimately
     * decides again a couple of seconds later. What the gate saves is the turn for that message.
     */
    private void checkInstructionSuppressed() {
        boolean modelled = this.model.requestCount() > this.llmCallsBeforePhase;
        if (modelled && this.ticks - this.phaseTick <= SUPPRESSION_WINDOW_TICKS) {
            this.earlyModelCall = true;
        }
        if (!this.settled()) {
            return;
        }
        boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
        boolean spoke = this.botSpoke();
        LOG.info("SPEECHTEST instruction: gateAsked={} modelTurnWithin{}Ticks={} botSpoke={} "
                + "(active gate must suppress the turn for this message)", asked,
                SUPPRESSION_WINDOW_TICKS, this.earlyModelCall, spoke);
        if (!asked) {
            this.fail("the gate was never consulted for a new non-question instruction");
        }
        if (this.earlyModelCall) {
            this.fail("the model was called anyway, so an active STAY_SILENT saved nothing");
        }
        if (spoke) {
            this.fail("the bot spoke after the gate said STAY_SILENT");
        }
        this.jevCallsBeforePhase = this.jev.calls();
        this.llmCallsBeforePhase = this.model.requestCount();
        this.say(SPEAKER, QUESTION);
        this.say(SPEAKER, QUESTION_NAMED);
        this.nextPhase("an unnamed question and a named one posted");
    }

    /** Phase 2: a question that names the bot is never filtered by the gate. */
    private void checkQuestionBypassed() {
        if (!this.settled()) {
            return;
        }
        boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
        boolean modelled = this.model.requestCount() > this.llmCallsBeforePhase;
        LOG.info("SPEECHTEST question   : gateAsked={} modelTurn={}", asked, modelled);
        if (asked) {
            this.fail("a question was sent to the gate instead of the model - naming the bot or not "
                    + "must not matter");
        }
        if (!modelled) {
            this.fail("a question produced no model turn");
        }
        this.setSpeechGate("shadow");
        this.jevCallsBeforePhase = this.jev.calls();
        this.llmCallsBeforePhase = this.model.requestCount();
        this.say(SPEAKER, CHATTER);
        this.nextPhase("chatter posted with speechGate=shadow");
    }

    /** Phase 3: shadow mode observes and logs, but must not suppress anything. */
    private void checkShadowObservesOnly() {
        if (!this.settled()) {
            return;
        }
        boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
        boolean modelled = this.model.requestCount() > this.llmCallsBeforePhase;
        LOG.info("SPEECHTEST shadow     : gateAsked={} modelTurn={}", asked, modelled);
        if (!asked) {
            this.fail("shadow mode did not consult the gate");
        }
        if (!modelled) {
            this.fail("shadow mode suppressed a model turn");
        }
        // The deterministic half of the fix, with the gate out of the way: the same line twice.
        this.setSpeechGate("off");
        synchronized (this.llmScript) {
            this.llmScript.clear();
            this.llmScript.add(ScriptedLlmServer.toolCall("dup_one", "say",
                    "{\"message\":\"" + REPEAT_LINE + "\"}"));
            this.llmScript.add(ScriptedLlmServer.toolCall("dup_two", "say",
                    "{\"message\":\"" + REPEAT_LINE + "\"}"));
        }
        this.say(SPEAKER, "GateBot 出发吧");
        this.nextPhase("repetition guard: the same line twice");
    }

    /** Phase 4: the second identical line must be refused by the guard, not by luck. */
    private void checkRepetitionGuard() {
        if (this.jevCallsBeforePhase == 0) {
            this.jevCallsBeforePhase = this.jev.calls();
        }
        // Two guards can refuse a repeat: the say tool's own "word for word" check, and the
        // runtime's repetition window. Either counts; what matters is that only one copy reached
        // chat, which is the assertion below.
        boolean refused = this.model.maxOccurrences("word for word") >= 1
                || this.model.maxOccurrences("you already said this") >= 1;
        if (!refused) {
            return;
        }
        List<String> lines = this.spokenLines();
        LOG.info("SPEECHTEST repeat     : refused={} linesHeard={}", true, lines);
        if (lines.size() != 1) {
            this.fail("expected exactly one copy of the repeated line, saw " + lines);
        }
        // Phase 5: the endpoint starts refusing. After three refusals in a row the client must stop
        // calling it, or the bot spends the quota - and the log - on nothing.
        this.setSpeechGate("active");
        this.jev.refuseWith429 = true;
        this.jevCallsBeforePhase = this.jev.calls();
        this.nextPhase("rate-limited endpoint: the gate must back off");
    }

    /**
     * Phase 5: three refusals in a row open the breaker, so the fourth message is not even sent.
     *
     * <p>The production endpoint refused 480 of 735 calls in two hours, every one of them a fresh
     * request to an endpoint that had already said no. The assertion is therefore about the count:
     * three attempts for four messages, not four.
     */
    private void checkBreaker() {
        if (this.breakerMessages < 4
                && this.jev.calls() - this.jevCallsBeforePhase >= this.breakerMessages
                && this.ticks - this.phaseTick > 25 * this.breakerMessages) {
            this.breakerMessages++;
            this.say(SPEAKER, CHATTER + " number " + this.breakerMessages);
            return;
        }
        if (this.breakerMessages < 4 || this.ticks - this.phaseTick < 25 * 4 + 40) {
            return;
        }
        int attempts = this.jev.calls() - this.jevCallsBeforePhase;
        LOG.info("SPEECHTEST breaker    : messages={} attempts={} (three 429s must stop the fourth "
                + "call)", this.breakerMessages, attempts);
        if (attempts != 3) {
            this.fail("expected exactly 3 attempts before the breaker opened, saw " + attempts);
        }
        this.jev.refuseWith429 = false;
        this.nextPhase("OpenAI-compatible protocol");
    }

    /**
     * Phase 6: the same decision over an OpenAI-compatible {@code /chat/completions} endpoint.
     *
     * <p>This is the path Vercel AI Gateway and any local proxy need; the typed-choice envelope is
     * OpenCode Zen's alone. Suppression must work identically, which proves the JSON contract is
     * carried and parsed rather than silently ignored.
     */
    private void checkChatProtocol() {
        if (this.chatPhaseStarted) {
            boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
            boolean modelled = this.model.requestCount() > this.llmCallsBeforePhase;
            // Lines heard since this phase began: an earlier phase's line is still in the log.
            boolean spoke = this.spokenLines().size() > this.spokenBeforePhase;
            LOG.info("SPEECHTEST chat proto : gateAsked={} modelTurn={} botSpoke={} (a confident "
                    + "STAY_SILENT over chat/completions must suppress the turn)", asked, modelled,
                    spoke);
            if (!asked) {
                this.fail("the gate was not consulted over the chat protocol");
            }
            if (modelled || spoke) {
                this.fail("the chat-protocol answer did not suppress the turn");
            }
            this.nextPhase("the same line twice in a row");
            return;
        }
        this.chatPhaseStarted = true;
        this.jevCallsBeforePhase = this.jev.calls();
        this.llmCallsBeforePhase = this.model.requestCount();
        this.spokenBeforePhase = this.spokenLines().size();
        try {
            this.writeJevConfig(true, "active", "chat");
        } catch (IOException e) {
            this.fail("could not point the gate at the chat endpoint: " + e.getMessage());
            return;
        }
        this.say(SPEAKER, CHATTER + " over chat completions");
    }

    /**
     * Phase 7: a verbatim repeat is answered with silence without asking Jev at all.
     *
     * <p>This is the production failure in its purest form: one instruction sent three times produced
     * three acknowledgements. The rule is deterministic - no model call, no token spend - and it must
     * also cost no planning turn.
     */
    private void checkDuplicateRule() {
        if (this.duplicateStarted) {
            boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
            boolean modelled = this.model.requestCount() > this.llmCallsBeforePhase;
            boolean spoke = this.spokenLines().size() > this.spokenBeforePhase;
            LOG.info("SPEECHTEST duplicate  : gateAsked={} modelTurn={} botSpoke={} (a verbatim repeat "
                    + "needs neither)", asked, modelled, spoke);
            if (asked) {
                this.fail("a verbatim repeat was sent to Jev instead of being decided locally");
            }
            if (modelled || spoke) {
                this.fail("a verbatim repeat still cost a turn or produced another acknowledgement");
            }
            this.nextPhase("a low-confidence STAY_SILENT");
            return;
        }
        this.duplicateStarted = true;
        this.jevCallsBeforePhase = this.jev.calls();
        this.llmCallsBeforePhase = this.model.requestCount();
        this.spokenBeforePhase = this.spokenLines().size();
        String line = "继续挖钻石 多挖点钻石";
        this.say(SPEAKER, line);
        this.say(SPEAKER, line);
    }

    /**
     * Phase 8: production's own answer must now suppress the turn.
     *
     * <p>The gate answered STAY_SILENT at 0.14 on "去挖一组钻石给我呗" and at 0.02 on a repeated
     * instruction; the 0.85 floor discarded both, the planner was called, and it answered anyway.
     * That is the chatter this phase pins down: a non-question message with a low-confidence
     * STAY_SILENT must cost neither a model turn nor a chat line. The control for it is phase 3,
     * where the same stub in shadow mode must not suppress anything.
     */
    private void checkLowConfidenceSilence() {
        if (!this.lowConfStarted) {
            this.lowConfStarted = true;
            // Back to the protocol production uses, and to the answer production got: STAY_SILENT at
            // 0.14 with the distribution barely favouring silence.
            this.setSpeechGate("active");
            this.jev.choice = "STAY_SILENT";
            this.jev.confidence = 0.14D;
            this.jevCallsBeforePhase = this.jev.calls();
            this.llmCallsBeforePhase = this.model.requestCount();
            this.spokenBeforePhase = this.spokenLines().size();
            this.earlyModelCall = false;
            this.phaseTick = this.ticks;
            this.say(SPEAKER, "去挖一组钻石给我呗");
            return;
        }
        // The measurement is the same one phase 1 uses: a model call inside the suppression window,
        // not "no call ever". After staying silent the bot legitimately decides again a second later,
        // and that ordinary idle turn is not an answer to this message.
        if (this.model.requestCount() > this.llmCallsBeforePhase
                && this.ticks - this.phaseTick <= SUPPRESSION_WINDOW_TICKS) {
            this.earlyModelCall = true;
        }
        if (!this.settled()) {
            return;
        }
        boolean asked = this.jev.calls() > this.jevCallsBeforePhase;
        boolean spoke = this.spokenLines().size() > this.spokenBeforePhase;
        LOG.info("SPEECHTEST low-conf   : gateAsked={} modelTurnWithin{}Ticks={} botSpoke={} "
                + "(STAY_SILENT at 0.14 must still suppress; a question never reaches the gate)",
                asked, SUPPRESSION_WINDOW_TICKS, this.earlyModelCall, spoke);
        if (!asked) {
            this.fail("the gate was not consulted for the low-confidence case");
        }
        if (this.earlyModelCall || spoke) {
            this.fail("a low-confidence STAY_SILENT was discarded and the bot answered anyway - this "
                    + "is the production failure");
        }
        this.nextPhase("progress narration from an unprompted turn");
    }

    /**
     * Phase 9: the lines production actually sent must be refused as narration.
     *
     * <p>Asserted through {@code sayAsTool} rather than through the model, because this is a policy
     * and a policy observed only through a model cannot be verified without the model's own
     * behaviour getting in the way. The last line is the control: a finished task is exactly what
     * the bot should be allowed to report, so the guard must not swallow it.
     */
    private void checkNarrationGuard() {
        AgentBrain brain = this.brain();
        if (brain == null) {
            this.fail("no brain for the narration guard");
            return;
        }
        boolean caughtAll = true;
        for (String line : PRODUCTION_NARRATION) {
            // The lines arrived minutes apart in production; the quiet period must not be allowed to
            // mask the narration rule here, or the control run would pass for the wrong reason.
            brain.clearQuietPeriodForTest();
            String result = brain.sayAsTool(line);
            boolean caught = result.contains("routine progress narration");
            LOG.info("SPEECHTEST narration  : \"{}\" -> {}", line, result);
            if (!caught) {
                caughtAll = false;
                this.fail("production narration was not refused: \"" + line + "\" -> " + result);
            }
        }
        int heardBefore = this.spokenLines().size();
        brain.clearQuietPeriodForTest();
        String completion = brain.sayAsTool(COMPLETION_REPORT);
        boolean swallowed = completion.contains("routine progress narration");
        boolean sent = this.spokenLines().size() > heardBefore;
        LOG.info("SPEECHTEST completion : \"{}\" -> {} (a finished task must not be read as "
                + "narration, and must not wait out the quiet period; sent={})",
                COMPLETION_REPORT, completion, sent);
        if (swallowed) {
            this.fail("a completed-task report was refused as narration");
        }
        if (!sent) {
            this.fail("a completed-task report was not sent: " + completion);
        }
        if (caughtAll) {
            this.nextPhase("done");
        } else {
            this.nextPhase("done");
        }
    }

    private void begin() {
        try {
            // One scripted planning model, and one stub System One endpoint whose answer the test
            // controls. The gate is the only thing between a chat line and a model turn.
            this.model = new ScriptedLlmServer(body -> {
                synchronized (this.llmScript) {
                    return this.llmScript.isEmpty()
                            ? ScriptedLlmServer.silent() : this.llmScript.pollFirst();
                }
            });
            this.savedLlmSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            this.jev = new StubSystemOne();
            this.writeJevConfig(true, "active");
        } catch (IOException e) {
            this.finish("could not start stubs: " + e.getMessage());
            return;
        }

        ServerLevel level = this.server.overworld();
        Vec3 at = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        BotManager.BotHandle bot = Agent.botManager().spawn(BOT, level, at, true);
        BotManager.BotHandle speaker = Agent.botManager().spawn(SPEAKER, level, at.add(2, 0, 0), true);
        if (bot == null || speaker == null) {
            this.finish("could not spawn bots");
            return;
        }
        ChatLog.clear(speaker.player().getUUID());
        ChatLog.clear(bot.player().getUUID());
        if (!Agent.attachBrain(bot.player())) {
            this.finish("could not attach brain");
        }
        this.phaseTick = this.ticks;
    }

    /**
     * Point the runtime-only Jev config at the stub, keeping the original file to put back.
     *
     * <p>{@code shadowMode} stays true throughout: the mining recovery is a physical decision and is
     * not what this test is about.
     */
    private void writeJevConfig(boolean enabled, String gate) throws IOException {
        this.writeJevConfig(enabled, gate, "systemone");
    }

    private void writeJevConfig(boolean enabled, String gate, String protocol) throws IOException {
        this.configPath = Path.of("config", "mcagent-jev.properties");
        if (this.originalConfig == null && Files.isRegularFile(this.configPath)) {
            this.originalConfig = Files.readString(this.configPath, StandardCharsets.UTF_8);
        }
        String body = "# Written by JevSpeechGateSmokeTest; restored when the test ends.\n"
                + "enabled=" + enabled + "\n"
                + "protocol=" + protocol + "\n"
                + "endpoint=" + ("chat".equals(protocol) ? this.jev.chatEndpoint() : this.jev.endpoint())
                + "\n"
                + "apiKey=stub\n"
                + "model=stub-model\n"
                + "timeoutMillis=2000\n"
                + "maxTokens=800\n"
                + "shadowMode=true\n"
                + "speechGate=" + gate + "\n";
        Files.createDirectories(this.configPath.getParent());
        Files.writeString(this.configPath, body, StandardCharsets.UTF_8);
        Config.apply();
    }

    private void setSpeechGate(String gate) {
        try {
            this.writeJevConfig(true, gate);
        } catch (IOException e) {
            this.fail("could not rewrite the Jev config: " + e.getMessage());
        }
    }

    private void say(String speakerName, String text) {
        BotManager.BotHandle speaker = Agent.botManager().get(speakerName);
        if (speaker == null) {
            this.fail("speaker " + speakerName + " disappeared");
            return;
        }
        NeoForge.EVENT_BUS.post(new ServerChatEvent(speaker.player(), text, Component.literal(text)));
    }

    private AgentBrain brain() {
        BotManager.BotHandle handle = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        return handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private boolean botSpoke() {
        return !this.spokenLines().isEmpty();
    }

    private List<String> spokenLines() {
        BotManager.BotHandle speaker = Agent.botManager() == null ? null
                : Agent.botManager().get(SPEAKER);
        if (speaker == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (ChatLog.Heard heard : ChatLog.recent(speaker.player(), 40)) {
            if (BOT.equals(heard.speaker())) {
                lines.add(heard.text());
            }
        }
        return lines;
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        LOG.info("SPEECHTEST phase {} : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures.add(why);
        LOG.warn("SPEECHTEST problem  : {}", why);
    }

    private void finish(String abort) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        boolean pass = abort == null && this.failures.isEmpty();
        LOG.info("SPEECHTEST gate calls: jev={} (a suppressed turn means the count did not grow)",
                this.jev == null ? -1 : this.jev.calls());
        for (String failure : this.failures) {
            LOG.info("SPEECHTEST failure   : {}", failure);
        }
        LOG.info("SPEECHTEST result    : {}", abort == null ? "completed" : abort);
        if ("off".equalsIgnoreCase(System.getenv("MCAGENT_NARRATION_GUARD"))) {
            // The control run: with the narration rule disabled the production lines must go out, so
            // a failure here is the expected outcome and proves the assertions can see the chatter.
            LOG.info("SPEECHTEST VERDICT   : {}", pass
                    ? "CONTROL-FAIL (narration guard disabled but the lines were still refused; these "
                            + "assertions cannot detect the chatter)"
                    : "CONTROL-PASS (narration guard disabled and the production narration lines "
                            + "were sent again, as expected)");
        } else {
            LOG.info("SPEECHTEST VERDICT   : {}", pass ? "PASS" : "FAIL");
        }

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
            Agent.botManager().remove(SPEAKER);
        }
        try {
            if (this.originalConfig != null) {
                Files.writeString(this.configPath, this.originalConfig, StandardCharsets.UTF_8);
            } else if (this.configPath != null && Files.isRegularFile(this.configPath)) {
                Files.delete(this.configPath);
            }
            Config.apply();
        } catch (IOException e) {
            LOG.warn("SPEECHTEST could not restore the Jev config: {}", e.toString());
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

    /**
     * A stub System One endpoint: it counts calls, remembers what it was asked, and always answers
     * STAY_SILENT with high confidence - the answer that must suppress a model turn when the gate is
     * active, and must not when it is in shadow.
     */
    private static final class StubSystemOne {

        private final HttpServer http;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> bodies = new ArrayList<>();
        /** When true every call is refused the way the production endpoint refused 480 of 735. */
        private volatile boolean refuseWith429;
        /**
         * What the stub answers, so the test can replay the production answer that broke the gate:
         * STAY_SILENT at 0.14 with the distribution barely favouring silence.
         */
        private volatile String choice = "STAY_SILENT";
        private volatile double confidence = 0.95;

        StubSystemOne() throws IOException {
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/v1/chat/completions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                synchronized (this.bodies) {
                    this.bodies.add(body);
                }
                this.calls.incrementAndGet();
                if (this.refuseWith429) {
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                    return;
                }
                // The chat envelope: the decision travels in the message content as JSON.
                String answer = "{\"choice\":\"" + this.choice + "\",\"confidence\":"
                        + this.confidence + "}";
                String payload = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"" + answer.replace("\"", "\\\"") + "\"}}]}";
                byte[] response = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            this.http.createContext("/systemone", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                synchronized (this.bodies) {
                    this.bodies.add(body);
                }
                this.calls.incrementAndGet();
                if (this.refuseWith429) {
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                    return;
                }
                double speak = 1.0D - this.confidence;
                byte[] response = ("{\"answers\":{\"speech_gate\":{\"choice\":\"" + this.choice
                        + "\",\"confidence\":" + this.confidence + ",\"probabilities\":{"
                        + "\"STAY_SILENT\":" + this.confidence + ",\"SPEAK\":" + speak
                        + "}}}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            this.http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcagent-stub-systemone");
                thread.setDaemon(true);
                return thread;
            }));
            this.http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort() + "/systemone";
        }

        /** The same stub seen through the OpenAI-compatible path. */
        String chatEndpoint() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort()
                    + "/v1/chat/completions";
        }

        int calls() {
            return this.calls.get();
        }

        @SuppressWarnings("unused")
        List<String> bodies() {
            synchronized (this.bodies) {
                return List.copyOf(this.bodies);
            }
        }

        void close() {
            this.http.stop(0);
        }
    }
}
