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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A field that ripens while the bot is busy asks Jev when to harvest, and "later" is honoured later.
 *
 * <p>The scheduling case the whole feature exists for: the bot is deep in a mining job, the crops
 * come ripe, and the question is whether to break off now or remember it. Jev answers TODO_LATER,
 * which lands on the todo list; the idle moment after the mining job finishes then harvests the field
 * with no planning call at all, because adopting the field hands the work to the farm skill.
 *
 * <p>Enabled only with {@code MCAGENT_FARMRIPE_TEST=true}. Four assertions: the question is asked
 * once the field is roughly ripe, the answer is recorded on the todo list, nothing is harvested while
 * the bot is busy, and the deferred harvest costs no planning turn.
 */
public final class FarmRipeSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/ripetest");
    private static final String BOT = "RipeBot";
    private static final int PHASE_CAP_TICKS = 3000;
    private static final int FIELD_RADIUS = 1;

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedLlmSettings;
    private RipeStub jev;
    private Path configPath;
    private String originalConfig;
    private ServerLevel level;
    private BlockPos centre;
    private boolean started;
    private boolean finished;
    private boolean nudged;
    private int phase;
    private int phaseTick;
    private boolean phaseStarted;
    private int ticks;
    private int requestsAtFieldAdoption = -1;
    private AgentBrain adoptedBrain;
    private BlockPos busyTarget;
    private final List<String> failures = new java.util.ArrayList<>();

    private FarmRipeSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_FARMRIPE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("RIPETEST: armed");
        return new FarmRipeSmokeTest(server);
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
        // Keep the body sound for the whole test, every tick. This measures a scheduling question, not
        // survival, and the farm skill drops its field goal the moment health reaches six - which is
        // exactly what kept happening while the harness only guarded the first phase.
        handle.player().setHealth(20.0F);
        handle.player().resetFallDistance();
        handle.player().setInvulnerable(true);
        // Standing beside the field, every tick. The join restores the position the bot logged out at,
        // which is the previous run's deleted scene - so the first cut had the bot somewhere else
        // entirely, and the runtime's own scan reported the field as ripe=0 crops=0 while the harness's
        // direct world query counted a ripe crop three blocks from the centre.
        handle.player().teleportTo(this.centre.getX() + 0.5D, this.centre.getY(),
                this.centre.getZ() + 3.5D);
        if (!this.nudged && this.ticks > 20 && this.brain() != null) {
            this.nudged = true;
            LOG.info("RIPETEST nudge        : brain present, asking for one decision");
            TestHook.nudge(handle.player());
        }
        if (this.ticks - this.phaseTick > PHASE_CAP_TICKS) {
            this.finish("phase " + this.phase + " timed out (state " + this.state() + ")");
            return;
        }
        switch (this.phase) {
            case 0 -> this.phaseAdoptField(handle);
            case 1 -> this.phaseRipenWhileBusy(handle);
            case 2 -> this.phaseDeferredHarvest(handle);
            default -> this.finish(null);
        }
    }

    /** Phase 0: the model adopts the field, which is the one planning call in the whole test. */
    private void phaseAdoptField(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            return;
        }
        if (this.model.requestCount() < 1) {
            return;
        }
        if (this.state().get("farmLastHarvestTick") == null) {
            if (this.ticks - this.phaseTick > 200) {
                this.fail("the farm tool was called but no field was adopted");
                this.nextPhase("done");
            }
            return;
        }
        this.requestsAtFieldAdoption = this.model.requestCount();
        this.adoptedBrain = this.brain();
        LOG.info("RIPETEST field        : adopted at {} radius {} with {} request(s)",
                this.centre.toShortString(), FIELD_RADIUS, this.model.requestCount());
        this.nextPhase("crops ripen while the bot mines: Jev is asked and answers TODO_LATER");
    }

    /** Phase 1: ripe crops while a mining job owns the bot. */
    private void phaseRipenWhileBusy(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            this.jev.answer("farm_ripe", "TODO_LATER", 0.95D);
            this.jev.answer("mining_recovery", "ESCALATE_LLM", 0.10D);
            // Ripen the field, then give the bot a job so the farm skill cannot claim the tick: this
            // is the production case, a bot deep in a mine while its crops come in.
            int ripened = this.ripenField();
            AgentBrain brain = this.brain();
            String started = brain == null ? "no brain"
                    : brain.mineAsTool(this.busyTarget, 0);
            LOG.info("RIPETEST busy         : ripened {} crop(s), mining job -> {}", ripened, started);
            if (ripened == 0) {
                this.fail("the harness could not ripen any crop");
            }
            return;
        }
        if (this.ticks % 20 == 0) {
            Map<String, Object> seen = this.state();
            Object reflex = seen.get("lastReflexReport");
            LOG.info("RIPETEST waiting      : sameBrain={} jevAsked={} ripe={} crops={} farmGoal={} pingPending={} "
                            + "harvested={} todo={} cooldown={}",
                    this.brain() == this.adoptedBrain, this.jev.calls("farm_ripe"),
                    this.ripeStanding(), this.cropsStanding(),
                    seen.get("farmLastHarvestTick") != null, seen.get("farmRipePingPending"),
                    seen.get("farmHarvested"), seen.get("todoSize"), seen.get("cooldownTicks"));
            if (reflex != null) {
                LOG.info("RIPETEST reflex       : {}", reflex);
            }
        }
        if (this.jev.calls("farm_ripe") < 1) {
            return;
        }
        if (this.ticks - this.phaseTick < 40) {
            return;
        }
        Map<String, Object> state = this.state();
        int todo = number(state.get("todoSize"));
        boolean harvested = number(state.get("farmHarvested")) > 0;
        int extra = this.model.requestCount() - this.requestsAtFieldAdoption;
        LOG.info("RIPETEST question     : jevAsked={} todoSize={} harvestedWhileBusy={} extraRequests={}",
                this.jev.calls("farm_ripe"), todo, harvested, extra);
        if (todo < 1) {
            this.fail("Jev's TODO_LATER answer never reached the todo list");
        }
        if (extra != 0) {
            this.fail("the ripeness question bought " + extra + " planning turn(s)");
        }
        if (harvested) {
            this.fail("the field was harvested while the bot was busy, so the deferral proves nothing");
        }
        this.nextPhase("the idle moment harvests the deferred field with no planning call");
    }

    /** Phase 2: the todo item is taken up when the bot is free, and costs nothing. */
    private void phaseDeferredHarvest(BotManager.BotHandle handle) {
        if (!this.phaseStarted) {
            this.phaseStarted = true;
            return;
        }
        int todo = number(this.state().get("todoSize"));
        int harvested = number(this.state().get("farmHarvested"));
        boolean crops = this.cropsStanding() > 0;
        int extra = this.model.requestCount() - this.requestsAtFieldAdoption;
        if (harvested >= 1 && todo == 0) {
            LOG.info("RIPETEST deferred     : harvested={} todoSize={} cropsStanding={} extraRequests={}",
                    harvested, todo, crops, extra);
            if (extra != 0) {
                this.fail("the deferred harvest bought " + extra + " planning turn(s)");
            }
            this.nextPhase("done");
            return;
        }
        if (this.ticks - this.phaseTick > 2400) {
            this.fail("the deferred harvest never happened: harvested=" + harvested + " todoSize=" + todo
                    + " cropsStanding=" + crops + " state=" + this.state());
            this.nextPhase("done");
        }
    }

    private int ripeStanding() {
        int count = 0;
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                BlockState state = this.level.getBlockState(this.centre.offset(dx, 1, dz));
                if (state.is(Blocks.WHEAT) && state.getValue(BlockStateProperties.AGE_7) >= 7) {
                    count++;
                }
            }
        }
        return count;
    }

    private int cropsStanding() {
        int count = 0;
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                BlockState state = this.level.getBlockState(this.centre.offset(dx, 1, dz));
                if (state.is(Blocks.WHEAT)) {
                    count++;
                }
            }
        }
        return count;
    }

    private int ripenField() {
        int ripened = 0;
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                BlockPos pos = this.centre.offset(dx, 1, dz);
                BlockState state = this.level.getBlockState(pos);
                if (state.is(Blocks.WHEAT)) {
                    this.level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.AGE_7, 7));
                    ripened++;
                }
            }
        }
        return ripened;
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() + 120;
        int z = spawn.getZ() + 120;
        int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.centre = new BlockPos(x, surface, z);
        this.busyTarget = this.centre.offset(4, 0, 0);
        // Everything the bot and its busy-work target need: floor, headroom, and no leftover surface
        // blocks in the way. The first cut only cleared the field itself, so the mining job aimed at a
        // block with no walkable floor under it and came back NO_SAFE_MINING_ACCESS - the bot was
        // never busy, and the deferral this test is about had nothing to defer.
        for (int dx = -FIELD_RADIUS - 1; dx <= FIELD_RADIUS + 6; dx++) {
            for (int dz = -FIELD_RADIUS - 1; dz <= FIELD_RADIUS + 5; dz++) {
                this.level.setBlockAndUpdate(this.centre.offset(dx, -1, dz),
                        Blocks.DIRT.defaultBlockState());
                this.level.setBlockAndUpdate(this.centre.offset(dx, 0, dz),
                        Blocks.AIR.defaultBlockState());
                this.level.setBlockAndUpdate(this.centre.offset(dx, 1, dz),
                        Blocks.AIR.defaultBlockState());
            }
        }
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                this.level.setBlockAndUpdate(this.centre.offset(dx, 0, dz),
                        Blocks.FARMLAND.defaultBlockState());
                this.level.setBlockAndUpdate(this.centre.offset(dx, 1, dz),
                        Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 0));
            }
        }
        // A block to mine, right beside the bot, standing on solid ground: a job that owns the bot's
        // ticks while the crops ripen, and one the runtime can actually reach.
        this.level.setBlockAndUpdate(this.busyTarget, Blocks.STONE.defaultBlockState());
        this.level.setBlockAndUpdate(this.busyTarget.above(), Blocks.AIR.defaultBlockState());
        this.level.setBlockAndUpdate(this.busyTarget.below(), Blocks.DIRT.defaultBlockState());

        try {
            AtomicInteger turns = new AtomicInteger();
            String farmCall = "{\"x\":" + this.centre.getX() + ",\"y\":" + this.centre.getY()
                    + ",\"z\":" + this.centre.getZ() + ",\"radius\":" + FIELD_RADIUS + "}";
            this.model = new ScriptedLlmServer(body -> turns.getAndIncrement() == 0
                    ? ScriptedLlmServer.toolCall("adopt", "farm", farmCall)
                    : ScriptedLlmServer.silent());
            this.savedLlmSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
            this.jev = new RipeStub();
            this.writeJevConfig();
        } catch (IOException e) {
            this.finish("could not start the stubs: " + e.getMessage());
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(BOT, this.level,
                Vec3.atBottomCenterOf(this.centre.offset(0, 0, 3)), true);
        if (handle == null) {
            this.finish("could not spawn the bot");
            return;
        }
        // The join path does not attach a brain in a dev harness (proved: "none at spawn" and then no
        // brain ever appeared), so the test attaches one itself, as the tunnel and branch harnesses
        // do. The earlier "field goal vanished" was NOT two brains: sameBrain=true ruled the lookup
        // instability out, and the log shows no interrupt call, no death and no low-health break, so
        // the clearing path still has to be named by instrumenting the three sites rather than
        // guessed at.
        if (!Agent.attachBrain(handle.player())) {
            this.finish("could not attach a brain to the bot");
            return;
        }
        LOG.info("RIPETEST brain        : {} after attach", this.brain() == null ? "none" : "present");
        // Daylight and invulnerability: the first cut left the bot standing at the surface, where the
        // survival reflex took over - it broke off the mining job and the low-health path cleared the
        // field goal (the only three places that clear it are death, health below six, and the
        // interrupt tool), so the harness measured a bot with no field and nothing to defer. This
        // test is about the ripeness question, not about surviving the night.
        this.level.setDayTime(6000L);
        // The bot's name is reused between runs, and persistence is the default: the second run of this
        // harness restored the previous run's health - 6.0, the farm skill's own safety valve - and the
        // field goal was dropped by it within four seconds of being adopted. FARMDEBUG named it:
        // "field set at 120,-64,120" then "cleared by low_health=6.0". Reset the body, do not inherit it.
        handle.player().setHealth(20.0F);
        handle.player().getFoodData().setFoodLevel(20);
        // Land it: a reused bot name restores the position it logged out at, which is the previous
        // run's scene - by then deleted - so it arrives in mid-air and takes fall damage down to 6.0,
        // the farm skill's own safety valve. Put it on the ground and clear the fall.
        handle.player().teleportTo(this.centre.getX() + 0.5D, this.centre.getY(),
                this.centre.getZ() + 3.5D);
        handle.player().resetFallDistance();
        handle.player().getAbilities().invulnerable = true;
        handle.player().onUpdateAbilities();
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_HOE));
        handle.player().getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 16));
        handle.player().getInventory().add(new ItemStack(Items.BREAD, 16));
        LOG.info("RIPETEST scene        : {} wheat on farmland at {}, bot at {}", this.cropsStanding(),
                this.centre.toShortString(), handle.player().blockPosition().toShortString());
        this.phaseTick = this.ticks;
    }

    private void writeJevConfig() throws IOException {
        this.configPath = Path.of("config", "mcagent-jev.properties");
        if (this.originalConfig == null && Files.isRegularFile(this.configPath)) {
            this.originalConfig = Files.readString(this.configPath, StandardCharsets.UTF_8);
        }
        String body = "# Written by FarmRipeSmokeTest; restored when the test ends.\n"
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
        this.jev.answer("farm_ripe", "TODO_LATER", 0.95D);
        Config.apply();
    }

    private Map<String, Object> state() {
        AgentBrain brain = this.brain();
        return brain == null ? Map.of() : brain.debugState();
    }

    private AgentBrain brain() {
        if (Agent.brainManager() == null || Agent.botManager() == null) {
            return null;
        }
        BotManager.BotHandle handle = Agent.botManager().get(BOT);
        return handle == null ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private static int number(Object value) {
        return value instanceof Integer count ? count : 0;
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        this.phaseStarted = false;
        LOG.info("RIPETEST phase {}      : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures.add(why);
        LOG.warn("RIPETEST problem      : {}", why);
    }

    private void finish(String abort) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        if (abort != null) {
            LOG.error("RIPETEST VERDICT      : FAIL - {}", abort);
        } else {
            LOG.info("RIPETEST requests     : {} planning call(s) in total", 
                    this.model == null ? -1 : this.model.requestCount());
            LOG.info("RIPETEST VERDICT      : {}", this.failures.isEmpty() ? "PASS" : "FAIL");
            for (String failure : this.failures) {
                LOG.info("RIPETEST failure      : {}", failure);
            }
        }
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.centre != null) {
            for (int dx = -FIELD_RADIUS - 2; dx <= FIELD_RADIUS + 2; dx++) {
                for (int dz = -FIELD_RADIUS - 2; dz <= FIELD_RADIUS + 2; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        this.level.setBlockAndUpdate(this.centre.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
            this.level.setBlockAndUpdate(this.busyTarget, Blocks.AIR.defaultBlockState());
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
            LOG.warn("RIPETEST could not restore the Jev config: {}", e.toString());
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
    private static final class RipeStub {

        private final HttpServer http;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> perQuestion = new ConcurrentHashMap<>();
        private final Map<String, String[]> answers = new ConcurrentHashMap<>();

        RipeStub() throws IOException {
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
                    LOG.info("RIPETEST stub       : question={} answer={} confidence={}", id,
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
