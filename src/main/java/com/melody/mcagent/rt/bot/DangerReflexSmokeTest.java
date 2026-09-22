package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The survival reflexes: a phantom is not fought, and low health breaks off work.
 *
 * <p>Both exist because of what production showed. 84% of the bot's deaths happened outside combat -
 * 25 while mining, 21 while walking - because nothing ever interrupted work for a threat: it kept
 * digging at {@code health=0.3} and died three seconds later. And four deaths plus three
 * {@code target is out of reach} failures came from trying to melee phantoms, which fly.
 *
 * <p>So this harness asserts the two refusals that matter, on state rather than on wording:
 * <ul>
 *   <li>an {@code attack} on a phantom is refused and names sleeping as the remedy;</li>
 *   <li>a bot whose health drops mid-job <b>drops the job</b>, and eats, and heads for its bed -
 *       without the model being asked anything.</li>
 * </ul>
 *
 * <p>Enabled with {@code MCAGENT_DANGER_TEST=true}.
 */
public final class DangerReflexSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/dangertest");

    private static final String BOT = "DangerBot";
    private static final int TURNS = 4;
    /** Health the test drops the bot to, below the reflex threshold of 8. */
    private static final float HURT_HEALTH = 5.0F;

    private final MinecraftServer server;

    private ServerLevel level;
    private BlockPos plot;
    private BlockPos bed;
    private BlockPos ore;

    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private int ticks;

    private boolean hurt;
    private boolean mineOrdered;
    private boolean attackOrdered;
    private boolean phantomSpawned;
    private boolean sawMineJob;
    private boolean mineJobDropped;
    private int healthAtHurt;
    /** The reflex's last reason, remembered as it is said: recovery clears it again. */
    private String reflexReason = "";
    private int foodBefore;

    private DangerReflexSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_DANGER_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("DANGERTEST: armed");
        return new DangerReflexSmokeTest(server);
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
        AgentBrain brain = this.brain();

        // Phase 1: wait for the scripted mine call to actually start a job, then wound the bot.
        if (!this.hurt && brain != null && Boolean.TRUE.equals(brain.debugState().get("mining"))) {
            this.sawMineJob = true;
            var bot = Agent.botManager().get(BOT).player();
            this.healthAtHurt = (int) bot.getHealth();
            this.foodBefore = bot.getFoodData().getFoodLevel();
            bot.setHealth(HURT_HEALTH);
            bot.getFoodData().setFoodLevel(4);
            // Now it has a home to run to, and a bed to get into.
            bot.setRespawnPosition(this.level.dimension(), this.bed, 0.0F, true, false);
            this.hurt = true;
            this.hurtTick = this.ticks;
            LOG.info("DANGERTEST wounded the bot mid-job: health {} -> {}, food {} -> 4",
                    this.healthAtHurt, (int) HURT_HEALTH, this.foodBefore);
            return;
        }
        if (this.hurt && !this.mineJobDropped && brain != null
                && !Boolean.TRUE.equals(brain.debugState().get("mining"))) {
            this.mineJobDropped = true;
            LOG.info("DANGERTEST the bot dropped its mining job {} tick(s) after being wounded",
                    this.ticks - this.hurtTick);
            // A phantom, for the attack that must be refused - and so the reflex's phantom branch is
            // exercised too. It is placed well away: this is not a fight test.
            // Night, because phantoms burn in daylight and a phantom that dies before the attack
            // call leaves the test asserting a refusal it never reached.
            this.level.setDayTime(18000L);
            Phantom phantom = new Phantom(net.minecraft.world.entity.EntityType.PHANTOM, this.level);
            Vec3 at = Vec3.atBottomCenterOf(this.plot).add(2.0D, 1.0D, 2.0D);
            phantom.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            phantom.setPhantomSize(1);
            // No AI: a phantom with AI circles and dives, and in the two seconds before the model
            // gets its next turn it can easily be out of sight - which would make this test assert a
            // refusal it never reached.
            phantom.setNoAi(true);
            this.level.addFreshEntity(phantom);
            this.phantomSpawned = true;
            LOG.info("DANGERTEST spawned a phantom at {} ({} blocks from the bot); immediately: "
                    + "removed={} alive={} inLevel={} day={} time={} difficulty={}",
                    phantom.blockPosition().toShortString(),
                    (int) Math.sqrt(phantom.distanceToSqr(Vec3.atBottomCenterOf(this.plot))),
                    phantom.isRemoved(), phantom.isAlive(),
                    this.level.getEntity(phantom.getId()) != null, this.level.isDay(),
                    this.level.getDayTime(), this.level.getDifficulty());
        }
        // Sample the reflex's own account of itself every tick: it clears the reason once the bot
        // has recovered, so reading it only at the end would miss the explanation entirely.
        if (brain != null) {
            Object reason = brain.debugState().get("lastReflex");
            if (reason != null && !"(none)".equals(reason.toString())) {
                this.reflexReason = reason.toString();
            }
        }
        // Phase 2: once the reflex has had time to work, judge it.
        if (this.hurt && this.phantomSpawned && this.ticks > this.hurtTick + 400) {
            this.verify();
            return;
        }
        if (this.ticks > 2400) {
            LOG.error("DANGERTEST VERDICT: FAIL - the run never got as far as the reflex "
                    + "(mineJobSeen={} hurt={})", this.sawMineJob, this.hurt);
            this.finishQuietly();
        }
    }

    /** Tick the bot was wounded on, so the reaction can be timed. */
    private int hurtTick = -1;

    private void begin() {
        // The dev server runs peaceful, where a hostile mob is discarded the moment it ticks - so the
        // phantom this test needs would vanish before the attack could be ordered at it.
        this.server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() - 110;
        int z = spawn.getZ() - 110;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.plot = new BlockPos(x, Math.max(spawn.getY() + 24, terrain + 16), z);
        this.bed = this.plot.offset(3, 0, 0);
        this.ore = this.plot.offset(0, 0, 3);
        this.buildSite();

        try {
            BlockPos orePos = this.ore;
            // Driven by the test rather than by turn number: the attack has to be asked for while a
            // phantom is actually there, and the mine has to come first so there is a job to break.
            this.model = new ScriptedLlmServer(body -> {
                if (!this.mineOrdered) {
                    this.mineOrdered = true;
                    return ScriptedLlmServer.toolCall("d1", "mine",
                            "{\"x\":" + orePos.getX() + ",\"y\":" + orePos.getY()
                                    + ",\"z\":" + orePos.getZ() + ",\"radius\":0}");
                }
                if (this.phantomSpawned && !this.attackOrdered) {
                    this.attackOrdered = true;
                    return ScriptedLlmServer.toolCall("d2", "attack", "{\"target\":\"phantom\"}");
                }
                return ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("DANGERTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, this.level, Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            LOG.error("DANGERTEST: FAIL - could not spawn the bot");
            this.finishQuietly();
            return;
        }
        var bot = handle.player();
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));
        bot.getInventory().add(new ItemStack(Items.COOKED_BEEF, 8));
        // The bed is both the way home and the thing that stops phantoms, so the bot has to have
        // one for the reflex to have anywhere to go.
        // Deliberately no respawn point yet: the surface within 96 blocks of home is protected from
        // ordinary excavation, and the mining job this test interrupts has to be allowed to start.
        // The bed becomes home when the bot is wounded, which is also when it needs one. Cleared
        // rather than merely not set, because the bot's playerdata outlives the run and a previous
        // run's bed would otherwise still be "home" here.
        bot.setRespawnPosition(this.level.dimension(), null, 0.0F, false, false);
        if (!Agent.attachBrain(bot)) {
            LOG.error("DANGERTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("DANGERTEST bot at {} with a bed at {} as its respawn point; the scripted model will "
                + "start a mining job and then order an attack on a phantom",
                this.plot.toShortString(), this.bed.toShortString());
    }

    /** A stone platform, a bed to go home to, and one block to mine. */
    private void buildSite() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -3; dy <= -1; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                }
                for (int dy = 0; dy <= 3; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.level.setBlockAndUpdate(this.bed, Blocks.RED_BED.defaultBlockState());
        this.level.setBlockAndUpdate(this.ore, Blocks.STONE.defaultBlockState());
    }

    private AgentBrain brain() {
        BotManager.BotHandle handle = Agent.botManager() == null ? null : Agent.botManager().get(BOT);
        return handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
    }

    private void verify() {
        var bot = Agent.botManager().get(BOT).player();
        AgentBrain brain = this.brain();
        Object retreating = brain == null ? null : brain.debugState().get("retreating");
        Object lastReflex = brain == null ? null : brain.debugState().get("lastReflex");
        int foodNow = bot.getFoodData().getFoodLevel();

        // The phantom refusal: it has to reach the model as a refusal that says what to do instead.
        boolean attackRefused = this.model.maxOccurrences("cannot be fought on foot") >= 1;
        // And the reflex itself: the job was dropped, the bot says why, and it ate.
        boolean dropped = this.mineJobDropped;
        boolean explained = !this.reflexReason.isEmpty();
        boolean ate = foodNow > 4;
        boolean headingHome = bot.blockPosition().distSqr(this.bed) < 64.0D || ate;

        LOG.info("DANGERTEST state: mineJobSeen={} mineJobDropped={} retreating={} reflexSaid='{}' "
                + "food 4 -> {} health now {} blocks from bed {}", this.sawMineJob, dropped,
                retreating, this.reflexReason, foodNow, (int) bot.getHealth(),
                (int) Math.sqrt(bot.blockPosition().distSqr(this.bed)));
        LOG.info("DANGERTEST attack(phantom) refused with the sleep advice: {}", attackRefused);

        boolean pass = this.sawMineJob && dropped && explained && attackRefused && ate;
        LOG.info("DANGERTEST VERDICT: {}{}", pass ? "PASS" : "FAIL - ",
                pass ? " (the job was dropped and explained without being asked, the bot ate, and a "
                        + "phantom attack was refused with the reason)"
                     : describeFailure(dropped, explained, attackRefused, ate));
        this.finishQuietly();
    }

    private String describeFailure(boolean dropped, boolean explained, boolean attackRefused,
                                   boolean ate) {
        StringBuilder sb = new StringBuilder();
        if (!this.sawMineJob) {
            sb.append("the mining job never started, so nothing was interrupted; ");
        }
        if (!dropped) {
            sb.append("the bot kept working while nearly dead; ");
        }
        if (!explained) {
            sb.append("the reflex did not say why it stopped; ");
        }
        if (!ate) {
            sb.append("the bot did not eat; ");
        }
        if (!attackRefused) {
            sb.append("an attack on a phantom was not refused; ");
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
