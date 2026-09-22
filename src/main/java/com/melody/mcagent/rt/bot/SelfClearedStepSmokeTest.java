package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

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
 * A queued step that names a block this bot just removed must not abort the plan.
 *
 * <p>Production bug this pins down: a descending tunnel died after two blocks, every 40 seconds, and
 * each death bought a full planning turn (~11.8k tokens) that re-derived the same tunnel. The cause
 * was two clearance systems naming the same cell - the tunnel macro queues the cells it means to
 * clear, and a {@code MineJob} clears its own approach blocker while removing an occluded target. The
 * second one won, the macro's step then found air, {@code startMine} returned {@code failed: there is
 * no block at ...}, and every macro step aborts the plan on failure, so the whole 12-block tunnel
 * (about a hundred expanded steps) was thrown away.
 *
 * <p>The test is end-to-end on purpose: it builds the real geometry, lets the real {@code MineJob}
 * clear the real blocker, then drives the <em>real</em> plan tool through the scripted model and asks
 * whether the step after the already-cleared one still ran. A test that only called
 * {@code mineAsTool} twice would prove the return string changed, not that the plan survived.
 *
 * <pre>
 *   MCAGENT_SELFCLEAR_TEST=true ... runServer                 # expect SELFCLEARTEST VERDICT: PASS
 *   MCAGENT_SELFCLEAR_TEST=true MCAGENT_SELFCLEARED=off ... runServer
 *                                                             # expect CONTROL-PASS: the plan dies
 * </pre>
 */
