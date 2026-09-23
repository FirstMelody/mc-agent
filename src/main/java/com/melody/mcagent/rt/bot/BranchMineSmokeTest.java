package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One planning call starts a branch-mining trip; after that the trip must cost no model at all.
 *
 * <p>This is the shape the whole branch-mining skill exists for: the model's entire contribution is
 * "dig a fishbone at this level for these ores", and everything afterwards - the corridor, the
 * branches, spotting ore through the rock, the return trip, and the two interruptions (a worn tool, a
 * mob, a full pack) - belongs to the runtime. The one exception the design allows is Jev: a bounded
 * typed question when the trip is disturbed, and only if even that cannot answer does the trip go back
 * to the planning model, exactly once.
 *
 * <p>Enabled only with {@code MCAGENT_BRANCHMINE_TEST=true}.
 *
 * <p>Four phases, each with its own assertion:
 * <ol>
 *   <li>the first scripted reply is {@code start_mining(mode=branch)}; the trip starts</li>
 *   <li>the pattern runs: branches are dug and ore buried outside the tunnels is found through the
 *       rock and mined, with the model's request count still at one</li>
 *   <li>every pickaxe is worn to a single use: Jev is asked about it, answers KEEP_MINING, and the
 *       trip carries on - still one request</li>
 *   <li>the same interruption with Jev answering ESCALATE_LLM: the trip is handed back and the model
 *       is asked exactly once more</li>
 * </ol>
 */
