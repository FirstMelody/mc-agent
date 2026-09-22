package com.melody.mcagent.rt.bot;

import java.io.IOException;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.action.Containers;
import com.melody.mcagent.rt.action.Stations;
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
 * The anvil and the enchanting table, driven through the real tool API.
 *
 * <p>Both are machines a player works through a GUI, and a bot has no client: it never clicks a slot.
 * The first version of this harness asked only what happened when a model reached for the tools it
 * already had, and the answer was bad - {@code open_container} refused both, and {@code use} reported
 * "used item on Anvil" while nothing whatsoever happened, so a bot told to save a pickaxe would say
 * it had and then break it on the next block.
 *
 * <p>So it now scripts the calls a model makes with the tools that exist for this: repair a nearly
 * broken pickaxe, enchant a sword, and two cases that must be refused honestly rather than silently
 * (an item with no durability, and a tool with nothing to repair it with). What is asserted is the
 * state afterwards, not the wording: durability really up, item really still carried, levels really
 * spent, sword really enchanted.
 *
 * <p>Enabled with {@code MCAGENT_ANVIL_TEST=true}.
 */
public final class AnvilEnchantSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/anviltest");

    private static final String BOT = "AnvilBot";
    private static final String PICKAXE = "diamond_pickaxe";
    private static final String SWORD = "diamond_sword";
    /** A diamond pickaxe holds 1561; this one is four hits from breaking. */
    private static final int PICKAXE_DAMAGE = 1549;

    /** Ticks to wait for the scripted sequence (five tool calls, each its own decision). */
    private static final int SEQUENCE_TIMEOUT_TICKS = 1600;
    /** Turns the scripted model has been asked for by the time the run is judged. */
    private static final int TURNS = 7;

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
    private int levelsBefore;
    /** What the bot actually starts with: an earlier run can leave items lying on the plot. */
    private int lapisBefore;

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

        if (this.model == null) {
            return;
        }
        if (this.model.requestCount() >= TURNS) {
            this.verify();
            return;
        }
        if (this.ticks > SEQUENCE_TIMEOUT_TICKS) {
            LOG.error("ANVILTEST VERDICT: FAIL - the scripted sequence stalled after {} request(s)",
                    this.model.requestCount());
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
            int[] turn = { 0 };
            BlockPos anvilPos = this.anvil;
            BlockPos tablePos = this.table;
            this.model = new ScriptedLlmServer(body -> switch (turn[0]++) {
                // The tool a model reaches for first, and still the wrong one for a machine.
                case 0 -> ScriptedLlmServer.toolCall("a1", "open_container", coords(anvilPos));
                // A bare right-click: it opens the menu and must say that this is not enough.
                case 1 -> ScriptedLlmServer.toolCall("a2", "use", coords(anvilPos));
                case 2 -> ScriptedLlmServer.toolCall("a3", "repair",
                        coords(anvilPos, "\"item\":\"" + PICKAXE + "\""));
                case 3 -> ScriptedLlmServer.toolCall("a4", "enchant",
                        coords(tablePos, "\"item\":\"" + SWORD + "\",\"offer\":1"));
                // A stick has no durability: the anvil cannot help and must say so.
                case 4 -> ScriptedLlmServer.toolCall("a5", "repair",
                        coords(anvilPos, "\"item\":\"stick\""));
                // A damaged shovel with no iron ingot and no second shovel: nothing to repair it with.
                case 5 -> ScriptedLlmServer.toolCall("a6", "repair",
                        coords(anvilPos, "\"item\":\"iron_shovel\""));
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
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamageValue(PICKAXE_DAMAGE);
        int pickaxeMax = pickaxe.getMaxDamage();
        handle.player().getInventory().add(pickaxe);
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND, 8));
        handle.player().getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
        shovel.setDamageValue(100);
        handle.player().getInventory().add(shovel);
        handle.player().getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 8));
        handle.player().getInventory().add(new ItemStack(Items.STICK, 4));
        handle.player().setExperienceLevels(40);
        this.levelsBefore = handle.player().experienceLevel;
        this.lapisBefore = count(handle.player(), Items.LAPIS_LAZULI);

        if (!Agent.attachBrain(handle.player())) {
            LOG.error("ANVILTEST: FAIL - could not attach a brain");
            this.finishQuietly();
            return;
        }
        LOG.info("ANVILTEST anvil at {}, enchanting table at {}; bot carries a {} at {}/{} durability, "
                + "8 diamonds, a sword, an iron shovel, 8 lapis and {} levels",
                this.anvil.toShortString(), this.table.toShortString(), PICKAXE,
                PICKAXE_DAMAGE, pickaxeMax, this.levelsBefore);
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
        // An earlier run of this harness can leave dropped items here: vanilla's EnchantmentMenu
        // drops whatever is still in its slots when it closes, and a bot standing on the plot would
        // then pick them up and quietly start the run with more lapis than the test handed it.
        for (net.minecraft.world.entity.item.ItemEntity dropped : this.level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(this.plot).inflate(8.0D))) {
            dropped.discard();
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

    /** A complete JSON arguments object for a call that needs nothing but coordinates. */
    private static String coords(BlockPos pos) {
        return coords(pos, "");
    }

    /**
     * The same, with extra fields spliced in before the closing brace.
     *
     * <p>Built as one object on purpose. An earlier version of this harness left the brace off, so
     * every call arrived with no arguments at all and the tools read the position as 0, 0, 0 - the
     * test then failed for a reason that had nothing to do with the code under test.
     */
    private static String coords(BlockPos pos, String extraFields) {
        return "{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                + (extraFields.isEmpty() ? "" : "," + extraFields) + "}";
    }

    /**
     * Judge the state, not the prose: what the bot is carrying and what it cost.
     */
    private void verify() {
        BotManager.BotHandle handle = Agent.botManager().get(BOT);
        var bot = handle.player();
        ItemStack pickaxe = carried(bot, Items.DIAMOND_PICKAXE);
        ItemStack sword = carried(bot, Items.DIAMOND_SWORD);
        int levelsSpent = this.levelsBefore - bot.experienceLevel;
        int lapisLeft = count(bot, Items.LAPIS_LAZULI);

        boolean pickaxeSurvived = !pickaxe.isEmpty();
        boolean repaired = pickaxeSurvived && pickaxe.getDamageValue() < PICKAXE_DAMAGE;
        boolean repairedFully = pickaxeSurvived && pickaxe.getDamageValue() == 0;
        boolean enchanted = !sword.isEmpty() && sword.isEnchanted();
        boolean paid = levelsSpent > 0;
        boolean lapisSpent = lapisLeft < this.lapisBefore;

        LOG.info("ANVILTEST state: pickaxe={} (damage {}) sword={} levels {} -> {} lapis {} -> {}",
                pickaxeSurvived ? "carried" : "LOST", pickaxeSurvived ? pickaxe.getDamageValue() : -1,
                enchanted ? "enchanted" : "plain", this.levelsBefore, bot.experienceLevel,
                this.lapisBefore, lapisLeft);

        // The two refusals have to reach the model as refusals: an item with no durability, and a
        // tool with nothing to repair it with. Silent success here is how the pickaxe got broken.
        // Counted across every request, not just the last one: the transcript is compacted as it
        // grows, so a result five turns old is collapsed to a summary and a lastRequest() check
        // would fail for a reason that has nothing to do with the tool. (This caught the author.)
        boolean stickRefused = this.model.maxOccurrences("has no durability") >= 1;
        boolean nothingToRepairWith = this.model.maxOccurrences("you have nothing to repair") >= 1;
        boolean containerStillRefused = this.model.maxOccurrences("there is no container at") >= 1;
        // A right-click that opens a GUI the bot cannot click must not read as "job done".
        //
        // The phrase has no apostrophes on purpose: these are matched against the raw request body,
        // and GSON escapes them to \u0027, so an assertion containing one can never match however
        // right the tool is. (It cost a run to notice.)
        boolean usePointsAtTheTool =
                this.model.maxOccurrences("tool to actually repair an item") >= 1;

        LOG.info("ANVILTEST refusals: stick={} no-material={} open_container(anvil)={} "
                + "use(anvil)-points-at-repair={}", stickRefused, nothingToRepairWith,
                containerStillRefused, usePointsAtTheTool);

        boolean pass = repaired && pickaxeSurvived && enchanted && paid && lapisSpent
                && stickRefused && nothingToRepairWith && containerStillRefused && usePointsAtTheTool;
        LOG.info("ANVILTEST VERDICT: {}{}", pass ? "PASS" : "FAIL - ",
                pass ? " (repair " + (repairedFully ? "to full durability" : "improved durability")
                        + ", enchant applied, both refusals honest)"
                     : describeFailure(repaired, pickaxeSurvived, enchanted, paid, lapisSpent,
                             stickRefused, nothingToRepairWith, usePointsAtTheTool));
        this.finishQuietly();
    }

    private static String describeFailure(boolean repaired, boolean survived, boolean enchanted,
                                          boolean paid, boolean lapisSpent, boolean stickRefused,
                                          boolean nothingToRepairWith, boolean usePointsAtTheTool) {
        StringBuilder sb = new StringBuilder();
        if (!survived) {
            sb.append("the pickaxe was lost; ");
        } else if (!repaired) {
            sb.append("the pickaxe was not repaired; ");
        }
        if (!enchanted) {
            sb.append("the sword was not enchanted; ");
        }
        if (!paid) {
            sb.append("no experience was spent; ");
        }
        if (!lapisSpent) {
            sb.append("no lapis was spent; ");
        }
        if (!stickRefused) {
            sb.append("a stick was not refused as unrepairable; ");
        }
        if (!nothingToRepairWith) {
            sb.append("a tool with no material was not refused; ");
        }
        if (!usePointsAtTheTool) {
            sb.append("use() on the anvil did not point at the repair tool; ");
        }
        return sb.toString();
    }

    private static ItemStack carried(net.minecraft.server.level.ServerPlayer bot,
                                     net.minecraft.world.item.Item item) {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int count(net.minecraft.server.level.ServerPlayer bot,
                             net.minecraft.world.item.Item item) {
        var inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Put the site back the way it was found.
     *
     * <p>This world is shared with every other harness, and the fixtures this test builds - an anvil,
     * an enchanting table, a ring of bookshelves - are exactly what the structure guard protects. Left
     * behind, they made an unrelated tunnel test fail with "you are standing inside the player-built
     * structure": a test that pollutes the world is a test that breaks the next one.
     */
    private void cleanUpSite() {
        if (this.level == null || this.plot == null) {
            return;
        }
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    net.minecraft.core.BlockPos pos = this.plot.offset(dx, dy, dz);
                    var state = this.level.getBlockState(pos);
                    if (state.is(Blocks.ANVIL) || state.is(Blocks.ENCHANTING_TABLE)
                            || state.is(Blocks.BOOKSHELF) || state.is(Blocks.TORCH)
                            || state.is(Blocks.OAK_PLANKS) || state.is(Blocks.CHEST)
                            || state.is(Blocks.WHEAT) || state.is(Blocks.FARMLAND)
                            || state.is(Blocks.RED_BED)) {
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