public final class SelfClearedStepSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/selfcleartest");
    private static final String BOT = "SelfClearBot";

    private final MinecraftServer server;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private ServerLevel level;
    private BlockPos origin;
    private BlockPos target;
    private BlockPos planTarget;
    private BlockPos blocker;
    private boolean started;
    private boolean finished;
    private int ticks;
    private int phase;
    private String mineResult = "";
    private int clearedAtTick = -1;
    /** Set when phase two wants the scripted model to hand out the plan. */
    private volatile boolean planArmed;

    private SelfClearedStepSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_SELFCLEAR_TEST"));
    }

    /** False only for the positive control: makes the already-cleared step fail again. */
    static boolean enabledFix() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_SELFCLEARED"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("SELFCLEARTEST: armed (fix {} )", enabledFix() ? "ON" : "OFF - positive control");
        return new SelfClearedStepSmokeTest(server);
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
        AgentBrain brain = Agent.brainManager() == null ? null
                : Agent.brainManager().get(handle.player().getUUID());
        if (brain == null) {
            this.finish(false, "no brain");
            return;
        }

        if (this.phase == 0) {
            this.phaseOne(handle, brain);
        } else {
            this.phaseTwo(brain);
        }
    }

    /**
     * Phase one builds the exact production state: ask for an occluded target, let the job clear the
     * blocker itself, and record which cell that was.
     */
    private void phaseOne(BotManager.BotHandle handle, AgentBrain brain) {
        if (this.mineResult.isEmpty()) {
            this.mineResult = brain.mineAsTool(this.target, 0);
            LOG.info("SELFCLEARTEST mine({}) -> {}", this.target.toShortString(), this.mineResult);
            this.blocker = parseBlocker(this.mineResult);
            if (this.blocker == null) {
                this.finish(false, "expected an occluded target with a named blocker, got '"
                        + this.mineResult + "'");
            }
            return;
        }
        boolean blockerGone = this.level.getBlockState(this.blocker).isAir();
        boolean targetGone = this.level.getBlockState(this.target).isAir();
        if (blockerGone && targetGone) {
            this.clearedAtTick = this.ticks;
            this.phase = 1;
            LOG.info("SELFCLEARTEST state ready : blocker {} removed by the bot's own job {} tick(s) "
                            + "ago, target {} gone; now queue a plan whose first step names {}",
                    this.blocker.toShortString(), this.ticks, this.target.toShortString(),
                    this.blocker.toShortString());
            // Ask for a plan whose step 1 is a no-op (already cleared) and whose step 2 is real work.
            this.planArmed = true;
            brain.requestDecisionNow();
            return;
        }
        if (this.ticks > 900) {
            this.finish(false, "blocker gone=" + blockerGone + " target gone=" + targetGone
                    + " after 900 ticks; mine result was '" + this.mineResult + "'");
        }
    }

    /**
     * Phase two asks the only question that matters: did the step after the already-cleared one run?
     * {@code planTarget} is solid stone, and the only way it stops being stone is the plan reaching
     * step 2 - which cannot happen if step 1 aborted the plan.
     */
    private void phaseTwo(AgentBrain brain) {
        boolean planTargetGone = this.level.getBlockState(this.planTarget).isAir();
        if (planTargetGone) {
            boolean queued = this.model != null && this.model.requestCount() > 0;
            this.finish(true, "the plan survived the already-cleared step: step 1 named "
                    + this.blocker.toShortString() + " (removed by the bot itself " + this.clearedAtTick
                    + " tick(s) earlier) and step 2 still ran - " + this.planTarget.toShortString()
                    + " is gone, model calls=" + (this.model == null ? 0 : this.model.requestCount())
                    + ", queued=" + queued);
            return;
        }
        if (this.ticks > 1400) {
            this.finish(false, "plan step 2 never ran: " + this.planTarget.toShortString()
                    + " is still " + this.level.getBlockState(this.planTarget).getBlock()
                    + " after 1400 ticks; step 1 named the already-cleared "
                    + this.blocker.toShortString()
                    + ", so the plan was aborted by 'failed: there is no block at ...'");
        }
    }

    /** Pull the blocker out of "clearing the real approach starting with X, Y, Z". */
    private static BlockPos parseBlocker(String message) {
        int at = message.indexOf("starting with ");
        if (at < 0) {
            return null;
        }
        String[] parts = message.substring(at + "starting with ".length()).split("[,\\s]+");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void begin() {
        try {
            // The plan is handed out only once the test has built the state: before that the bot would
            // be planning against geometry that does not exist yet, and an autonomous turn at spawn
            // would eat the single scripted answer.
            java.util.concurrent.atomic.AtomicBoolean served =
                    new java.util.concurrent.atomic.AtomicBoolean();
            this.model = new ScriptedLlmServer(body -> {
                if (this.planArmed && served.compareAndSet(false, true)) {
                    return ScriptedLlmServer.toolCall("selfclear_plan", "plan", """
                            {"steps":[
                              {"tool":"mine","arguments":{"x":%d,"y":%d,"z":%d}},
                              {"tool":"mine","arguments":{"x":%d,"y":%d,"z":%d}}
                            ]}
                            """.formatted(this.blocker.getX(), this.blocker.getY(), this.blocker.getZ(),
                            this.planTarget.getX(), this.planTarget.getY(), this.planTarget.getZ()));
                }
                return ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            this.finish(false, "could not start scripted endpoint: " + e.getMessage());
            return;
        }

        this.level = this.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 140;
        int z = spawn.getZ() + 140;
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.origin = new BlockPos(x, Math.max(spawn.getY() + 20, terrain + 12), z);

        // A solid stone hillside with a two-block pocket for the bot. The target sits two blocks east
        // at eye height with exactly one stone cell between, so the job has one blocker to clear
        // itself - the production shape. planTarget is the pocket's south wall: adjacent, in reach,
        // and only the plan's second step can remove it.
        for (int dx = -2; dx <= 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(this.origin, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(this.origin.above(), Blocks.AIR.defaultBlockState());
        this.blocker = this.origin.offset(1, 1, 0);
        this.target = this.origin.offset(2, 1, 0);
        this.planTarget = this.origin.offset(0, 1, 1);
        level.setBlockAndUpdate(this.blocker, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(this.target, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(this.planTarget, Blocks.STONE.defaultBlockState());

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, level, Vec3.atBottomCenterOf(this.origin), true);
        if (handle == null) {
            this.finish(false, "could not spawn bot");
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        if (!Agent.attachBrain(handle.player())) {
            this.finish(false, "could not attach scripted brain");
        }
        LOG.info("SELFCLEARTEST terrain   : origin={} blocker={} target={} planStep2={}",
                this.origin.toShortString(), this.blocker.toShortString(), this.target.toShortString(),
                this.planTarget.toShortString());
    }

    private void finish(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("SELFCLEARTEST result  : {}", detail);
        if (!enabledFix()) {
            // The control run: with the fix off the already-cleared step must fail and kill the plan,
            // so "step 2 never ran" is the expected outcome and proves this test can detect the bug.
            boolean reproduced = !pass;
            LOG.info("SELFCLEARTEST VERDICT : {}", reproduced
                    ? "CONTROL-PASS (fix disabled and the plan died on the already-cleared step, "
                            + "as expected)"
                    : "CONTROL-FAIL (fix disabled but the plan survived; this test cannot detect "
                            + "the bug it is for)");
        } else {
            LOG.info("SELFCLEARTEST VERDICT : {}", pass ? "PASS" : "FAIL");
        }

        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.origin != null) {
            for (int dx = -2; dx <= 4; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -2; dy <= 3; dy++) {
                        this.level.setBlockAndUpdate(this.origin.offset(dx, dy, dz),
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
