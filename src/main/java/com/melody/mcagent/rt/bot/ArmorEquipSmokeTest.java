package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armour goes on the body, not in the hand.
 *
 * <p>Production is the reason this harness exists. The bot crafted a full iron set and a diamond
 * helmet, then called {@code hold} for each piece - 43 times across the corpus - because that is the
 * only equipment verb it had. {@code hold} filled the main hand, so it fought zombies holding iron
 * boots, took 27 deaths by Zombie, and eventually wrote itself the note "armor cannot be equipped
 * with my tools: hold". The note was correct.
 *
 * <p>So this test scripts exactly what production did: the same five calls, in the same order, with
 * the same items. What it asserts is where they ended up - and specifically that the main hand is
 * <em>not</em> holding a helmet, which is the bug.
 *
 * <p>Enabled with {@code MCAGENT_ARMOR_TEST=true}.
 */
public final class ArmorEquipSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/armortest");

    private static final String BOT = "ArmorBot";
    private static final int TURNS = 6;

    private final MinecraftServer server;

    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private boolean nudged;
    private int ticks;

    private ArmorEquipSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_ARMOR_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("ARMORTEST: armed");
        return new ArmorEquipSmokeTest(server);
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
        if (this.model.requestCount() >= TURNS) {
            this.verify();
            return;
        }
        if (this.ticks > 1200) {
            LOG.error("ARMORTEST VERDICT: FAIL - the scripted sequence stalled after {} request(s)",
                    this.model.requestCount());
            this.finishQuietly();
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        var spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + 110;
        int z = spawn.getZ() - 110;
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        var plot = new net.minecraft.core.BlockPos(x, Math.max(spawn.getY() + 24, terrain + 16), z);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(plot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(plot.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(plot.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
            }
        }

        try {
            // Exactly the calls production made, in the order it made them.
            int[] turn = { 0 };
            this.model = new ScriptedLlmServer(body -> switch (turn[0]++) {
                case 0 -> ScriptedLlmServer.toolCall("e1", "hold", "{\"item\":\"iron_helmet\"}");
                case 1 -> ScriptedLlmServer.toolCall("e2", "hold", "{\"item\":\"iron_chestplate\"}");
                case 2 -> ScriptedLlmServer.toolCall("e3", "hold", "{\"item\":\"iron_leggings\"}");
                case 3 -> ScriptedLlmServer.toolCall("e4", "hold", "{\"item\":\"iron_boots\"}");
                case 4 -> ScriptedLlmServer.toolCall("e5", "hold", "{\"item\":\"shield\"}");
                default -> ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("ARMORTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, level, Vec3.atBottomCenterOf(plot), true);
        if (handle == null) {
            LOG.error("ARMORTEST: FAIL - could not spawn the bot");
            this.finishQuietly();
            return;
        }
        handle.player().getInventory().clearContent();
        handle.player().getInventory().add(new ItemStack(Items.IRON_HELMET));
        handle.player().getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
        handle.player().getInventory().add(new ItemStack(Items.IRON_LEGGINGS));
        handle.player().getInventory().add(new ItemStack(Items.IRON_BOOTS));
        handle.player().getInventory().add(new ItemStack(Items.SHIELD));
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        if (!Agent.attachBrain(handle.player())) {
            LOG.error("ARMORTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("ARMORTEST bot carries a full iron set, a shield and a sword; the scripted model will "
                + "call hold() for each piece exactly as production did");
    }

    private void verify() {
        var bot = Agent.botManager().get(BOT).player();
        ItemStack head = bot.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chest = bot.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack legs = bot.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack feet = bot.getItemBySlot(EquipmentSlot.FEET);
        ItemStack offhand = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        ItemStack mainHand = bot.getMainHandItem();

        LOG.info("ARMORTEST worn: head={} chest={} legs={} feet={} offhand={} mainhand={}",
                head.getHoverName().getString(), chest.getHoverName().getString(),
                legs.getHoverName().getString(), feet.getHoverName().getString(),
                offhand.isEmpty() ? "(empty)" : offhand.getHoverName().getString(),
                mainHand.isEmpty() ? "(empty)" : mainHand.getHoverName().getString());

        boolean worn = head.is(Items.IRON_HELMET) && chest.is(Items.IRON_CHESTPLATE)
                && legs.is(Items.IRON_LEGGINGS) && feet.is(Items.IRON_BOOTS);
        boolean shieldOffhand = offhand.is(Items.SHIELD);
        // The bug itself: armour in the main hand, where it does nothing at all.
        boolean handFree = !mainHand.is(Items.IRON_HELMET) && !mainHand.is(Items.IRON_CHESTPLATE)
                && !mainHand.is(Items.IRON_LEGGINGS) && !mainHand.is(Items.IRON_BOOTS);
        boolean armourValue = bot.getArmorValue() > 0;

        LOG.info("ARMORTEST armour points: {} (iron set is 15)", bot.getArmorValue());
        boolean pass = worn && shieldOffhand && handFree && armourValue;
        LOG.info("ARMORTEST VERDICT: {}{}", pass ? "PASS" : "FAIL - ",
                pass ? " (the whole set is worn, the shield is in the offhand, and the hand is free)"
                     : describeFailure(worn, shieldOffhand, handFree, armourValue));
        this.finishQuietly();
    }

    private String describeFailure(boolean worn, boolean shieldOffhand, boolean handFree,
                                   boolean armourValue) {
        StringBuilder sb = new StringBuilder();
        if (!worn) {
            sb.append("not every piece reached its armour slot; ");
        }
        if (!shieldOffhand) {
            sb.append("the shield is not in the offhand; ");
        }
        if (!handFree) {
            sb.append("armour is still in the main hand, which is the production bug; ");
        }
        if (!armourValue) {
            sb.append("the bot has 0 armour points, so nothing is actually protecting it; ");
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