public final class BranchMineSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/branchtest");
    private static final String BOT = "BranchBot";
    /** Long enough for a corridor, a branch, an ore detour and a return trip. */
    private static final int PHASE_CAP_TICKS = 3600;

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedLlmSettings;
    private StubSystemOne jev;
    private Path configPath;
    private String originalConfig;

    private ServerLevel level;
    private BlockPos origin;
    private BlockPos buriedOre;
    private boolean started;
    private boolean finished;
    private boolean nudged;
    private int phase;
    private int phaseTick;
    private boolean phaseStarted;
    private int ticks;
    private int requestsAtMiningStart = -1;
    /** Peaks seen while the trip ran: its counters are gone once it finishes. */
    private int peakBranches;
    private int peakMain;
    private int peakOre;
    private int peakIron;
    private int peakBridges;
    private int peakMainSecondTrip;
    private int requestsAtReentry = -1;
    private final List<String> failures = new java.util.ArrayList<>();

    private BranchMineSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_BRANCHMINE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("BRANCHTEST: armed");
        return new BranchMineSmokeTest(server);
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
        BotManager.BotHandle handle = Agent.botManager() == null ? null
                : Agent.botManager().get(BOT);
        if (handle == null) {
            this.finish("the bot disappeared");
            return;
        }
        // A bot with no standing goal waits for an event instead of buying a turn, which is the
        // production policy: a harness bot has to be given a decision the way an operator does.
        if (!this.nudged && this.ticks > 20) {
            this.nudged = true;
            TestHook.nudge(handle.player());
        }
        if (this.ticks - this.phaseTick > PHASE_CAP_TICKS) {
            this.finish("phase " + this.phase + " timed out");
            return;
        }
        switch (this.phase) {
            case 0 -> this.phaseStartTrip(handle);
            case 1 -> this.phasePatternRuns(handle);
            case 2 -> this.phaseEscalate(handle);
            case 3 -> this.phaseReentry(handle);
            default -> this.finish(null);
        }
    }

    /** Phase 0: the single planning call that starts the trip, and a pickaxe with one use left. */
    private void phaseStartTrip(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            return;
        }
        if (this.model.requestCount() < 1) {
            return;
        }
        // The request is asynchronous: the tool call is applied a few ticks after it is counted, so
        // wait for the trip rather than reading the state on the same tick the request appears.
        boolean branchMining = Boolean.TRUE.equals(this.state().get("branchMining"));
        if (!branchMining) {
            if (this.ticks - this.phaseTick > 200) {
                this.fail("the planning call did not start a branch-mining trip");
                this.nextPhase("done");
            }
            return;
        }
        LOG.info("BRANCHTEST start      : requests={} branchMining={}",
                this.model.requestCount(), branchMining);
        this.requestsAtMiningStart = this.model.requestCount();
        // Leave every pickaxe fifteen uses: below the "worn" floor, but enough to survive the run
        // that is already queued, so the interruption reaches Jev between runs instead of the tool
        // simply breaking and the bot quietly switching to a worse one.
        int worn = 0;
        for (int slot = 0; slot < handle.player().getInventory().getContainerSize(); slot++) {
            ItemStack stack = handle.player().getInventory().getItem(slot);
            // Only the stone pickaxe, which is the one the runtime reaches for on stone: the diamond
            // one stays whole so the trip still digs at full speed once the worn tool breaks.
            if (!stack.isEmpty() && stack.isDamageableItem() && stack.is(Items.STONE_PICKAXE)) {
                stack.setDamageValue(Math.max(0, stack.getMaxDamage() - 15));
                worn++;
            }
        }
        this.jev.answer("mining_interrupt", "KEEP_MINING", 0.95D);
        LOG.info("BRANCHTEST worn tool  : {} stone pickaxe(s) left with 15 uses; stub answers "
                + "KEEP_MINING", worn);
        this.nextPhase("the pattern digs, finds buried ore, and never asks the model");
    }

    /** Phase 1: corridor, branches and an ore detour, with the request count frozen. */
    private void phasePatternRuns(BotManager.BotHandle handle) {
        Map<String, Object> state = this.state();
        int branches = number(state.get("branchBranchesDug"));
        int main = number(state.get("branchMainBlocks"));
        int ore = number(state.get("branchOreJobs"));
        int iron = handle.player().getInventory().countItem(Items.RAW_IRON)
                + handle.player().getInventory().countItem(Items.IRON_ORE);
        // Sample while the trip is alive: branchBranchesDug and its neighbours disappear with the job,
        // so reading them after the trip has returned home reports a trip that dug nothing.
        this.peakBranches = Math.max(this.peakBranches, branches);
        this.peakMain = Math.max(this.peakMain, main);
        this.peakOre = Math.max(this.peakOre, ore);
        this.peakIron = Math.max(this.peakIron, iron);
        this.peakBridges = Math.max(this.peakBridges, number(state.get("branchBridges")));
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            LOG.info("BRANCHTEST pattern    : waiting for branches/ore; buried ore at {}",
                    this.buriedOre.toShortString());
        }
        if (this.peakBranches >= 1 && this.peakMain >= 2 && this.peakOre >= 1
                && this.peakIron >= 1 && this.peakBridges >= 1) {
            branches = this.peakBranches;
            main = this.peakMain;
            ore = this.peakOre;
            iron = this.peakIron;
            int requests = this.model.requestCount() - this.requestsAtMiningStart;
            int asked = number(state.get("branchInterruptsAsked"));
            int applied = number(state.get("branchInterruptsApplied"));
            LOG.info("BRANCHTEST pattern    : branches={} mainBlocks={} oreJobs={} iron={} bridges={} "
                            + "extraRequests={} jevAsked={} jevApplied={}",
                    branches, main, ore, iron, this.peakBridges, requests, asked, applied);
            if (requests != 0) {
                this.fail("the mining trip bought " + requests + " planning turn(s) after it started");
            }
            if (asked < 1 || applied < 1) {
                this.fail("a pickaxe with one use left was not handed to Jev (asked=" + asked
                        + ", applied=" + applied + ")");
            }
            this.nextPhase("Jev refuses to decide, so the trip goes back to the model exactly once");
            return;
        }
        if (this.ticks - this.phaseTick > 3000) {
            this.fail("the pattern made no progress: peak branches=" + this.peakBranches
                    + " mainBlocks=" + this.peakMain + " oreJobs=" + this.peakOre
                    + " iron=" + this.peakIron + " bridges=" + this.peakBridges);
            this.nextPhase("done");
        }
    }

    /** Phase 2: the fallback. One interruption Jev refuses to answer costs exactly one model turn. */
    private void phaseEscalate(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.jev.answer("mining_interrupt", "ESCALATE_LLM", 0.90D);
            this.requestsBefore = this.model.requestCount();
            // Wear the tools again: the first worn pickaxe has long since broken, and for the
            // escalation to be reached there has to be something for Jev to be asked about.
            int worn = 0;
            for (int slot = 0; slot < handle.player().getInventory().getContainerSize(); slot++) {
                ItemStack stack = handle.player().getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.isDamageableItem()
                        && stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
                    stack.setDamageValue(Math.max(0, stack.getMaxDamage() - 15));
                    worn++;
                }
            }
            LOG.info("BRANCHTEST escalate   : stub now answers ESCALATE_LLM, {} pickaxe(s) worn "
                    + "again", worn);
            return;
        }
        // Keep every pickaxe worn while this phase runs. A 15-use pickaxe breaks after a run or two,
        // and with it goes the interruption this phase is waiting for - the quiet period after Jev's
        // first answer is thirty seconds, which is longer than a pickaxe lasts.
        for (int slot = 0; slot < handle.player().getInventory().getContainerSize(); slot++) {
            ItemStack stack = handle.player().getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()
                    && stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
                stack.setDamageValue(Math.max(0, stack.getMaxDamage() - 15));
            }
        }
        // The quiet period after Jev's first answer has to expire before it is asked again; the
        // harness waits for the request rather than shortening production's behaviour for a test.
        if (this.model.requestCount() <= this.requestsBefore
                && this.ticks - this.phaseTick < 1500) {
            return;
        }
        if (this.model.requestCount() <= this.requestsBefore) {
            int asked = number(this.state().get("branchInterruptsAsked"));
            this.fail("Jev was never asked again after the quiet period (asked=" + asked
                    + "), so the escalation path was not reached");
            this.nextPhase("done");
            return;
        }
        int extra = this.model.requestCount() - this.requestsBefore;
        boolean handedBack = !Boolean.TRUE.equals(this.state().get("branchMining"));
        LOG.info("BRANCHTEST escalate   : extraRequests={} handedBack={}", extra, handedBack);
        if (extra != 1) {
            this.fail("the fallback cost " + extra + " planning turn(s), not exactly one");
        }
        if (!handedBack) {
            this.fail("the trip kept running after Jev escalated it to the model");
        }
        this.nextPhase("done");
    }

    /**
     * Phase 3: the trip the fallback turn started is a re-entry. The bot is deep in its own corridor,
     * with a working face behind it and a saved route on disk - the route production has watched drag
     * the bot back to a broken face at 861,26,418 and then home. This runner never consults it: it opens
     * a new path from where the bot stands. Asserted both ways: new main blocks, and no planning call.
     */
    private void phaseReentry(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.requestsAtReentry = this.model.requestCount();
            LOG.info("BRANCHTEST re-entry   : second trip requested at {}", 
                    handle.player().blockPosition().toShortString());
            return;
        }
        Map<String, Object> state = this.state();
        if (Boolean.TRUE.equals(state.get("branchMining"))) {
            this.peakMainSecondTrip = Math.max(this.peakMainSecondTrip,
                    number(state.get("branchMainBlocks")));
        }
        if (this.peakMainSecondTrip >= 1) {
            int extra = this.model.requestCount() - this.requestsAtReentry;
            LOG.info("BRANCHTEST re-entry   : second trip dug {} main block(s) from where it stood, "
                    + "extraRequests={}", this.peakMainSecondTrip, extra);
            if (extra != 0) {
                this.fail("re-entering the same corridor bought " + extra + " planning turn(s)");
            }
            this.nextPhase("done");
            return;
        }
        if (this.ticks - this.phaseTick > 1500) {
            this.fail("the second trip never dug from the old corridor: " + state);
            this.nextPhase("done");
        }
    }

    private int requestsBefore;

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() + 60;
        int z = spawn.getZ() - 60;
        int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Well under the surface, so the protected home band never enters this test.
        int y = Math.max(this.level.getMinBuildHeight() + 8, surface - 30);
        this.origin = new BlockPos(x, y, z);

        // Solid rock in a band wide enough for a corridor (east) with 4-block branches north/south,
        // and room around it for the buried ore the scan has to see through stone.
        for (int dx = -3; dx <= 30; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        // Open space right across the corridor's first run: two cells of floor missing, three deep.
        // The pattern has to pave them and carry on, which is the difference between "open space is a
        // reason to abandon the level" and "open space is one block to place".
        for (int dx = 2; dx <= 3; dx++) {
            for (int dy = -4; dy <= -1; dy++) {
                this.level.setBlockAndUpdate(this.origin.offset(dx, dy, 0),
                        Blocks.AIR.defaultBlockState());
            }
        }
        // Air for the bot to start in; the corridor is dug by the skill.
        this.level.setBlockAndUpdate(this.origin, Blocks.AIR.defaultBlockState());
        this.level.setBlockAndUpdate(this.origin.above(), Blocks.AIR.defaultBlockState());
        // Ore the tunnels never reach, just past where a 3-block branch ends: two blocks of stone
        // between the branch tip and the ore, which is inside the scan's five-block budget and
        // outside anything the pattern would dig. North is -z, so +z is the south branch.
        // Two blocks past the tip of the first branch (junctions are every three main blocks and the
        // branch is four long), so the ore sits behind exactly two blocks of stone.
        this.buriedOre = this.origin.offset(3, 0, 6);
        this.level.setBlockAndUpdate(this.buriedOre, Blocks.IRON_ORE.defaultBlockState());
        this.level.setBlockAndUpdate(this.buriedOre.above(), Blocks.STONE.defaultBlockState());
        this.level.setBlockAndUpdate(this.origin.offset(6, 0, 6), Blocks.IRON_ORE.defaultBlockState());

        try {
            AtomicInteger turns = new AtomicInteger();
            int targetY = this.origin.getY();
                        String startTrip = "{\"mode\":\"branch\",\"y\":" + targetY + ",\"direction\":\"east\","
                    + "\"primary\":\"iron_ore\",\"branch_spacing\":3,"
                    + "\"branch_length\":4,\"max_branches\":12,\"amount\":0}";
            // Turn 0 starts the trip. Turn 1 is the fallback the escalation phase triggers: a model with
            // the report in front of it would re-issue the trip, and that second trip is the re-entry
            // case - the bot is already deep inside its own corridor, with a working face behind it and a
            // route on disk that production has watched drag it back to a broken one. The runner must
            // open a new path from where it stands, and spend no planning call doing it.
            this.model = new ScriptedLlmServer(body -> switch (turns.getAndIncrement()) {
                case 0, 1 -> ScriptedLlmServer.toolCall("branch_trip", "start_mining", startTrip);
                default -> ScriptedLlmServer.silent();
            });
            this.savedLlmSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            this.jev = new StubSystemOne();
            this.writeJevConfig();
        } catch (IOException e) {
            this.finish("could not start the stubs: " + e.getMessage());
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(BOT, this.level,
                Vec3.atBottomCenterOf(this.origin), true);
        if (handle == null) {
            this.finish("could not spawn the bot");
            return;
        }
        if (!Agent.attachBrain(handle.player())) {
            this.finish("could not attach a brain to the bot");
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        handle.player().getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        handle.player().getInventory().add(new ItemStack(Items.BREAD, 16));
        handle.player().getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        LOG.info("BRANCHTEST scene      : bot at {} in stone, buried iron ore at {} ({} blocks off "
                        + "the branch line)", handle.player().blockPosition().toShortString(),
                this.buriedOre.toShortString(), 6 - 3);
        this.phaseTick = this.ticks;
    }

    /** Point the runtime-only Jev config at the stub, keeping the original to put back. */
    private void writeJevConfig() throws IOException {
        this.configPath = Path.of("config", "mcagent-jev.properties");
        if (this.originalConfig == null && Files.isRegularFile(this.configPath)) {
            this.originalConfig = Files.readString(this.configPath, StandardCharsets.UTF_8);
        }
        String body = "# Written by BranchMineSmokeTest; restored when the test ends.\n"
                + "enabled=true\n"
                + "protocol=systemone\n"
                + "endpoint=" + this.jev.endpoint() + "\n"
                + "apiKey=stub\n"
                + "model=stub-model\n"
                + "timeoutMillis=2000\n"
                + "maxTokens=800\n"
                + "shadowMode=false\n"
                + "speechGate=off\n"
                + "routing=off\n"
                + "interrupts=active\n";
        Files.createDirectories(this.configPath.getParent());
        Files.writeString(this.configPath, body, StandardCharsets.UTF_8);
        this.jev.answer("mining_interrupt", "KEEP_MINING", 0.95D);
        Config.apply();
    }

    private Map<String, Object> state() {
        AgentBrain brain = this.brain();
        return brain == null ? Map.of() : brain.debugState();
    }

    private AgentBrain brain() {
        if (Agent.brainManager() == null) {
            return null;
        }
        BotManager.BotHandle handle = Agent.botManager() == null ? null
                : Agent.botManager().get(BOT);
        return handle == null ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private static int number(Object value) {
        return value instanceof Integer count ? count : 0;
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        this.phaseStarted = false;
        LOG.info("BRANCHTEST phase {}   : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures.add(why);
        LOG.warn("BRANCHTEST problem    : {}", why);
    }

    private void finish(String abort) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        if (abort != null) {
            LOG.error("BRANCHTEST VERDICT    : FAIL - {}", abort);
        } else {
            LOG.info("BRANCHTEST requests   : {} planning call(s) in total (1 to start, 1 fallback)",
                    this.model == null ? -1 : this.model.requestCount());
            LOG.info("BRANCHTEST VERDICT    : {}", this.failures.isEmpty() ? "PASS" : "FAIL");
            for (String failure : this.failures) {
                LOG.info("BRANCHTEST failure    : {}", failure);
            }
        }
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.origin != null) {
            for (int dx = -4; dx <= 32; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    for (int dy = -2; dy <= 5; dy++) {
                        this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        ScriptedLlmServer.restoreSettings(this.savedLlmSettings);
        try {
            if (this.configPath != null) {
                if (this.originalConfig == null) {
                    Files.deleteIfExists(this.configPath);
                } else {
                    Files.writeString(this.configPath, this.originalConfig, StandardCharsets.UTF_8);
                }
                Config.apply();
            }
        } catch (IOException e) {
            LOG.warn("BRANCHTEST could not restore the Jev config: {}", e.toString());
        }
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

    /** A System One endpoint that answers a named question with a choice the test picks. */
    private static final class StubSystemOne {

        private final HttpServer http;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> perQuestion = new ConcurrentHashMap<>();
        private final Map<String, String[]> answers = new ConcurrentHashMap<>();

        StubSystemOne() throws IOException {
            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/systemone", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                this.calls.incrementAndGet();
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                JsonObject out = new JsonObject();
                for (String id : request.getAsJsonObject("questions").keySet()) {
                    this.perQuestion.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
                    String[] answer = this.answers.getOrDefault(id,
                            new String[] { "KEEP_MINING", "0.90" });
                    double confidence = Double.parseDouble(answer[1]);
                    JsonObject one = new JsonObject();
                    one.addProperty("choice", answer[0]);
                    one.addProperty("confidence", confidence);
                    JsonObject probabilities = new JsonObject();
                    probabilities.addProperty(answer[0], confidence);
                    probabilities.addProperty("ESCALATE_LLM", Math.max(0.0D, 1.0D - confidence));
                    one.add("probabilities", probabilities);
                    out.add(id, one);
                    LOG.info("BRANCHTEST stub       : question={} answer={} confidence={}", id,
                            answer[0], answer[1]);
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
            this.http.setExecutor(null);
            this.http.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort() + "/systemone";
        }

        void answer(String question, String choice, double confidence) {
            this.answers.put(question, new String[] { choice, String.valueOf(confidence) });
        }

        int calls(String question) {
            AtomicInteger counter = this.perQuestion.get(question);
            return counter == null ? 0 : counter.get();
        }

        void close() {
            this.http.stop(0);
        }
    }
}
