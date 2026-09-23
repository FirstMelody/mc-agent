package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** End-to-end proof that one local macro can continuously dig and descend a real tunnel. */
public final class TunnelSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/tunneltest");
    private static final String BOT = "TunnelBot";

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private BlockPos origin;
    private boolean started;
    private boolean finished;
    private boolean nudged;
    private int ticks;

    private TunnelSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_TUNNEL_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("TUNNELTEST: armed");
        return new TunnelSmokeTest(server);
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
        BlockPos at = handle.player().blockPosition();
        // Production waits for a task or an event when a bot has no standing goal, so a harness bot
        // spawned bare never asks its scripted model at all. Give it the operator's own bypass
        // (/mcagent think) once, then the turn's tool calls keep it deciding on its own.
        if (!this.nudged && this.ticks > 20) {
            this.nudged = true;
            TestHook.nudge(handle.player());
        }
        boolean descended = this.origin != null
                && at.getX() >= this.origin.getX() + 16
                && at.getY() <= this.origin.getY() - 7;
        if (descended) {
            int drops = handle.player().getInventory().countItem(Items.COBBLESTONE);
            boolean schema = this.model != null
                    && this.model.maxOccurrences("\"name\":\"dig_tunnel\"") >= 1;
            ItemStack stonePick = find(handle.player(), Items.STONE_PICKAXE);
            ItemStack diamondPick = find(handle.player(), Items.DIAMOND_PICKAXE);
            AgentBrain brain = Agent.brainManager() == null ? null
                    : Agent.brainManager().get(handle.player().getUUID());
            handle.player().teleportTo(Vec3.atBottomCenterOf(this.origin).x,
                    this.origin.getY(), Vec3.atBottomCenterOf(this.origin).z);
            // Make this artificial hillside "home" only after the initial route is complete. The
            // first entrance must remain legal; every later attempt to replace it must be refused.
            handle.player().setRespawnPosition(handle.player().level().dimension(), this.origin,
                    0.0F, true, false);
            String resume = brain == null ? "no brain"
                    : brain.digTunnel("east", "level", 2, "diamond_pickaxe");
            boolean reused = resume.contains("established mine entrance")
                    && resume.contains("no new surface hole");
            String secondEntrance = brain == null ? "no brain"
                    : brain.digTunnel("south", "down", 2, "diamond_pickaxe", true);
            boolean oneEntrance = secondEntrance.contains("second entrance is forbidden");
            BlockPos protectedSurface = this.origin.offset(0, 3, 2);
            String surfaceMine = brain == null ? "no brain"
                    : brain.mineAsTool(protectedSurface, 0);
            boolean surfaceIntact = surfaceMine.contains("surface")
                    && level(handle).getBlockState(protectedSurface).is(Blocks.STONE);
            // The rule protects the ground, not what grows on it. A log standing at the same protected
            // height must be accepted: refusing it put every tree within 96 blocks of home off limits,
            // so `mine_resource(oak_wood)` failed on the spot, the plan aborted, the queue drained and
            // the model was asked again two seconds later - production spent 25 planning turns and 71
            // refusals in eight minutes moving a few blocks and changing nothing. Stone at that height
            // stays refused, which is what `surfaceIntact` above asserts.
            BlockPos harvestable = this.origin.offset(2, 3, 2);
            level(handle).setBlockAndUpdate(harvestable, Blocks.OAK_LOG.defaultBlockState());
            String logMine = brain == null ? "no brain" : brain.mineAsTool(harvestable, 0);
            boolean vegetationHarvestable = !logMine.contains("surface")
                    && !logMine.startsWith("failed");
            boolean economical = !stonePick.isEmpty() && stonePick.getDamageValue() > 0
                    && !diamondPick.isEmpty() && diamondPick.getDamageValue() == 0;
            this.finish(drops >= 36 && schema && reused && oneEntrance && surfaceIntact
                            && vegetationHarvestable && economical,
                    "moved from " + this.origin.toShortString() + " to " + at.toShortString()
                    + ", cobblestone=" + drops + ", schema=" + schema
                    + ", reused=" + reused + ", oneEntrance=" + oneEntrance
                    + ", surfaceIntact=" + surfaceIntact
                    + ", vegetationHarvestable=" + vegetationHarvestable
                    + ", economicalTools=" + economical
                    + ", resume='" + resume + "', second='" + secondEntrance
                    + "', surfaceMine='" + surfaceMine + "', logMine='" + logMine + "'");
        } else if (this.ticks > 1600) {
            this.finish(false, "still at " + at.toShortString() + " after 1600 ticks");
        }
    }

    private static ServerLevel level(BotManager.BotHandle handle) {
        return handle.player().serverLevel();
    }

    private void begin() {
        try {
            AtomicInteger turns = new AtomicInteger();
            this.model = new ScriptedLlmServer(body -> turns.getAndIncrement() == 0
                    ? ScriptedLlmServer.toolCall("tunnel_plan", "plan", """
                            {"steps":[
                              {"tool":"dig_tunnel","arguments":{"direction":"east","mode":"down",
                                "length":8,"item":"diamond_pickaxe","new_site":true}},
                              {"tool":"dig_tunnel","arguments":{"direction":"east","mode":"level",
                                "length":8,"item":"diamond_pickaxe"}}
                            ]}
                            """)
                    : ScriptedLlmServer.silent());
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            this.finish(false, "could not start scripted endpoint: " + e.getMessage());
            return;
        }

        ServerLevel level = this.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 70;
        int z = spawn.getZ() + 70;
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.origin = new BlockPos(x, Math.max(spawn.getY() + 20, terrain + 12), z);

        // A solid artificial hillside. Only the starting two-block chamber is open; every later
        // landing must be made by the macro with ordinary block breaking.
        for (int dx = -2; dx <= 18; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -11; dy <= 3; dy++) {
                    level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(this.origin, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(this.origin.above(), Blocks.AIR.defaultBlockState());

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, level, Vec3.atBottomCenterOf(this.origin), true);
        if (handle == null) {
            this.finish(false, "could not spawn bot");
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        handle.player().getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        if (!Agent.attachBrain(handle.player())) {
            this.finish(false, "could not attach scripted brain");
            return;
        }
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(handle.player().getUUID());
        if (brain == null) {
            this.finish(false, "attached brain was not registered");
            return;
        }
        brain.clearMineRouteForTest();
    }

    private static ItemStack find(net.minecraft.server.level.ServerPlayer player, Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private void finish(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("TUNNELTEST result  : {}", detail);
        LOG.info("TUNNELTEST VERDICT : {}", pass ? "PASS" : "FAIL");

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.origin != null) {
            ServerLevel level = this.server.overworld();
            for (int dx = -2; dx <= 18; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -11; dy <= 3; dy++) {
                        level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
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
