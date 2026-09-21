package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.action.Containers;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.perception.ObservationBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Behaviour-level proof that combat is pursuit plus repeated cooled-down attacks, not one swing.
 * Enabled only with {@code MCAGENT_COMBAT_TEST=true}.
 */
public final class CombatSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/combat-test");
    private static final String BOT = "CombatBot";

    private final MinecraftServer server;
    private ServerLevel level;
    private ScriptedLlmServer model;
    private String[] savedSettings;
    private Zombie zombie;
    private Vec3 start;
    private double maxTravel;
    private int ticks;
    private boolean started;
    private boolean finished;
    private boolean observationOk;
    private boolean containerOk;
    private boolean combatStarted;
    private Difficulty savedDifficulty;

    private CombatSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_COMBAT_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("COMBATTEST: armed");
        return new CombatSmokeTest(server);
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
        AgentBrain brain = handle == null || Agent.brainManager() == null
                ? null : Agent.brainManager().get(handle.player().getUUID());
        if (handle == null || brain == null) {
            this.report(false, "the bot or its brain disappeared");
            return;
        }
        if (!this.combatStarted) {
            // Newly added entities do not become queryable through ServerLevel#getEntities until
            // the entity manager has completed its next update. Give it a few ticks so this checks
            // the normal observation path rather than addFreshEntity's staging window.
            if (this.ticks < 20) {
                return;
            }
            this.startFight(handle, brain);
            return;
        }
        this.maxTravel = Math.max(this.maxTravel, handle.player().position().distanceTo(this.start));

        if (!this.zombie.isAlive() || this.zombie.isRemoved()) {
            // Give the brain one tick to observe the death and close the job.
            if (!brain.isFighting()) {
                boolean pass = this.observationOk && this.containerOk && this.maxTravel >= 3.0D;
                this.report(pass, "target dead, pursuit="
                        + String.format(java.util.Locale.ROOT, "%.2f", this.maxTravel) + " blocks");
            }
            return;
        }
        if (this.ticks > 400) {
            this.report(false, "target still alive after 20 seconds (hp=" + this.zombie.getHealth()
                    + ", action=" + brain.currentAction() + ")");
        }
    }

    private void begin() {
        this.level = this.server.overworld();
        this.savedDifficulty = this.server.getWorldData().getDifficulty();
        this.server.setDifficulty(Difficulty.NORMAL, true);
        this.level.setDayTime(18000L); // Keep the zombie from burning; this is an isolated dev world.
        BlockPos base = this.level.getSharedSpawnPos().offset(40, 0, 40);
        this.prepareGround(base);

        try {
            this.savedSettings = ScriptedLlmServer.settings();
            this.model = new ScriptedLlmServer(request -> ScriptedLlmServer.silent());
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("COMBATTEST: could not start the scripted model", e);
            this.report(false, "scripted model did not start");
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(BOT, this.level,
                Vec3.atBottomCenterOf(base), false);
        if (handle == null || !Agent.attachBrain(handle.player())) {
            this.report(false, "bot did not spawn with a brain");
            return;
        }
        this.containerOk = this.verifyContainerTransfers(handle.player(), base);
        handle.player().getInventory().add(new ItemStack(Items.IRON_SWORD));
        Actions.holdItem(handle.player(), "minecraft:iron_sword");
        this.start = handle.player().position();

        this.zombie = EntityType.ZOMBIE.create(this.level);
        if (this.zombie == null) {
            this.report(false, "zombie could not be created");
            return;
        }
        this.zombie.setNoAi(true);
        this.zombie.setHealth(12.0F); // More than one iron-sword hit: repeated attacks are required.
        this.zombie.moveTo(base.getX() + 8.5D, base.getY(), base.getZ() + 0.5D, 0.0F, 0.0F);
        this.level.addFreshEntity(this.zombie);
    }

    /** Wait one full entity tick before observing, so the freshly spawned target is tracked. */
    private void startFight(BotManager.BotHandle handle, AgentBrain brain) {
        String observation = ObservationBuilder.describe(handle.player(), 24);
        String where = this.zombie.blockPosition().toShortString();
        boolean time = observation.contains("Time:");
        boolean facing = observation.contains("Facing:");
        boolean danger = observation.contains("DANGER:");
        boolean coordinates = observation.contains("zombie") && observation.contains(" at " + where);
        boolean durability = observation.contains("durability");
        this.observationOk = time && facing && danger && coordinates && durability;
        LOG.info("COMBATTEST observation: time={} facing={} danger={} entityCoordinates={} durability={}",
                time, facing, danger, coordinates, durability);

        String outcome = brain.attackAsTool(this.zombie);
        this.combatStarted = true;
        LOG.info("COMBATTEST start: {}", outcome);
        if (!brain.isFighting()) {
            this.report(false, "combat job did not start: " + outcome);
        }
    }

    private void prepareGround(BlockPos base) {
        for (int dx = -3; dx <= 14; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 4; dy++) {
                    this.level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
                this.level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** Regression checks for partial transfers: no duplication, no overflow dropped on the floor. */
    private boolean verifyContainerTransfers(net.minecraft.server.level.ServerPlayer bot, BlockPos base) {
        BlockPos chestPos = base.offset(0, 0, 2);
        this.level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        Container chest = Containers.containerAt(bot, chestPos);
        if (chest == null) {
            LOG.error("COMBATTEST container check: chest was not available");
            return false;
        }
        Containers.markOpened(bot, chestPos);

        // A full pack must leave the requested stack in the chest, not throw it into the world.
        bot.getInventory().clearContent();
        for (int slot = 0; slot < Math.min(36, bot.getInventory().getContainerSize()); slot++) {
            bot.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        chest.clearContent();
        chest.setItem(1, new ItemStack(Items.GOLD_INGOT, 10));
        int dropsBefore = this.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(chestPos).inflate(4.0D)).size();
        Actions.Result fullWithdraw = Containers.withdraw(bot, chestPos, "minecraft:gold_ingot", 10);
        int dropsAfter = this.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(chestPos).inflate(4.0D)).size();
        boolean overflowStayed = !fullWithdraw.success()
                && chest.getItem(1).is(Items.GOLD_INGOT)
                && chest.getItem(1).getCount() == 10
                && dropsAfter == dropsBefore;

        // Four free places in a matching stack must move exactly four. The old path inserted those
        // four, returned "not everything fit", and forgot to shrink the source - duplicating them.
        bot.getInventory().clearContent();
        chest.clearContent();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        chest.setItem(0, new ItemStack(Items.DIAMOND, 60));
        bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
        Actions.Result partialDeposit = Containers.deposit(bot, chestPos, "minecraft:diamond", 10);
        boolean partialExact = partialDeposit.success()
                && chest.getItem(0).getCount() == 64
                && bot.getInventory().getItem(0).getCount() == 6;

        bot.getInventory().clearContent();
        LOG.info("COMBATTEST container transfers: full-withdraw-safe={} partial-deposit-exact={} "
                + "(withdraw='{}', deposit='{}')", overflowStayed, partialExact,
                fullWithdraw.message(), partialDeposit.message());
        return overflowStayed && partialExact;
    }

    private void report(boolean pass, String detail) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        LOG.info("COMBATTEST ================= RESULT =================");
        LOG.info("COMBATTEST observation actionable : {}", this.observationOk);
        LOG.info("COMBATTEST container transfers     : {}", this.containerOk);
        LOG.info("COMBATTEST pursuit distance       : {}", String.format(
                java.util.Locale.ROOT, "%.2f", this.maxTravel));
        LOG.info("COMBATTEST detail                 : {}", detail);
        LOG.info("COMBATTEST VERDICT                : {}", pass ? "PASS" : "FAIL");
        LOG.info("COMBATTEST ==========================================");

        if (this.zombie != null && !this.zombie.isRemoved()) {
            this.zombie.discard();
        }
        if (Agent.botManager() != null) {
            Agent.botManager().remove(BOT);
        }
        if (this.savedDifficulty != null) {
            this.server.setDifficulty(this.savedDifficulty, true);
        }
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        if (this.model != null) {
            this.model.close();
        }
        this.server.halt(false);
    }
}
