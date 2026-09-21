package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.util.List;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.Actions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves that a mining job's drops end up in the pack as the blocks fall, instead of being walked to.
 *
 * <p>Enabled with {@code MCAGENT_MINEDROP_TEST=true}. Three checks, each of which fails differently:
 * <ol>
 *   <li>a radius job on a stack of logs fells the whole trunk, the logs are in the inventory, nothing
 *       is left on the ground, and the end-of-job collection phase finds nothing to walk to;</li>
 *   <li>with a full pack, what does not fit stays on the ground instead of being destroyed;</li>
 *   <li>valuable ore displaces one redundant low-tier tool in a completely full pack;</li>
 *   <li>an item that was already lying there before the break is not swept up with it.</li>
 * </ol>
 *
 * <p>The model is a scripted stub that never asks for anything, so the job runs deterministically and
 * a real model cannot walk off with the test's inventory mid-check.
 */
public final class MineDropSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/minedrop");

    private static final String BOT = "MineBot";
    private static final String OTHER = "MineStandIn";
    private static final int TREE_LOGS = 5;
    /**
     * How old the foreign item must be before the block above it is broken.
     *
     * <p>Comfortably past the sweep's own freshness window (a couple of ticks), so the two are not
     * being compared at the boundary.
     */
    private static final int FOREIGN_ITEM_AGE_TICKS = 15;

    /**
     * How long the scene is left to settle, sweeping the ground each tick, before the first job runs.
     *
     * <p>An item left by an earlier run lives in a chunk that this server may not have entity-loaded
     * yet when the first tick arrives, so a single sweep can miss it and the item then turns up
     * halfway through the run - counted as this run's leftover, and walked to by its collection phase.
     */
    private static final int SCENE_SETTLE_TICKS = 20;

    private final MinecraftServer server;

    private ScriptedLlmServer model;
    /** The LLM settings this run found, restored on the way out so the config file is untouched. */
    private String[] savedSettings;
    private ServerLevel level;
    private BlockPos base;
    private BlockPos treeBase;
    private BlockPos bookshelf;
    private BlockPos diamondLog;
    private ItemEntity foreignItem;

    private int ticks;
    private int step;
    private int phaseStartTick;
    private int collectingTicks;
    private boolean started;
    private boolean finished;
    private boolean failed;

    public MineDropSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_MINEDROP_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("MINEDROPTEST: armed");
        return new MineDropSmokeTest(server);
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
        if (this.step >= 2 && this.step <= 7 && this.isCollecting()) {
            this.collectingTicks++;
        }

        switch (this.step) {
            case 0 -> {
                // Let the world settle before measuring anything in it. The chunk the scene sits in is
                // not necessarily entity-loaded on the first tick, so a sweep that runs only then can
                // miss what an earlier run left behind - and an item from an earlier run is counted by
                // this run's checks, and would send its collection phase walking to it.
                this.sweepScene();
                if (this.ticks >= SCENE_SETTLE_TICKS) {
                    this.step = 1;
                }
            }
            case 1 -> this.startRadiusJob();
            case 2 -> {
                if (!this.isMining()) {
                    this.checkRadiusJob();
                    this.startPartialFitJob();
                } else if (this.timedOut(1200, "the radius job never finished")) {
                    // handled by timedOut
                }
            }
            case 3 -> {
                // The sweep happens inside the tick that breaks the block, and vanilla's own pickup
                // cannot have run yet when this hook sees it, so counting the leftovers here is the
                // moment before anything could have moved them.
                if (this.level.getBlockState(this.bookshelf).isAir()) {
                    this.checkLeftoversImmediately();
                    this.step = 4;
                    this.phaseStartTick = this.ticks;
                } else if (this.timedOut(600, "the bookshelf was never broken")) {
                    // handled by timedOut
                }
            }
            case 4 -> {
                if (!this.isMining()) {
                    this.checkLeftoversAfterJob();
                    this.checkValuableDropPressureRelief();
                    this.startAgeFilterSetUp();
                } else if (this.timedOut(400, "the job never gave up on the leftovers it could not "
                        + "pick up")) {
                    // handled by timedOut
                }
            }
            case 5 -> {
                // Wait for the item to be genuinely old, rather than for a wall clock: what the age
                // filter looks at is the item's own tick count, so that is what the test waits on.
                int age = this.foreignItem.isRemoved() ? -1 : this.foreignItem.getAge();
                if (age >= FOREIGN_ITEM_AGE_TICKS) {
                    this.startAgeFilterJob();
                } else if (age < 0) {
                    LOG.error("MINEDROPTEST: FAIL - the diamond was picked up before the break; the "
                            + "test cannot tell the age filter from a lucky pickup");
                    this.check("the foreign item was still on the ground when the log broke", false);
                    this.report();
                } else if (this.timedOut(400, "the diamond never aged past " + FOREIGN_ITEM_AGE_TICKS
                        + " ticks (still " + age + ")")) {
                    // handled by timedOut
                }
            }
            case 6 -> {
                if (this.level.getBlockState(this.diamondLog).isAir()) {
                    this.checkAgeFilter();
                    this.step = 7;
                } else if (this.timedOut(600, "the log over the diamond was never broken")) {
                    // handled by timedOut
                }
            }
            case 7 -> {
                // The job is not over when the block breaks: the end-of-job collection phase still
                // runs, and it is the part that could walk the bot over to the diamond. The claim
                // worth checking is the one a player would make - the diamond is still there when the
                // bot has finished.
                if (!this.isMining()) {
                    this.checkForeignItemNotSwept();
                    this.report();
                } else if (this.timedOut(400, "the age-filter job never finished")) {
                    // handled by timedOut
                }
            }
            default -> { }
        }
    }

    // --- set-up ----------------------------------------------------------------------------------

    private void begin() {
        this.level = this.server.overworld();
        BlockPos spawn = this.level.getSharedSpawnPos();

        try {
            // A model that answers every request with "nothing": the decision loop is real, the
            // model never interferes. This test measures the drop path, not a model's choices.
            this.savedSettings = ScriptedLlmServer.settings();
            this.model = new ScriptedLlmServer(request -> ScriptedLlmServer.silent());
            this.model.pointModAtThisServer();
            LOG.info("MINEDROPTEST: scripted model listening at {} (the real LLM settings are put "
                    + "back when this test stops)", this.model.baseUrl());
        } catch (IOException e) {
            LOG.error("MINEDROPTEST: FAIL - could not start the scripted model", e);
            this.failed = true;
            this.finish();
            return;
        }

        // Somewhere flat and empty, well away from the other gated tests' scenery.
        this.base = spawn.offset(24, 0, 24);
        this.prepareGround();

        this.treeBase = this.base.offset(2, 0, 0);
        for (int i = 0; i < TREE_LOGS; i++) {
            this.level.setBlockAndUpdate(this.treeBase.above(i), Blocks.OAK_LOG.defaultBlockState());
        }
        this.bookshelf = this.base.offset(2, 0, 2);
        this.level.setBlockAndUpdate(this.bookshelf, Blocks.BOOKSHELF.defaultBlockState());
        LOG.info("MINEDROPTEST: tree of {} logs based at {}, bookshelf at {}", TREE_LOGS,
                this.treeBase, this.bookshelf);

        var handle = Agent.botManager().spawn(BOT, this.level,
                new Vec3(this.base.getX() + 1.5, this.base.getY(), this.base.getZ() + 0.5), true);
        if (handle == null) {
            LOG.error("MINEDROPTEST: FAIL - could not spawn {}", BOT);
            this.failed = true;
            this.finish();
            return;
        }
        boolean attached = Agent.attachBrain(handle.player());
        LOG.info("MINEDROPTEST: {} joined at {} with a brain attached={}", BOT,
                handle.player().blockPosition(), attached);
        if (!attached) {
            LOG.error("MINEDROPTEST: FAIL - no brain attached, so no mining job can run");
            this.failed = true;
            this.finish();
            return;
        }

        // An axe, so a break takes a few ticks rather than the ~200 a bare hand needs on a log.
        giveAxe();

        if (Agent.botManager().spawn(OTHER, this.level,
                new Vec3(this.base.getX() + 8.5, this.base.getY(), this.base.getZ() + 0.5), false) == null) {
            LOG.error("MINEDROPTEST: FAIL - could not spawn the stand-in player");
            this.failed = true;
            this.finish();
            return;
        }

        this.step = 0;
    }

    /** Level the working area and lay a stone floor, so the test measures the job and nothing else. */
    private void prepareGround() {
        for (int dx = -3; dx <= 12; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    this.level.setBlockAndUpdate(this.base.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState());
                }
                this.level.setBlockAndUpdate(this.base.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * Throw away every dropped item in the test's area.
     *
     * <p>Anything an earlier run left on the ground would be counted by this run's checks - and,
     * worse, would send the collection phase walking to it. The test builds its own scene, so it both
     * sweeps before it starts and clears up after itself; see {@link #SCENE_SETTLE_TICKS}.
     */
    private void sweepScene() {
        int discarded = 0;
        for (ItemEntity leftover : this.level.getEntitiesOfClass(ItemEntity.class,
                new AABB(this.base).inflate(24.0D))) {
            leftover.discard();
            discarded++;
        }
        if (discarded > 0 && LOG.isInfoEnabled()) {
            LOG.info("MINEDROPTEST swept {} item(s) off the ground in the test area (tick {})",
                    discarded, this.ticks);
        }
    }

    /** Put an iron axe in the bot's hand, in a slot that the other phases leave alone. */
    private void giveAxe() {
        ServerPlayer bot = bot();
        if (bot == null) {
            return;
        }
        bot.getInventory().setItem(8, new ItemStack(Items.IRON_AXE));
        bot.getInventory().selected = 8;
    }

    // --- check 1: a radius job -------------------------------------------------------------------

    private void startRadiusJob() {
        ServerPlayer bot = bot();
        var brain = brain();
        if (bot == null || brain == null) {
            this.check("a bot with a brain exists to mine with", false);
            this.finish();
            return;
        }
        bot.getInventory().clearContent();
        giveAxe();
        bot.teleportTo(this.treeBase.getX() + 1.5, this.treeBase.getY(), this.treeBase.getZ() + 0.5);
        String outcome = brain.mineAsTool(this.treeBase, 6);
        LOG.info("MINEDROPTEST radius job: {}", outcome);
        this.check("the radius job started", outcome.startsWith("started mining"));
        this.step = 2;
        this.phaseStartTick = this.ticks;
    }

    private void checkRadiusJob() {
        ServerPlayer bot = bot();
        int standing = 0;
        for (int i = 0; i < TREE_LOGS; i++) {
            if (this.level.getBlockState(this.treeBase.above(i)).is(Blocks.OAK_LOG)) {
                standing++;
            }
        }
        int logs = bot == null ? 0 : countOf(bot, Items.OAK_LOG);
        int onGround = itemsNear(this.treeBase, 8.0D).size();

        LOG.info("MINEDROPTEST ================ CHECK 1: radius job ================");
        LOG.info("MINEDROPTEST logs still standing  : {} of {}", standing, TREE_LOGS);
        LOG.info("MINEDROPTEST oak logs in pack     : {}", logs);
        LOG.info("MINEDROPTEST items on the ground  : {}", onGround);
        LOG.info("MINEDROPTEST ticks in the end-of-job collection phase: {}", this.collectingTicks);
        this.check("the whole trunk fell", standing == 0);
        this.check("every log is in the pack without walking to it (" + logs + ")", logs >= TREE_LOGS);
        this.check("nothing was left on the ground", onGround == 0);
        this.check("the collection phase had nothing to walk to (" + this.collectingTicks
                + " tick(s) spent in it)", this.collectingTicks <= 2);
        this.collectingTicks = 0;
    }

    // --- check 2: partial fit --------------------------------------------------------------------

    /**
     * Break a bookshelf with a pack that has room for one of the three books it drops.
     *
     * <p>A full pack is exactly the case where an over-eager sweep would delete the remainder.
     */
    private void startPartialFitJob() {
        ServerPlayer bot = bot();
        var brain = brain();
        if (bot == null || brain == null) {
            this.check("a bot with a brain exists for the partial-fit job", false);
            this.finish();
            return;
        }

        var inventory = bot.getInventory();
        inventory.clearContent();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        // Room for exactly one book, and the bookshelf drops three.
        inventory.setItem(0, new ItemStack(Items.BOOK, 63));
        giveAxe();

        bot.teleportTo(this.bookshelf.getX() + 1.5, this.bookshelf.getY(),
                this.bookshelf.getZ() + 0.5);
        String outcome = brain.mineAsTool(this.bookshelf, 0);
        LOG.info("MINEDROPTEST partial-fit job: {}", outcome);
        this.check("the partial-fit job started", outcome.startsWith("started mining"));
        this.step = 3;
        this.phaseStartTick = this.ticks;
    }

    private void checkLeftoversImmediately() {
        int books = 0;
        for (ItemEntity drop : itemsNear(this.bookshelf, 3.0D)) {
            if (drop.getItem().is(Items.BOOK)) {
                books += drop.getItem().getCount();
            }
        }
        ServerPlayer bot = bot();
        int held = bot == null ? -1 : countOf(bot, Items.BOOK);

        LOG.info("MINEDROPTEST ================ CHECK 2: partial fit ================");
        LOG.info("MINEDROPTEST bookshelf broke with room for 1 of its 3 books");
        LOG.info("MINEDROPTEST books in the pack    : {}", held);
        LOG.info("MINEDROPTEST books on the ground  : {}", books);
        this.check("what fitted went into the pack (64 books)", held == 64);
        this.check("what did not fit stayed on the ground instead of being deleted (" + books
                + " of the 2 left over)", books == 2);
    }

    private void checkLeftoversAfterJob() {
        int books = 0;
        for (ItemEntity drop : itemsNear(this.bookshelf, 6.0D)) {
            if (drop.getItem().is(Items.BOOK)) {
                books += drop.getItem().getCount();
            }
        }
        LOG.info("MINEDROPTEST after the fallback collection phase: {} book(s) still on the ground, "
                + "{} tick(s) spent trying to collect them", books, this.collectingTicks);
        this.check("the leftovers are still on the ground after the fallback phase (" + books + ")",
                books == 2);
        this.check("the fallback gave up instead of standing there forever (" + this.collectingTicks
                + " ticks)", this.collectingTicks < 400);
        this.collectingTicks = 0;
    }

    /** A fresh ore drop may replace redundant starter gear instead of being abandoned. */
    private void checkValuableDropPressureRelief() {
        ServerPlayer bot = bot();
        if (bot == null) {
            this.check("a bot exists for the inventory-pressure check", false);
            return;
        }
        var inventory = bot.getInventory();
        inventory.clearContent();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        inventory.setItem(0, new ItemStack(Items.STONE_PICKAXE));
        inventory.setItem(1, new ItemStack(Items.STONE_PICKAXE));
        inventory.selected = 8;

        BlockPos orePos = this.base.offset(10, 0, 0);
        ItemEntity ore = new ItemEntity(this.level, orePos.getX() + 0.5D, orePos.getY() + 0.5D,
                orePos.getZ() + 0.5D, new ItemStack(Items.RAW_IRON, 2));
        this.level.addFreshEntity(ore);
        int moved = Actions.collectBreakDrops(bot, orePos);

        LOG.info("MINEDROPTEST ================ CHECK 3: full-pack ore ================");
        LOG.info("MINEDROPTEST raw iron moved       : {}", moved);
        LOG.info("MINEDROPTEST stone pickaxes left  : {}", countOf(bot, Items.STONE_PICKAXE));
        this.check("valuable ore entered a completely full pack", moved == 2
                && countOf(bot, Items.RAW_IRON) == 2 && ore.isRemoved());
        this.check("only one redundant stone pickaxe was discarded",
                countOf(bot, Items.STONE_PICKAXE) == 1);
    }

    // --- check 4: the age filter -----------------------------------------------------------------

    private void startAgeFilterSetUp() {
        ServerPlayer bot = bot();
        if (bot == null) {
            this.check("a bot exists for the age-filter job", false);
            this.finish();
            return;
        }
        bot.getInventory().clearContent();
        giveAxe();

        this.diamondLog = this.base.offset(2, 0, -2);
        this.level.setBlockAndUpdate(this.diamondLog, Blocks.OAK_LOG.defaultBlockState());

        // A real dropped item, resting on top of the log that is about to be broken - squarely inside
        // the area the sweep looks at, so only its age can keep it out of the pack. Not a block drop
        // and not instantly pickable: like anything a player throws down, it starts with the usual
        // pickup delay.
        this.foreignItem = new ItemEntity(this.level,
                this.diamondLog.getX() + 0.5D, this.diamondLog.getY() + 1.05D,
                this.diamondLog.getZ() + 0.5D, new ItemStack(Items.DIAMOND, 1));
        this.foreignItem.setDefaultPickUpDelay();
        this.level.addFreshEntity(this.foreignItem);
        LOG.info("MINEDROPTEST dropped a diamond on top of the log at {}; nothing in this break "
                + "produced it", this.diamondLog.toShortString());

        // Stand well back while it ages. A player collects items from about 1.3 blocks away (the
        // bounding box used for pickup is inflated by 1.0 x 0.5 x 1.0), so a bot standing next to it
        // would simply pick it up and the test would prove nothing.
        bot.teleportTo(this.diamondLog.getX() + 6.5, this.diamondLog.getY(),
                this.diamondLog.getZ() + 0.5);
        this.stopBot();
        this.step = 5;
        this.phaseStartTick = this.ticks;
    }

    private void startAgeFilterJob() {
        ServerPlayer bot = bot();
        var brain = brain();
        if (brain == null || bot == null) {
            this.check("a brain exists for the age-filter job", false);
            this.finish();
            return;
        }
        // Close enough to reach the block, far enough that vanilla's own pickup cannot grab the
        // diamond out from under the test. A player collects items from a box inflated by
        // (1.0, 0.5, 1.0) around their own, so 2.5 blocks of separation leaves a margin - but only
        // for as long as the bot stands still, which is why the driver is stopped first.
        bot.teleportTo(this.diamondLog.getX() + 2.5, this.diamondLog.getY(),
                this.diamondLog.getZ() + 0.5);
        this.stopBot();
        this.collectingTicks = 0;
        String outcome = brain.mineAsTool(this.diamondLog, 0);
        LOG.info("MINEDROPTEST age-filter job: bot at {}, {} block(s) from the diamond, which is {} "
                + "ticks old and was left where it was; {}",
                bot.blockPosition(), String.format(java.util.Locale.ROOT, "%.2f",
                        Math.sqrt(bot.distanceToSqr(this.foreignItem.position()))),
                this.foreignItem.getAge(), outcome);
        this.check("the age-filter job started", outcome.startsWith("started mining"));
        this.step = 6;
        this.phaseStartTick = this.ticks;
    }

    /**
     * Stop the bot walking.
     *
     * <p>The bot has to stand still for this check to mean anything: a player collects items from a
     * box inflated by (1.0, 0.5, 1.0) around their own, so a bot that drifts within about a block and
     * a half of the diamond picks it up itself and the check then measures nothing at all. Whether it
     * would drift depends on what the previous phase left in the movement driver, so the driver is
     * stopped here rather than left to chance.
     */
    private void stopBot() {
        BotManager manager = Agent.botManager();
        BotManager.BotHandle handle = manager == null ? null : manager.get(BOT);
        if (handle != null) {
            handle.movement().clear();
        }
    }

    private void checkAgeFilter() {
        ServerPlayer bot = bot();
        int logsHeld = bot == null ? 0 : countOf(bot, Items.OAK_LOG);
        int diamondsHeld = bot == null ? 0 : countOf(bot, Items.DIAMOND);
        int diamondsOnGround = 0;
        int age = this.foreignItem.isRemoved() ? -1 : this.foreignItem.getAge();
        for (ItemEntity drop : itemsNear(this.diamondLog, 4.0D)) {
            if (drop.getItem().is(Items.DIAMOND)) {
                diamondsOnGround += drop.getItem().getCount();
            }
        }

        LOG.info("MINEDROPTEST ================ CHECK 3: age filter ================");
        LOG.info("MINEDROPTEST the log's own drop went into the pack: {} oak_log", logsHeld);
        LOG.info("MINEDROPTEST the diamond was {} ticks old when the log above it broke", age);
        LOG.info("MINEDROPTEST the bot was at {} then, {} block(s) from the diamond",
                bot == null ? "?" : bot.blockPosition(),
                bot == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f",
                        Math.sqrt(bot.distanceToSqr(this.foreignItem.position()))));
        LOG.info("MINEDROPTEST diamond still on the ground: {} ({} in the pack)", diamondsOnGround,
                diamondsHeld);
        this.check("the block's own drop was swept up as usual (" + logsHeld + " oak_log)", logsHeld >= 1);
        this.check("an item that was already lying there was left alone (age " + age + ", "
                + diamondsOnGround + " on the ground, " + diamondsHeld + " in the pack)",
                diamondsOnGround == 1 && diamondsHeld == 0 && age > 2);
    }

    /**
     * The same claim again, once the job is over.
     *
     * <p>Checking at the instant of the break only proves the sweep left the diamond alone. The job
     * then enters its collection phase, which walks to what is still on the ground, and that phase is
     * where a bot that treats every nearby item as its own would pick the diamond up after all.
     */
    private void checkForeignItemNotSwept() {
        ServerPlayer bot = bot();
        int diamondsHeld = bot == null ? -1 : countOf(bot, Items.DIAMOND);
        int diamondsOnGround = 0;
        for (ItemEntity drop : itemsNear(this.diamondLog, 6.0D)) {
            if (drop.getItem().is(Items.DIAMOND)) {
                diamondsOnGround += drop.getItem().getCount();
            }
        }
        double distance = bot == null || this.foreignItem.isRemoved()
                ? -1.0D : Math.sqrt(bot.distanceToSqr(this.foreignItem));

        LOG.info("MINEDROPTEST after the job ended: the diamond is {} block(s) away, {} on the "
                + "ground, {} in the pack; the collection phase spent {} tick(s)",
                distance < 0.0D ? "?" : String.format(java.util.Locale.ROOT, "%.1f", distance),
                diamondsOnGround, diamondsHeld, this.collectingTicks);
        this.check("the bot did not walk to the diamond after the job (" + (distance < 0.0D ? "gone"
                : String.format(java.util.Locale.ROOT, "%.1f blocks", distance)) + ")", distance > 1.5D);
        this.check("the diamond someone else dropped is still on the ground ("
                + diamondsOnGround + ")", diamondsOnGround == 1);
        this.check("the diamond someone else dropped is not in the pack (" + diamondsHeld + ")",
                diamondsHeld == 0);
        this.check("the collection phase had nothing of its own to fetch (" + this.collectingTicks
                + " tick(s))", this.collectingTicks <= 2);
        this.collectingTicks = 0;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private boolean isMining() {
        var brain = brain();
        return brain != null && brain.isMining();
    }

    private boolean isCollecting() {
        var brain = brain();
        return brain != null && brain.currentAction().startsWith("collecting");
    }

    /** True (and reported as a failure) when a phase has run past its patience. */
    private boolean timedOut(int limit, String what) {
        if (this.ticks - this.phaseStartTick <= limit) {
            return false;
        }
        LOG.error("MINEDROPTEST: FAIL - {}", what);
        this.check(what, false);
        this.report();
        return true;
    }

    private com.melody.mcagent.rt.brain.AgentBrain brain() {
        var brains = Agent.brainManager();
        var bots = Agent.botManager();
        if (brains == null || bots == null) {
            return null;
        }
        BotManager.BotHandle handle = bots.get(BOT);
        return handle == null ? null : brains.get(handle.player().getUUID());
    }

    private ServerPlayer bot() {
        BotManager manager = Agent.botManager();
        BotManager.BotHandle handle = manager == null ? null : manager.get(BOT);
        return handle == null ? null : handle.player();
    }

    private List<ItemEntity> itemsNear(BlockPos pos, double range) {
        return this.level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(range));
    }

    private static int countOf(ServerPlayer bot, Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            this.failed = true;
        }
        LOG.info("MINEDROPTEST {} : {}", ok ? "PASS" : "FAIL", what);
    }

    private void report() {
        LOG.info("MINEDROPTEST ================ RESULT ================");
        LOG.info("MINEDROPTEST VERDICT : {}", this.failed ? "FAIL" : "PASS");
        LOG.info("MINEDROPTEST ======================================");
        this.finish();
    }

    private void finish() {
        this.finished = true;
        // Clear up after the checks have reported. Without this the next run inherits this run's
        // leftovers, and the two runs are then not measuring the same thing.
        try {
            this.sweepScene();
        } catch (Throwable ignored) {
            // The world may already be tearing down; nothing depends on this succeeding.
        }
        ScriptedLlmServer.restoreSettings(this.savedSettings);
        if (this.model != null) {
            try {
                this.model.close();
            } catch (Throwable ignored) {
                // Best effort; the process is about to stop anyway.
            }
        }
        LOG.info("MINEDROPTEST: done, stopping server");
        this.server.halt(false);
    }
}
