package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.Crops;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The farm: ripe crops are harvested and replanted without a single planning turn.
 *
 * <p>That is the whole claim, and it is the thing a normal tool call cannot do - the model would have
 * to be asked after every crop, which on a 9x9 field is eighty round trips to say "yes, take the
 * wheat". So the assertion is deliberately two-sided: the crops really do come off the field and go
 * back in, <em>and</em> the model was asked nothing while it happened. A test that only checked the
 * field would pass for a bot that spent a fortune in tokens doing it.
 *
 * <p>The field also contains crops that are NOT ripe: a skill that harvests everything is a skill
 * that destroys its own farm.
 *
 * <p>Enabled with {@code MCAGENT_FARM_TEST=true}.
 */
public final class FarmSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/farmtest");

    private static final String BOT = "FarmBot";
    /** Ripe crops planted, and the number the run is judged on. */
    private static final int RIPE = 6;
    /** Unripe crops that must still be standing at the end. */
    private static final int GROWING = 4;
    /** Ticks to let the skill work before judging (harvest, collect, replant, six times over). */
    private static final int WORK_TIMEOUT_TICKS = 1800;

    private final MinecraftServer server;

    private ServerLevel level;
    private BlockPos plot;
    private final java.util.List<BlockPos> ripe = new java.util.ArrayList<>();
    private final java.util.List<BlockPos> growing = new java.util.ArrayList<>();

    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private boolean nudged;
    private int ticks;
    private int requestsAfterFarmCall = -1;

    private FarmSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_FARM_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("FARMTEST: armed");
        return new FarmSmokeTest(server);
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

        // Production waits for a task or an event when a bot has no standing goal, so a harness bot
        // spawned bare never asks its scripted model at all. Give it the operator's own bypass
        // (/mcagent think) once, then the turn's tool calls keep it deciding on its own.
        if (!this.nudged && this.ticks > 20) {
            this.nudged = true;
            BotManager.BotHandle handle = Agent.botManager() == null
                    ? null : Agent.botManager().get(BOT);
            if (handle != null) {
                TestHook.nudge(handle.player());
            }
        }
        // The scripted model answers `silent()` to everything after the one farm call, so any extra
        // request is a planning turn the harvest did not need.
        if (this.requestsAfterFarmCall < 0 && this.model.requestCount() >= 2) {
            this.requestsAfterFarmCall = this.model.requestCount();
        }
        int harvested = this.harvestedCount();
        if (harvested >= RIPE) {
            this.verify();
            return;
        }
        if (this.ticks > WORK_TIMEOUT_TICKS) {
            LOG.error("FARMTEST VERDICT: FAIL - only {} of {} ripe crop(s) were harvested in {} ticks",
                    harvested, RIPE, WORK_TIMEOUT_TICKS);
            this.finishQuietly();
        }
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() - 70;
        int z = spawn.getZ() + 70;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.plot = new BlockPos(x, Math.max(spawn.getY() + 24, terrain + 16), z);
        this.buildField();

        try {
            // One call to adopt the field, then nothing: the harvest must not need the model at all.
            BlockPos centre = this.plot;
            int[] turn = { 0 };
            this.model = new ScriptedLlmServer(body -> switch (turn[0]++) {
                case 0 -> ScriptedLlmServer.toolCall("f1", "farm",
                        "{\"x\":" + centre.getX() + ",\"y\":" + centre.getY()
                                + ",\"z\":" + centre.getZ() + ",\"radius\":6}");
                default -> ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("FARMTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, this.level, Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            LOG.error("FARMTEST: FAIL - could not spawn the bot");
            this.finishQuietly();
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 16));
        handle.player().getInventory().add(new ItemStack(Items.IRON_HOE));
        if (!Agent.attachBrain(handle.player())) {
            LOG.error("FARMTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("FARMTEST field at {} (radius 6): {} ripe and {} growing wheat, bot carries 16 seeds "
                + "and a hoe", this.plot.toShortString(), RIPE, GROWING);
    }

    /** Farmland with a mix of ripe and unripe wheat, on our own slab of stone. */
    private void buildField() {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                this.level.setBlockAndUpdate(this.plot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        int planted = 0;
        for (int dx = -2; dx <= 2 && planted < RIPE + GROWING; dx++) {
            for (int dz = -1; dz <= 1 && planted < RIPE + GROWING; dz++) {
                BlockPos soil = this.plot.offset(dx, -1, dz);
                BlockPos crop = this.plot.offset(dx, 0, dz);
                this.level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
                boolean isRipe = planted < RIPE;
                BlockState wheat = Blocks.WHEAT.defaultBlockState()
                        .setValue(CropBlock.AGE, isRipe ? CropBlock.MAX_AGE : 2);
                this.level.setBlockAndUpdate(crop, wheat);
                (isRipe ? this.ripe : this.growing).add(crop.immutable());
                planted++;
            }
        }
    }

    /** How many of the originally ripe positions are now standing young crops. */
    private int harvestedCount() {
        int done = 0;
        for (BlockPos pos : this.ripe) {
            BlockState state = this.level.getBlockState(pos);
            // Harvested and replanted: a crop is there again but it is not ripe. A block that is
            // still ripe has not been touched yet.
            if (Crops.isCrop(state) && !Crops.isMature(state)) {
                done++;
            }
        }
        return done;
    }

    private void verify() {
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(Agent.botManager().get(BOT).player().getUUID());
        Object farmState = brain == null ? null : brain.debugState().get("farm");

        int untouchedGrowing = 0;
        for (BlockPos pos : this.growing) {
            if (Crops.isCrop(this.level.getBlockState(pos)) && !Crops.isMature(this.level.getBlockState(pos))) {
                untouchedGrowing++;
            }
        }
        boolean farmlandIntact = true;
        for (BlockPos pos : this.ripe) {
            if (!Crops.isFarmland(this.level.getBlockState(pos.below()))) {
                farmlandIntact = false;
            }
        }
        int requests = this.model.requestCount();
        // Two requests is the floor: the turn that asked for `farm`, and the turn after it. Anything
        // beyond that is a planning turn bought by the harvest.
        boolean noPlanningPerCrop = requests <= 3;

        LOG.info("FARMTEST harvested={}/{} growing-left-intact={}/{} farmland-intact={} farm-state={}",
                this.harvestedCount(), RIPE, untouchedGrowing, GROWING, farmlandIntact, farmState);
        LOG.info("FARMTEST model requests for the whole run: {} (one to adopt the field, then silence)",
                requests);

        boolean pass = this.harvestedCount() >= RIPE && untouchedGrowing == GROWING
                && farmlandIntact && noPlanningPerCrop;
        LOG.info("FARMTEST VERDICT: {}{}", pass ? "PASS" : "FAIL - ",
                pass ? " (every ripe crop harvested and replanted with no planning turn, unripe crops "
                        + "and farmland untouched)"
                     : describeFailure(untouchedGrowing, farmlandIntact, noPlanningPerCrop, requests));
        this.finishQuietly();
    }

    private String describeFailure(int untouchedGrowing, boolean farmlandIntact, boolean cheap,
                                   int requests) {
        StringBuilder sb = new StringBuilder();
        if (this.harvestedCount() < RIPE) {
            sb.append("only ").append(this.harvestedCount()).append(" of ").append(RIPE)
              .append(" ripe crops were taken and replanted; ");
        }
        if (untouchedGrowing != GROWING) {
            sb.append("unripe crops were damaged (").append(untouchedGrowing).append('/')
              .append(GROWING).append(" left); ");
        }
        if (!farmlandIntact) {
            sb.append("the farmland itself was destroyed; ");
        }
        if (!cheap) {
            sb.append("the harvest cost ").append(requests)
              .append(" model requests, so it was asking for permission per crop; ");
        }
        return sb.toString();
    }

    private void finishQuietly() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
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
