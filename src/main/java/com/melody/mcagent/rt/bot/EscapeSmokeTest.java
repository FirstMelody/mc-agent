package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.ChatLog;

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

/** End-to-end proof for emergency respawn teleport and physical staircase escape. */
public final class EscapeSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/escapetest");
    private static final String BOT = "EscapeBot";
    private static final String LISTENER = "EscapeListener";
    private static final String DONE = "escape plan complete";

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private BlockPos pit;
    private boolean started;
    private boolean finished;
    private boolean returnPassed;
    private boolean spokeBeforeEscape;
    private int escapedAtTick = -1;
    private int ticks;

    private EscapeSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_ESCAPE_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("ESCAPETEST: armed");
        return new EscapeSmokeTest(server);
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
        boolean climbed = this.pit != null && at.getY() >= this.pit.getY() + 6;
        boolean escaped = climbed && handle.player().level().canSeeSky(at.above());
        if (!escaped && this.heardDone()) {
            this.spokeBeforeEscape = true;
        }
        if (escaped) {
            if (this.escapedAtTick < 0) {
                this.escapedAtTick = this.ticks;
            }
            boolean schema = this.model != null
                    && this.model.maxOccurrences("\"name\":\"escape_up\"") >= 1
                    && this.model.maxOccurrences("\"name\":\"return_to_spawn\"") >= 1;
            boolean planTail = this.heardDone() && !this.spokeBeforeEscape;
            if (planTail || this.ticks - this.escapedAtTick > 20) {
                this.finish(this.returnPassed && schema && planTail,
                        "climbed from " + this.pit.toShortString()
                        + " to " + at.toShortString() + ", sky visible=" + escaped
                        + ", tool schemas=" + schema + ", plan tail after escape=" + planTail);
            }
        } else if (this.ticks > 800) {
            this.finish(false, "still at " + at.toShortString() + " after 800 ticks");
        }
    }

    private void begin() {
        try {
            AtomicInteger turns = new AtomicInteger();
            this.model = new ScriptedLlmServer(body -> turns.getAndIncrement() == 0
                    ? ScriptedLlmServer.toolCall("escape_plan", "plan", """
                            {"steps":[
                              {"tool":"escape_up","arguments":{"item":"diamond_pickaxe"}},
                              {"tool":"say","arguments":{"message":"escape plan complete"}}
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
        BlockPos worldSpawn = level.getSharedSpawnPos();
        int x = worldSpawn.getX() + 40;
        int z = worldSpawn.getZ() + 40;
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.pit = new BlockPos(x, Math.max(worldSpawn.getY() + 12, terrain + 2), z);

        // An eight-block-high artificial hill with one open vertical shaft. Every horizontal
        // direction contains enough solid terrain for a seven-tread staircase.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                for (int dy = -1; dy <= 6; dy++) {
                    level.setBlockAndUpdate(this.pit.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
        for (int dy = 0; dy <= 7; dy++) {
            level.setBlockAndUpdate(this.pit.above(dy), Blocks.AIR.defaultBlockState());
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, level, Vec3.atBottomCenterOf(this.pit), false);
        BotManager.BotHandle listener = Agent.botManager().spawn(
                LISTENER, level, Vec3.atBottomCenterOf(worldSpawn), true);
        if (handle == null || listener == null) {
            this.finish(false, "could not spawn bot");
            return;
        }
        ChatLog.clear(listener.player().getUUID());
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));

        // First prove the emergency teleport itself without involving a model.
        BlockPos safe = new BlockPos(worldSpawn.getX(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        worldSpawn.getX(), worldSpawn.getZ()), worldSpawn.getZ());
        handle.player().setRespawnPosition(level.dimension(), safe, 0.0F, true, false);
        Actions.Result returned = Actions.returnToRespawn(handle.player());
        this.returnPassed = returned.success()
                && handle.player().blockPosition().distSqr(safe) <= 2.0D;
        LOG.info("ESCAPETEST return_to_spawn -> {} (at {}, expected {})",
                returned.message(), handle.player().blockPosition().toShortString(), safe.toShortString());

        handle.player().teleportTo(this.pit.getX() + 0.5D, this.pit.getY(), this.pit.getZ() + 0.5D);
        if (!Agent.attachBrain(handle.player())) {
            this.finish(false, "could not attach scripted brain");
        }
    }

    private boolean heardDone() {
        BotManager.BotHandle listener = Agent.botManager() == null ? null : Agent.botManager().get(LISTENER);
        return listener != null && ChatLog.recent(listener.player(), 100).stream()
                .anyMatch(line -> line.speaker().equals(BOT) && line.text().equals(DONE));
    }

    private void finish(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("ESCAPETEST return teleport : {}", this.returnPassed ? "PASS" : "FAIL");
        LOG.info("ESCAPETEST staircase       : {}", detail);
        LOG.info("ESCAPETEST VERDICT         : {}", pass ? "PASS" : "FAIL");

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
            Agent.botManager().remove(LISTENER);
        }
        if (this.pit != null) {
            ServerLevel level = this.server.overworld();
            for (int dx = -9; dx <= 9; dx++) {
                for (int dz = -9; dz <= 9; dz++) {
                    for (int dy = -1; dy <= 8; dy++) {
                        level.setBlockAndUpdate(this.pit.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
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
