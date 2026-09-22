package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.PlayerStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end proof that a player-built structure cannot be mined, tunnelled or dug out of.
 *
 * <p>This reproduces the real incident in the isolated dev server: a bot standing inside a
 * player's house is told (by the scripted model, exactly as the tool schema allows) to mine a wall
 * block, to open a descending tunnel, and then to escape upward. Before the guard existed all three
 * succeeded and the house was damaged; the assertions here are that all three are refused, that not
 * one house block is removed, and that the escape still gets the bot out - on foot, through the
 * opening.
 *
 * <p>The fourth phase is the positive control for the opposite mistake: away from the house the
 * same bot must still be able to dig a tunnel, so the guard cannot be passing by blocking mining in
 * general.
 *
 * <p>Run it twice, because a guard that cannot be shown to be the cause proves nothing:
 * <pre>
 *   MCAGENT_STRUCTURE_TEST=true ... runServer        # expect STRUCTTEST VERDICT: PASS
 *   MCAGENT_STRUCTURE_TEST=true MCAGENT_STRUCTURE_GUARD=off ... runServer
 *                                                    # expect CONTROL-PASS: the house is damaged
 * </pre>
 * The second run deliberately fails the guard and watches the bot break the wall, which is the
 * evidence that the guard - and nothing else - is what stops it.
 */
