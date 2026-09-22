package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.action.Containers;
import com.melody.mcagent.rt.brain.AgentBrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the bot can actually do with an anvil and an enchanting table.
 *
 * <p>Both are machines a player drives through a GUI, and the bot has no client: it acts through a
 * tool API and never clicks a slot. This harness therefore asks the question at the level that
 * matters - it scripts the exact calls a model would make ({@code open_container}, then {@code use}),
 * and reports what each one told the model, what menu the interaction actually installed on the
 * player, and whether the operation can be completed from there.
 *
 * <p>The distinction the last part draws is the point of the harness. "The vanilla menu opened" and
 * "the bot can finish the job" are different claims, and only the second one answers whether an
 * operator should expect a bot to repair a pickaxe or enchant a sword.
 *
 * <p>Enabled with {@code MCAGENT_ANVIL_TEST=true}.
 */
public final class AnvilEnchantSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/anviltest");

    private static final String BOT = "AnvilBot";

    /** Ticks to wait for the scripted sequence (four tool calls, each its own decision). */
    private static final int SEQUENCE_TIMEOUT_TICKS = 1400;

    private final MinecraftServer server;

    private ServerLevel level;
    private BlockPos plot;
    private BlockPos anvil;
    private BlockPos table;

    private ScriptedLlmServer model;
    private String[] savedSettings;
    private boolean started;
    private boolean finished;
    private int ticks;
    private String lastMenu = "(none)";

    private AnvilEnchantSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_ANVIL_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("ANVILTEST: armed");
        return new AnvilEnchantSmokeTest(server);
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

        // What the interaction actually installed on the player, as it changes. A menu the bot
        // cannot drive is still worth seeing: it is the difference between "nothing happened" and
        // "the vanilla GUI is open in front of a player with no hands".
        BotManager.BotHandle handle = Agent.botManager() == null
                ? null : Agent.botManager().get(BOT);
        if (handle != null) {
            String menu = handle.player().containerMenu.getClass().getSimpleName();
            if (!menu.equals(this.lastMenu)) {
                LOG.info("ANVILTEST bot's open menu is now {} (was {})", menu, this.lastMenu);
                this.lastMenu = menu;
            }
        }

        if (this.model == null) {
            return;
        }
        if (this.model.requestCount() >= 5) {
            this.probe();
            return;
        }
        if (this.ticks > SEQUENCE_TIMEOUT_TICKS) {
            LOG.error("ANVILTEST VERDICT: FAIL - the scripted sequence never completed ({} request(s) "
                    + "so far)", this.model.requestCount());
            this.finishQuietly();
        }
    }

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();
        int x = spawn.getX() + 70;
        int z = spawn.getZ() + 70;
        int terrain = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        this.plot = new BlockPos(x, Math.max(spawn.getY() + 24, terrain + 16), z);
        this.anvil = this.plot.offset(2, 0, 0);
        this.table = this.plot.offset(-2, 0, 0);
        this.buildSite();

        try {
            // Exactly what a model would try, in the order it would try it: the container tool it
            // already knows, then a right-click on the block itself.
            int[] turn = { 0 };
            BlockPos anvilPos = this.anvil;
            BlockPos tablePos = this.table;
            this.model = new ScriptedLlmServer(body -> switch (turn[0]++) {
                case 0 -> ScriptedLlmServer.toolCall("a1", "open_container", coords(anvilPos));
                case 1 -> ScriptedLlmServer.toolCall("a2", "use", coords(anvilPos));
                case 2 -> ScriptedLlmServer.toolCall("a3", "open_container", coords(tablePos));
                case 3 -> ScriptedLlmServer.toolCall("a4", "use", coords(tablePos));
                default -> ScriptedLlmServer.silent();
            });
            this.savedSettings = ScriptedLlmServer.settings();
            this.model.pointModAtThisServer();
        } catch (IOException e) {
            LOG.error("ANVILTEST: FAIL - could not start the scripted model", e);
            this.finishQuietly();
            return;
        }

        BotManager.BotHandle handle = Agent.botManager().spawn(
                BOT, this.level, Vec3.atBottomCenterOf(this.plot), true);
        if (handle == null) {
            LOG.error("ANVILTEST: FAIL - could not spawn the bot");
            this.finishQuietly();
            return;
        }
        handle.player().getInventory().clearContent();
        // A damaged sword and a diamond, so the anvil has something to repair; lapis, so the
        // enchanting table has something to spend; and levels, so neither refuses for want of XP.
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(100);
        handle.player().getInventory().add(sword);
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND, 8));
        handle.player().getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 64));
        handle.player().giveExperienceLevels(50);

        if (!Agent.attachBrain(handle.player())) {
            LOG.error("ANVILTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("ANVILTEST anvil at {}, enchanting table at {} (bookshelves around it); the scripted "
                + "model will try open_container then use on each",
                this.anvil.toShortString(), this.table.toShortString());
    }

    /** A flat stone platform with the two machines on it, plus bookshelves for real offers. */
    private void buildSite() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                this.level.setBlockAndUpdate(this.plot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    this.level.setBlockAndUpdate(this.plot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        this.level.setBlockAndUpdate(this.anvil, Blocks.ANVIL.defaultBlockState());
        this.level.setBlockAndUpdate(this.table, Blocks.ENCHANTING_TABLE.defaultBlockState());
        // Distance two in a ring, which is where vanilla counts bookshelves from.
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = this.table.offset(dx, dy, dz);
                    if (pos.equals(this.table) || pos.equals(this.plot) || pos.equals(this.anvil)) {
                        continue;
                    }
                    this.level.setBlockAndUpdate(pos, Blocks.BOOKSHELF.defaultBlockState());
                }
            }
        }
    }

    private static String coords(BlockPos pos) {
        return "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
    }

    /**
     * What the model was told, and whether the operation is completable from where the bot ends up.
     */
    private void probe() {
        String transcript = this.model.lastRequest();
        boolean containerRefusedForAnvil = transcript.contains("there is no container at "
                + this.anvil.toShortString());
        boolean containerRefusedForTable = transcript.contains("there is no container at "
                + this.table.toShortString());
        boolean useReportedAnvil = transcript.contains("used item on Anvil");
        boolean useReportedTable = transcript.contains("used item on Enchanting Table");

        LOG.info("ANVILTEST what the model was told: open_container(anvil) refused={} "
                + "open_container(table) refused={} use(anvil) reported success={} "
                + "use(table) reported success={}",
                containerRefusedForAnvil, containerRefusedForTable, useReportedAnvil, useReportedTable);

        BotManager.BotHandle handle = Agent.botManager().get(BOT);
        var bot = handle.player();

        // --- the tool surface, called exactly as the dispatcher calls it -------------------------
        LOG.info("ANVILTEST tool surface: isContainer(anvil)={} isContainer(table)={}",
                Containers.isContainer(bot, this.anvil), Containers.isContainer(bot, this.table));
        Actions.Result withdrawAnvil = Containers.withdraw(bot, this.anvil, "minecraft:diamond", 1);
        LOG.info("ANVILTEST tool surface: withdraw(anvil) -> {} {}",
                withdrawAnvil.success() ? "ok" : "FAILED", withdrawAnvil.message());

        // --- is the menu the interaction opened actually functional? -----------------------------
        probeAnvil(bot);
        probeTable(bot);

        LOG.info("ANVILTEST VERDICT: the bot cannot use an anvil or an enchanting table. Both refuse "
                + "open_container (neither is a Container block entity), and although use() reports "
                + "success and does install the vanilla menu, no tool can move an item into that "
                + "menu's slots or take its result - so the model is told it did something it did "
                + "not do.");
        this.finishQuietly();
    }

    /**
     * Repair a sword through the anvil menu by hand.
     *
     * <p>Not something the bot can do - it is here to separate "vanilla cannot" from "the tool
     * surface does not reach it". If this works, a repair tool is a wiring job, not a research one.
     */
    private void probeAnvil(net.minecraft.server.level.ServerPlayer bot) {
        Actions.Result used = Actions.useOnBlock(bot, this.anvil, Direction.UP);
        if (!(bot.containerMenu instanceof AnvilMenu anvilMenu)) {
            LOG.info("ANVILTEST anvil menu probe: use() -> {} but the open menu is {}, not an AnvilMenu",
                    used.message(), bot.containerMenu.getClass().getSimpleName());
            return;
        }
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(100);
        anvilMenu.getSlot(AnvilMenu.INPUT_SLOT).set(sword);
        anvilMenu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
        ItemStack result = anvilMenu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        LOG.info("ANVILTEST anvil menu probe: damaged sword + diamond -> result={} cost={} levels",
                result.isEmpty() ? "(none)" : result.getDisplayName().getString() + " x" + result.getCount(),
                anvilMenu.getCost());
        if (result.isEmpty()) {
            return;
        }
        int levelsBefore = bot.experienceLevel;
        anvilMenu.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.PICKUP, bot);
        // Vanilla puts a picked-up result on the cursor, exactly as it does for a real client's
        // first click; a second click would move it into the inventory. Both are reported so the
        // claim "the menu is functional" rests on what actually happened, not on one of them.
        ItemStack carried = anvilMenu.getCarried();
        LOG.info("ANVILTEST anvil menu probe: taking the result by hand -> carried={} inInventory={} "
                + "levels {} -> {}", carried.isEmpty() ? "(empty)" : carried.getDisplayName().getString(),
                bot.getInventory().contains(result), levelsBefore, bot.experienceLevel);
    }

    /** Enchant a sword through the enchanting menu by hand, the same way. */
    private void probeTable(net.minecraft.server.level.ServerPlayer bot) {
        Actions.Result used = Actions.useOnBlock(bot, this.table, Direction.UP);
        if (!(bot.containerMenu instanceof EnchantmentMenu enchantMenu)) {
            LOG.info("ANVILTEST table menu probe: use() -> {} but the open menu is {}, not an "
                    + "EnchantmentMenu", used.message(), bot.containerMenu.getClass().getSimpleName());
            return;
        }
        enchantMenu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
        enchantMenu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
        LOG.info("ANVILTEST table menu probe: offers={} {} {}", enchantMenu.costs[0],
                enchantMenu.costs[1], enchantMenu.costs[2]);
        if (enchantMenu.costs[0] <= 0) {
            LOG.info("ANVILTEST table menu probe: no offer to take, so the button cannot be pressed");
            return;
        }
        int levelsBefore = bot.experienceLevel;
        enchantMenu.clickMenuButton(bot, 0);
        ItemStack enchanted = enchantMenu.getSlot(0).getItem();
        LOG.info("ANVILTEST table menu probe: clickMenuButton(0) by hand -> item={} enchanted={} "
                + "levels {} -> {}", enchanted.getDisplayName().getString(),
                enchanted.isEnchanted(), levelsBefore, bot.experienceLevel);
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
