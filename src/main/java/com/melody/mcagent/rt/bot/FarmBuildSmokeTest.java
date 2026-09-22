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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Building a field: the bot chooses the ground, then tills, waters, lights and sows it.
 *
 * <p>Three things are asserted, and the third is the one that would be easy to fake. The field
 * exists and is the shape that was asked for. It has water in the middle and torches on the corners,
 * because "build the whole farm with tools" includes the parts that are not crops. And the whole
 * build cost <em>one</em> model request: a build that asks the model before every block is a build
 * that costs more in tokens than the wheat is worth.
 *
 * <p>The site is also checked against a deliberately bad neighbour: a "house" of planks with a chest
 * in it is built right next to the good ground, and the bot is asked for a field with no coordinates.
 * It must not site its farm in the house - and the guard would refuse block by block if it tried.
 *
 * <p>Enabled with {@code MCAGENT_FARMBUILD_TEST=true}.
 */
public final class FarmBuildSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/farmbuildtest");

    private static final String BOT = "BuildBot";
    /** The field the bot builds on its own is a 5x5: what it can work from its middle without
     * walking on it. See the radius clamp in {@code startFarmBuild}. */
    private static final int RADIUS = 2;
    private static final int SIDE = RADIUS * 2 + 1;
    /** Farmland cells: the whole square, less the water hole in the middle. */
    private static final int EXPECTED_FARMLAND = SIDE * SIDE - 1;

    private static final int BUILD_TIMEOUT_TICKS = 2400;

    private final MinecraftServer server;

    private ServerLevel level;
    private BlockPos plot;

    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private int ticks;
    /** Model requests counted when the build was first seen running, and the most seen during it. */
    private int requestsWhenBuildSeen = -1;
    private int requestsDuringBuild;

    private FarmBuildSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_FARMBUILD_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("FARMBUILDTEST: armed");
        return new FarmBuildSmokeTest(server);
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
        // Count planning turns *while the build is running*, not for the whole session: an idle bot
        // buys turns by design (that is what the cheap routing layer is for), so the claim being
        // tested is that building the field costs none of them.
        if (this.building()) {
            if (this.requestsWhenBuildSeen < 0) {
                this.requestsWhenBuildSeen = this.model.requestCount();
            }
            this.requestsDuringBuild =
                    Math.max(this.requestsDuringBuild, this.model.requestCount() - this.requestsWhenBuildSeen);
        }
        if (this.fieldComplete()) {
            this.verify();
            return;
        }
        if (this.ticks > BUILD_TIMEOUT_TICKS) {
            java.util.List<BlockPos> soil = this.farmland();
            LOG.error("FARMBUILDTEST VERDICT: FAIL - the field was not finished in {} ticks "
                    + "(farmland={} crops={} bounds={})", BUILD_TIMEOUT_TICKS, soil.size(),
                    this.cropCount(soil), java.util.Arrays.toString(this.bounds(soil)));
            this.finishQuietly();
        }
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() + 70;
        int z = spawn.getZ() - 70;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.plot = new BlockPos(x, Math.max(spawn.getY() + 24, terrain + 16), z);
        this.buildGround();

        try {
            // One call, no coordinates: the bot has to choose the site itself. Everything after it
            // must happen without the model.
            int[] turn = { 0 };
            this.model = new ScriptedLlmServer(body -> switch (turn[0]++) {
                case 0 -> ScriptedLlmServer.toolCall("b1", "build_farm", "{\"radius\":" + RADIUS + "}");
                default -> ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("FARMBUILDTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, this.level, Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            LOG.error("FARMBUILDTEST: FAIL - could not spawn the bot");
            this.finishQuietly();
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.IRON_HOE));
        handle.player().getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 64));
        handle.player().getInventory().add(new ItemStack(Items.TORCH, 8));
        handle.player().getInventory().add(new ItemStack(Items.WATER_BUCKET));
        if (!Agent.attachBrain(handle.player())) {
            LOG.error("FARMBUILDTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("FARMBUILDTEST bot at {} carries a hoe, 64 seeds, 8 torches and a water bucket; the "
                + "scripted model asks for a field with no coordinates, then says nothing",
                this.plot.toShortString());
    }

    /**
     * Flat grass to build on, plus a deliberately tempting neighbour: a plank house with a chest in
     * it, close enough that a careless site search would put the field inside it.
     */
    private void buildGround() {
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                // Several layers of solid ground, not a one-block platform: the field is built by
                // digging a water hole into it, and a platform one block thick drops the bot into the
                // void the moment it breaks the surface. (That is exactly what the first version of
                // this harness did, and it read as a bug in the builder.)
                for (int dy = -5; dy <= -2; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.DIRT.defaultBlockState());
                }
                this.level.setBlockAndUpdate(this.plot.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // The house sits 7 blocks away, so a 7x7 field centred on the bot cannot reach it but a
        // careless search could still choose ground inside its protected region.
        BlockPos house = this.plot.offset(7, 0, 0);
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                this.level.setBlockAndUpdate(house.offset(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState());
                for (int dy = 1; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(house.offset(dx, dy, dz),
                            dx == 0 || dz == 0 || dx == 3 || dz == 3
                                    ? Blocks.OAK_PLANKS.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.level.setBlockAndUpdate(house.offset(1, 1, 1), Blocks.CHEST.defaultBlockState());
    }

    /**
     * The field the bot actually built, found by looking for its farmland.
     *
     * <p>Not "around the bot": choosing the ground is the bot's job, and on this plot the ground
     * beside it is correctly refused - a house of planks with a chest in it sits seven blocks away,
     * so the field is built further off. The test follows the field instead of assuming where it is,
     * which is also what makes the site-selection assertion meaningful.
     */
    private java.util.List<BlockPos> farmland() {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (int dx = -24; dx <= 24; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                for (int dy = -4; dy <= 2; dy++) {
                    BlockPos pos = this.plot.offset(dx, dy, dz);
                    if (this.level.getBlockState(pos).is(Blocks.FARMLAND)) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        return found;
    }

    /** The rectangle the farmland occupies, or null while there is none. */
    @Nullable
    private int[] bounds(java.util.List<BlockPos> soil) {
        if (soil.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int y = soil.get(0).getY();
        for (BlockPos pos : soil) {
            minX = Math.min(minX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new int[] { minX, y, minZ, maxX, maxZ };
    }

    /** Is the field finished: the right farmland, water in the middle, four torches, crops on it. */
    private boolean fieldComplete() {
        java.util.List<BlockPos> soil = this.farmland();
        if (soil.size() < EXPECTED_FARMLAND) {
            return false;
        }
        int[] box = this.bounds(soil);
        int centreX = (box[0] + box[3]) / 2;
        int centreZ = (box[2] + box[4]) / 2;
        boolean water = this.level.getBlockState(new BlockPos(centreX, box[1], centreZ)).is(Blocks.WATER);
        // The torches stand on the ring just outside the field, one per corner.
        int torches = 0;
        for (int x : new int[] { box[0] - 1, box[3] + 1 }) {
            for (int z : new int[] { box[2] - 1, box[4] + 1 }) {
                if (this.level.getBlockState(new BlockPos(x, box[1] + 1, z)).is(Blocks.TORCH)) {
                    torches++;
                }
            }
        }
        return water && torches >= 4 && this.cropCount(soil) >= EXPECTED_FARMLAND;
    }

    /** Crops standing on the field's own cells. */
    private int cropCount(java.util.List<BlockPos> soil) {
        int found = 0;
        for (BlockPos pos : soil) {
            if (Crops.isCrop(this.level.getBlockState(pos.above()))) {
                found++;
            }
        }
        return found;
    }

    /** Is a field build running right now? Read from the brain's own diagnostic state. */
    private boolean building() {
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(Agent.botManager().get(BOT).player().getUUID());
        Object state = brain == null ? null : brain.debugState().get("farmBuild");
        return state != null && !"(none)".equals(state.toString());
    }

    /** Did any field block land inside the house? The one thing the site search must never do. */
    private boolean builtInTheHouse() {
        BlockPos house = this.plot.offset(7, 0, 0);
        for (int dx = 0; dx <= 3; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                for (int dy = 1; dy <= 3; dy++) {
                    BlockState state = this.level.getBlockState(house.offset(dx, dy, dz));
                    if (Crops.isCrop(state) || state.is(Blocks.FARMLAND) || state.is(Blocks.TORCH)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void verify() {
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(Agent.botManager().get(BOT).player().getUUID());
        Object buildState = brain == null ? null : brain.debugState().get("farmBuild");
        Object farmState = brain == null ? null : brain.debugState().get("farm");

        java.util.List<BlockPos> soil = this.farmland();
        int[] box = this.bounds(soil);
        int farmland = soil.size();
        int crops = this.cropCount(soil);
        int requests = this.model.requestCount();
        boolean inHouse = this.builtInTheHouse();
        // The build itself must cost nothing: one request asked for the field, and the job did the
        // rest. Turns bought afterwards are the idle bot's normal behaviour, not the build's.
        boolean noPlanningPerBlock = this.requestsDuringBuild == 0;
        boolean watered = box != null && this.level.getBlockState(
                new BlockPos((box[0] + box[3]) / 2, box[1], (box[2] + box[4]) / 2)).is(Blocks.WATER);
        int torches = 0;
        if (box != null) {
            for (int x : new int[] { box[0] - 1, box[3] + 1 }) {
                for (int z : new int[] { box[2] - 1, box[4] + 1 }) {
                    if (this.level.getBlockState(new BlockPos(x, box[1] + 1, z)).is(Blocks.TORCH)) {
                        torches++;
                    }
                }
            }
        }
        boolean lit = torches >= 4;

        LOG.info("FARMBUILDTEST field: farmland={}/{} crops={}/{} bounds={} water-in-middle={} "
                + "ring-torches={}/4 built-in-house={}", farmland, EXPECTED_FARMLAND, crops,
                EXPECTED_FARMLAND, java.util.Arrays.toString(box), watered, torches, inHouse);
        LOG.info("FARMBUILDTEST farmBuild={} farm={}", buildState, farmState);
        LOG.info("FARMBUILDTEST model requests: {} in the whole session, {} of them while the build "
                + "was running", requests, this.requestsDuringBuild);

        boolean pass = farmland >= EXPECTED_FARMLAND && crops >= EXPECTED_FARMLAND && watered && lit
                && !inHouse && noPlanningPerBlock;
        LOG.info("FARMBUILDTEST VERDICT: {}{}", pass ? "PASS" : "FAIL - ",
                pass ? " (a " + SIDE + "x" + SIDE + " field with water, corner torches and crops, on "
                        + "ground it chose itself, for one model request)"
                     : describeFailure(farmland, crops, watered, lit, inHouse, noPlanningPerBlock,
                             requests));
        this.finishQuietly();
    }

    private String describeFailure(int farmland, int crops, boolean watered, boolean lit,
                                   boolean inHouse, boolean cheap, int requests) {
        StringBuilder sb = new StringBuilder();
        if (farmland < EXPECTED_FARMLAND) {
            sb.append("only ").append(farmland).append('/').append(EXPECTED_FARMLAND)
              .append(" cells were tilled; ");
        }
        if (crops < EXPECTED_FARMLAND) {
            sb.append("only ").append(crops).append('/').append(EXPECTED_FARMLAND)
              .append(" cells were sown; ");
        }
        if (!watered) {
            sb.append("no water source was placed; ");
        }
        if (!lit) {
            sb.append("the corners were not lit; ");
        }
        if (inHouse) {
            sb.append("it built part of the field inside the house - the site search failed; ");
        }
        if (!cheap) {
            sb.append("the build bought ").append(this.requestsDuringBuild)
              .append(" planning turn(s) while it ran, so it was asking per block; ");
        }
        return sb.toString();
    }

    /**
     * Remove the house this harness builds to tempt the site search.
     *
     * <p>It is planks plus a chest - constructed blocks and a fixture - which is exactly what the
     * structure guard protects, and this world is shared with every other harness. The field itself
     * is left alone: farmland and crops are exempt from the guard, so they cannot mislead anything.
     */
    private void cleanUpSite() {
        if (this.level == null || this.plot == null) {
            return;
        }
        net.minecraft.core.BlockPos house = this.plot.offset(7, 0, 0);
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -1; dz <= 4; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    net.minecraft.core.BlockPos pos = house.offset(dx, dy, dz);
                    var state = this.level.getBlockState(pos);
                    if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.CHEST)) {
                        this.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private void finishQuietly() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        this.cleanUpSite();
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