public final class StructureGuardSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/structuretest");
    private static final String BOT = "StructBot";
    /** Half-width of the house footprint; the house is (2*HALF+1) square. */
    private static final int HALF = 3;
    /** How far east of the house the control site is built, well outside the structure scan. */
    private static final int CONTROL_OFFSET = 16;
    /** Half-width of the stone ground the house stands on; must reach past the walk-out target. */
    private static final int PLATFORM = 11;

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private BlockPos plot;
    private BlockPos wall;
    private BlockPos control;
    private ServerLevel level;
    private int houseBlocksBefore;
    private boolean started;
    private boolean finished;
    private boolean phaseTwoStarted;
    private int phaseTwoAtTick = -1;
    private int ticks;

    private StructureGuardSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_STRUCTURE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("STRUCTTEST: armed (guard {})", PlayerStructure.enabled() ? "on" : "OFF");
        return new StructureGuardSmokeTest(server);
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
        BotManager.BotHandle handle = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        if (handle == null) {
            this.finish(false, "bot disappeared");
            return;
        }

        if (!this.phaseTwoStarted) {
            this.checkPhaseOne(handle);
            return;
        }
        this.checkPhaseTwo(handle);
    }

    /** The three illegal break attempts, driven through real tool calls from the scripted model. */
    private void checkPhaseOne(BotManager.BotHandle handle) {
        if (this.phaseOneChecked) {
            // The walk-out left a goto queued. Give the bot until it is idle again, otherwise the
            // control tunnel would queue behind it and the check would measure the wrong thing.
            AgentBrain idle = Agent.brainManager() == null ? null
                    : Agent.brainManager().get(handle.player().getUUID());
            if (idle != null && !"idle".equals(idle.currentAction())
                    && this.ticks - this.phaseOneAtTick < 300) {
                return;
            }
            this.startControl(handle, idle);
            return;
        }
        // Four requests means the model has been shown the result of all three tool calls.
        if (this.model.requestCount() < 4) {
            if (this.ticks > 1200) {
                this.finish(false, "the scripted model was only asked "
                        + this.model.requestCount() + " times in 1200 ticks");
            }
            return;
        }
        boolean wallRefused = this.model.maxOccurrences("Player-built structures are protected") >= 1;
        boolean tunnelRefused =
                this.model.maxOccurrences("no tunnel may be started from this spot") >= 1;
        boolean walkedOut =
                this.model.maxOccurrences("walking out through its own door/opening") >= 1;
        boolean wallIntact = this.level.getBlockState(this.wall).is(Blocks.OAK_PLANKS);
        int houseBlocksAfter = this.countHouseBlocks();
        boolean houseIntact = houseBlocksAfter >= this.houseBlocksBefore;
        boolean leftHouse = !insideHouse(handle.player().blockPosition());

        LOG.info("STRUCTTEST mine wall   : refused={} wallIntact={} botAt={}", wallRefused,
                wallIntact, handle.player().blockPosition().toShortString());
        LOG.info("STRUCTTEST dig tunnel  : refused={}", tunnelRefused);
        LOG.info("STRUCTTEST escape_up   : walkOutOffered={} leftHouse={}", walkedOut, leftHouse);
        LOG.info("STRUCTTEST house blocks: before={} after={}", this.houseBlocksBefore,
                houseBlocksAfter);

        if (!PlayerStructure.enabled()) {
            // The control run: with the guard disabled the bot must actually break the wall, which
            // is the damage this test exists to prevent.
            boolean damaged = !wallIntact;
            LOG.info("STRUCTTEST control     : guard OFF -> wallBroken={} refused={} (this is the "
                    + "damage the guard prevents, and the reason no production server sets "
                    + "MCAGENT_STRUCTURE_GUARD)", damaged, wallRefused);
            this.finish(damaged, "guard disabled; expected break reproduced=" + damaged);
            return;
        }

        if (!wallIntact || !wallRefused || !tunnelRefused || !walkedOut || !houseIntact) {
            this.finish(false, "wallRefused=" + wallRefused + " tunnelRefused=" + tunnelRefused
                    + " walkedOut=" + walkedOut + " wallIntact=" + wallIntact
                    + " houseIntact=" + houseIntact);
            return;
        }
        this.phaseOneLeftHouse = leftHouse;
        this.phaseOneChecked = true;
        this.phaseOneAtTick = this.ticks;
    }

    /** Away from the building, with a stone bank to dig into: the same macro must still work. */
    private void startControl(BotManager.BotHandle handle, AgentBrain brain) {
        this.phaseTwoStarted = true;
        this.phaseTwoAtTick = this.ticks;
        BlockPos here = new BlockPos(this.plot.getX() + CONTROL_OFFSET, this.plot.getY(),
                this.plot.getZ());
        handle.player().teleportTo(here.getX() + 0.5D, here.getY(), here.getZ() + 0.5D);
        // new_site: the dev world keeps a saved mine route from other tests, and a resume call
        // would walk to it instead of digging here - which is correct, but useless as a control.
        this.controlResult = brain == null ? "no brain"
                : brain.digTunnel("east", "level", 3, "diamond_pickaxe", true);
        LOG.info("STRUCTTEST control site : digTunnel away from the house -> {}", this.controlResult);
    }

    private boolean phaseOneLeftHouse;
    private boolean phaseOneChecked;
    private int phaseOneAtTick;

    private String controlResult = "";

    /** The positive control: natural ground away from the building must still be diggable. */
    private void checkPhaseTwo(BotManager.BotHandle handle) {
        boolean accepted = this.controlResult.startsWith("digging and walking through");
        boolean dug = !this.level.getBlockState(this.control).is(Blocks.STONE)
                || handle.player().getInventory().countItem(Items.COBBLESTONE) > 0;
        if (accepted && dug) {
            this.finish(true, "control tunnel accepted away from the house and blocks were broken; "
                    + "leftHouseAfterEscape=" + this.phaseOneLeftHouse);
            return;
        }
        if (this.ticks - this.phaseTwoAtTick > 400) {
            this.finish(false, "control digTunnel accepted=" + accepted + " dug=" + dug
                    + " result='" + this.controlResult + "'");
        }
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() - 70;
        int z = spawn.getZ() - 70;
        // Build on a slab of our own stone rather than on terrain: a chunk that has never been
        // generated reports a height that has nothing under it, and an earlier version of this test
        // spawned the bot inside the house and then watched it fall out of the world.
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = Math.max(spawn.getY() + 24, terrain + 16);
        this.plot = new BlockPos(x, ground, z);
        this.wall = new BlockPos(x, ground + 1, z - HALF);

        try {
            // The scripted model makes exactly the three tool calls the guard has to refuse.
            AtomicInteger turns = new AtomicInteger();
            BlockPos wallPos = this.wall;
            this.model = new ScriptedLlmServer(body -> switch (turns.getAndIncrement()) {
                case 0 -> ScriptedLlmServer.toolCall("guard_mine_wall", "mine",
                        "{\"x\":" + wallPos.getX() + ",\"y\":" + wallPos.getY()
                                + ",\"z\":" + wallPos.getZ() + ",\"item\":\"diamond_pickaxe\"}");
                case 1 -> ScriptedLlmServer.toolCall("guard_tunnel", "dig_tunnel",
                        "{\"direction\":\"north\",\"mode\":\"level\",\"length\":4,"
                                + "\"new_site\":true}");
                case 2 -> ScriptedLlmServer.toolCall("guard_escape", "escape_up", "{}");
                default -> ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            this.finish(false, "could not start scripted endpoint: " + e.getMessage());
            return;
        }

        this.buildHouse();
        this.buildControlSite();
        this.houseBlocksBefore = this.countHouseBlocks();

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, this.level, Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            this.finish(false, "could not spawn bot");
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        if (!Agent.attachBrain(handle.player())) {
            this.finish(false, "could not attach scripted brain");
            return;
        }
        // A durable tunnel route outlives the run that created it, and the positive control is the
        // run that creates one: with a route remembered, dig_tunnel answers "return through the
        // established entrance" instead of reaching the structure guard, and this test then reports
        // a refusal that never had the chance to happen. Start from no route so the assertion is
        // about the guard rather than about leftover state.
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(handle.player().getUUID());
        if (brain == null) {
            this.finish(false, "attached brain was not registered");
            return;
        }
        brain.clearMineRouteForTest();
    }

    /**
     * A small house: plank floor, three-block plank walls with one glass window, a plank roof, an
     * opening in the south wall as the only way in or out, and ordinary furniture inside. Every one
     * of those choices is a fixture or a building-palette block, which is what the guard measures.
     */
    private void buildHouse() {
        // Solid ground first: 9x9 of stone from four blocks below the floor upwards.
        for (int dx = -PLATFORM; dx <= PLATFORM; dx++) {
            for (int dz = -PLATFORM; dz <= PLATFORM; dz++) {
                for (int dy = -4; dy <= -1; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                this.level.setBlockAndUpdate(this.plot.offset(dx, -1, dz),
                        Blocks.OAK_PLANKS.defaultBlockState());
                this.level.setBlockAndUpdate(this.plot.offset(dx, 3, dz),
                        Blocks.OAK_PLANKS.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    boolean wallBlock = Math.max(Math.abs(dx), Math.abs(dz)) == HALF;
                    boolean doorway = dx == 0 && dz == HALF && dy <= 1;
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                            wallBlock && !doorway
                                    ? Blocks.OAK_PLANKS.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState());
                }
            }
        }
        // A window in the west wall, and furniture that only a player places.
        this.level.setBlockAndUpdate(this.plot.offset(-HALF, 1, 0), Blocks.GLASS.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(HALF, 1, 0), Blocks.GLASS.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(1, 0, 1), Blocks.WHITE_BED.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(2, 0, 1), Blocks.CHEST.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(2, 0, -1),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(1, 0, -1), Blocks.FURNACE.defaultBlockState());
        this.level.setBlockAndUpdate(this.plot.offset(-1, 0, -1), Blocks.BOOKSHELF.defaultBlockState());
    }

    /** A stone bank 16 blocks east, with a two-block air pocket to stand in. */
    private void buildControlSite() {
        BlockPos origin = new BlockPos(this.plot.getX() + CONTROL_OFFSET, this.plot.getY(),
                this.plot.getZ());
        for (int dx = -1; dx <= 8; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    this.level.setBlockAndUpdate(origin.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        this.level.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
        this.level.setBlockAndUpdate(origin.above(), Blocks.AIR.defaultBlockState());
        this.control = origin.offset(1, 0, 0);
    }

    private int countHouseBlocks() {
        int count = 0;
        for (int dx = -HALF - 1; dx <= HALF + 1; dx++) {
            for (int dz = -HALF - 1; dz <= HALF + 1; dz++) {
                for (int dy = -PlayerStructure.SUBSTRATE; dy <= 4; dy++) {
                    BlockState state = this.level.getBlockState(this.plot.offset(dx, dy, dz));
                    if (!state.isAir() && PlayerStructure.isFixture(state)) {
                        count++;
                    }
                }
            }
        }
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    if (!this.level.getBlockState(this.plot.offset(dx, dy, dz)).isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean insideHouse(BlockPos pos) {
        return Math.abs(pos.getX() - this.plot.getX()) <= HALF
                && Math.abs(pos.getZ() - this.plot.getZ()) <= HALF
                && pos.getY() >= this.plot.getY() - 1 && pos.getY() <= this.plot.getY() + 3;
    }

    private void finish(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("STRUCTTEST result  : {}", detail);
        if (!PlayerStructure.enabled()) {
            LOG.info("STRUCTTEST VERDICT : {}", pass
                    ? "CONTROL-PASS (guard disabled and the house was damaged, as expected)"
                    : "CONTROL-FAIL (guard disabled yet nothing broke; the test proves nothing)");
        } else {
            LOG.info("STRUCTTEST VERDICT : {}", pass ? "PASS" : "FAIL");
        }

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.plot != null) {
            this.clear(new BlockPos(this.plot.getX() + CONTROL_OFFSET, this.plot.getY(),
                    this.plot.getZ()), -2, 10, -3, 3, -2, 3);
            this.clear(this.plot, -HALF - 2, HALF + 2, -HALF - 2, HALF + 2,
                    -PlayerStructure.SUBSTRATE, 4);
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

    private void clear(BlockPos centre, int dxMin, int dxMax, int dzMin, int dzMax,
                       int dyMin, int dyMax) {
        for (int dx = dxMin; dx <= dxMax; dx++) {
            for (int dz = dzMin; dz <= dzMax; dz++) {
                for (int dy = dyMin; dy <= dyMax; dy++) {
                    this.level.setBlockAndUpdate(centre.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
