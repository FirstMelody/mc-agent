package com.melody.mcagent.rt.bot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves that a bot still has its belongings and its respawn point after the server is stopped and
 * started again, and that a bot whose death was saved comes back alive instead of being culled.
 *
 * <p>Two runs, deliberately: a restart cannot be faked inside one process, and the bug being fixed
 * only ever showed up across one.
 *
 * <pre>
 *   MCAGENT_PERSIST_TEST=write  runServer    # give a bot an inventory and a respawn point, stop
 *   MCAGENT_PERSIST_TEST=verify runServer    # the same bot rejoins; check what came back
 * </pre>
 *
 * <p>The first run leaves two bots on disk:
 * <ul>
 *   <li>{@code PersistBot}, alive and healthy, carrying diamonds and golden apples, with a respawn
 *       point set through the vanilla {@code /spawnpoint} command;</li>
 *   <li>{@code DeadBot}, given emeralds and then written out with health 0, {@code DeathTime} 21 and
 *       an empty food bar - the exact state the production bug produced, where the next join loaded a
 *       corpse and {@code tickDeath} removed it before anyone saw it.</li>
 * </ul>
 *
 * <p>The second run compares what the rejoined bots actually have against what the first run wrote
 * into the marker file, and checks that the dead one stays alive well past the 20 ticks that used to
 * kill it.
 */
public final class PersistenceSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/persisttest");

    /** Environment variable selecting the phase; unset means the test does not run at all. */
    private static final String ENV = "MCAGENT_PERSIST_TEST";

    private static final String LIVING = "PersistBot";
    private static final String DEAD = "DeadBot";
    private static final int DIAMONDS = 7;
    private static final int GOLDEN_APPLES = 3;
    private static final int EMERALDS = 5;
    /** Past 20, which is where vanilla's {@code tickDeath} removes a dead player. */
    private static final int SAVED_DEATH_TIME = 21;
    /** How long the repaired bot must stay alive before it counts as fixed. */
    private static final int ALIVE_TICKS_REQUIRED = 80;

    private final MinecraftServer server;
    private final boolean writePhase;
    private final Path marker;

    private final Properties expectations = new Properties();
    private int ticks;
    private int step;
    private boolean started;
    private boolean finished;
    private boolean failed;
    /** Tick at which the dead bot was spawned in the verify run. */
    private int revivedAtTick;

    private PersistenceSmokeTest(MinecraftServer server, boolean writePhase) {
        this.server = server;
        this.writePhase = writePhase;
        this.marker = server.getServerDirectory().resolve("mcagent-persist-test.properties");
    }

    /** The phase asked for on the command line, or null when the test is not enabled. */
    private static String requestedPhase() {
        String raw = System.getenv(ENV);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("write") || value.equals("verify")) {
            return value;
        }
        LOG.error("PERSISTTEST: {}='{}' is not 'write' or 'verify'; not arming", ENV, raw);
        return null;
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        String phase = requestedPhase();
        if (phase == null) {
            return null;
        }
        LOG.info("PERSISTTEST: armed for the '{}' phase", phase);
        return new PersistenceSmokeTest(server, phase.equals("write"));
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
        if (this.writePhase) {
            switch (this.step) {
                case 0 -> { if (this.ticks >= 20) { this.step++; this.writeSpawn(); } }
                case 1 -> { if (this.ticks >= 40) { this.step++; this.writeSetUp(); } }
                case 2 -> { if (this.ticks >= 80) { this.step++; this.writeStopWithSavedState(); } }
                default -> { }
            }
        } else {
            switch (this.step) {
                case 0 -> { if (this.ticks >= 20) { this.step++; this.verifyReadDisk(); } }
                case 1 -> { if (this.ticks >= 40) { this.step++; this.verifySpawn(); } }
                case 2 -> { if (this.ticks >= 60) { this.step++; this.verifyWhatCameBack(); } }
                case 3 -> { if (this.ticks >= this.revivedAtTick + ALIVE_TICKS_REQUIRED) { this.step++; this.verifyStillAlive(); } }
                default -> { }
            }
        }
    }

    private void begin() {
        LOG.info("PERSISTTEST phase '{}' starting (game dir {})", this.writePhase ? "write" : "verify",
                this.server.getServerDirectory());

        if (!this.writePhase) {
            if (!Files.isRegularFile(this.marker)) {
                LOG.error("PERSISTTEST: FAIL - {} is missing; run the 'write' phase first",
                        this.marker);
                this.fail();
                this.finish();
                return;
            }
            try (Reader reader = Files.newBufferedReader(this.marker)) {
                this.expectations.load(reader);
            } catch (IOException e) {
                LOG.error("PERSISTTEST: FAIL - could not read the marker file", e);
                this.fail();
                this.finish();
            }
        }
    }

    // --- phase 1: set the world up and stop the server -------------------------------------------

    private void writeSpawn() {
        // Start from a clean slate: the bots persist now, so a second run of this test would
        // otherwise measure the diamonds the previous run left behind. "fresh" is the supported way
        // to say "this bot starts over", so using it here exercises that path too.
        this.run("mcagent spawn " + LIVING + " fresh");
        this.run("mcagent spawn " + DEAD + " fresh");
        ServerPlayer bot = player(LIVING);
        if (bot == null) {
            LOG.error("PERSISTTEST: FAIL - could not spawn {}", LIVING);
            this.fail();
            this.finish();
            return;
        }
        LOG.info("PERSISTTEST '{}' joined as uuid {} at {}", LIVING, bot.getUUID(),
                bot.blockPosition().toShortString());
        this.holdStill(LIVING);
    }

    private void writeSetUp() {
        ServerPlayer bot = player(LIVING);
        if (bot == null) {
            LOG.error("PERSISTTEST: FAIL - {} is gone", LIVING);
            this.fail();
            this.finish();
            return;
        }

        // Give it something nobody could confuse with chance, through the ordinary commands.
        this.run("give " + LIVING + " minecraft:diamond " + DIAMONDS);
        this.run("give " + LIVING + " minecraft:golden_apple " + GOLDEN_APPLES);

        BlockPos home = bot.blockPosition().offset(5, 0, 5);
        this.run(String.format(Locale.ROOT, "spawnpoint %s %d %d %d",
                LIVING, home.getX(), home.getY(), home.getZ()));

        LOG.info("PERSISTTEST '{}' inventory: {}", LIVING, describeInventory(bot));
        LOG.info("PERSISTTEST '{}' respawn point: {}", LIVING, describeRespawn(bot));

        this.expectations.setProperty("living.uuid", bot.getUUID().toString());
        this.expectations.setProperty("living.items", describeInventory(bot));
        this.expectations.setProperty("living.diamonds", Integer.toString(DIAMONDS));
        this.expectations.setProperty("living.apples", Integer.toString(GOLDEN_APPLES));
        this.expectations.setProperty("living.spawnx", Integer.toString(home.getX()));
        this.expectations.setProperty("living.spawny", Integer.toString(home.getY()));
        this.expectations.setProperty("living.spawnz", Integer.toString(home.getZ()));
        this.expectations.setProperty("living.spawndim",
                bot.getRespawnDimension().location().toString());

        // The second bot exists to reproduce the original bug: it is written to disk dead.
        this.run("mcagent spawn " + DEAD);
        ServerPlayer dead = player(DEAD);
        if (dead == null) {
            LOG.error("PERSISTTEST: FAIL - could not spawn {}", DEAD);
            this.fail();
            this.finish();
            return;
        }
        this.holdStill(DEAD);
        this.run("give " + DEAD + " minecraft:emerald " + EMERALDS);
        LOG.info("PERSISTTEST '{}' inventory: {}", DEAD, describeInventory(dead));
        this.expectations.setProperty("dead.uuid", dead.getUUID().toString());
        this.expectations.setProperty("dead.items", describeInventory(dead));
        this.expectations.setProperty("dead.emeralds", Integer.toString(EMERALDS));
    }

    /**
     * Kill the second bot and stop the server in the same tick.
     *
     * <p>{@code setHealth(0)} rather than {@code kill()}: {@code kill()} runs the real death, which
     * scatters the bot's inventory on the ground, and the point here is a dead bot that still has its
     * emeralds. This test hook runs at the end of the server tick, after every entity has been ticked,
     * so nothing advances {@code deathTime} or revives the bot before the server saves and exits.
     */
    private void writeStopWithSavedState() {
        ServerPlayer dead = player(DEAD);
        if (dead == null) {
            LOG.error("PERSISTTEST: FAIL - {} is gone before it could be killed", DEAD);
            this.fail();
            this.finish();
            return;
        }

        dead.setHealth(0.0F);
        dead.deathTime = SAVED_DEATH_TIME;
        // Empty the food bar in this same tick, because the repair has two halves and this is the
        // other one: a bot that starved is saved with foodLevel 0, and restoring its health alone
        // would leave it starving again the moment it joins. It cannot be done earlier - this server
        // runs on Peaceful, where the game tops hunger up on its own every ten ticks, so a bar
        // emptied at the start of the run is part-full again by the time it is written out.
        dead.getFoodData().setFoodLevel(0);
        dead.getFoodData().setSaturation(0.0F);
        LOG.info("PERSISTTEST '{}' written out dead: health={} deathTime={} foodLevel={} (this is "
                + "what the 'came back dead' bug saved)", DEAD, dead.getHealth(), dead.deathTime,
                dead.getFoodData().getFoodLevel());

        // Force the save now so the run can report what is on disk before it stops, rather than
        // claiming it and hoping.
        this.server.getPlayerList().saveAll();

        LOG.info("PERSISTTEST '{}' file on disk: {}", LIVING, describePlayerFile(LIVING));
        LOG.info("PERSISTTEST '{}' file on disk: {}", DEAD, describePlayerFile(DEAD));

        try (Writer writer = Files.newBufferedWriter(this.marker)) {
            this.expectations.store(writer, "written by the MC Agent persistence test");
        } catch (IOException e) {
            LOG.error("PERSISTTEST: FAIL - could not write the marker file", e);
            this.fail();
        }

        boolean ok = Files.isRegularFile(playerFile(LIVING)) && Files.isRegularFile(playerFile(DEAD));
        this.check("both bots have a playerdata file before the server stops", ok);
        int savedFood = foodLevelOnDisk(DEAD);
        this.check("'" + DEAD + "' is written out starving, so the restart has to feed it (foodLevel "
                + savedFood + " on disk)", savedFood == 0);

        LOG.info("PERSISTTEST: stopping the server; start it again with {}={} to check what survived",
                ENV, "verify");
        this.finish();
    }

    // --- phase 2: the server has been restarted --------------------------------------------------

    private void verifyReadDisk() {
        LOG.info("PERSISTTEST restart: what is on disk before anything joins");
        LOG.info("PERSISTTEST '{}' file on disk: {}", LIVING, describePlayerFile(LIVING));
        LOG.info("PERSISTTEST '{}' file on disk: {}", DEAD, describePlayerFile(DEAD));
    }

    private void verifySpawn() {
        this.run("mcagent spawn " + LIVING);
        this.run("mcagent spawn " + DEAD);
        this.revivedAtTick = this.ticks;
    }

    private void verifyWhatCameBack() {
        ServerPlayer living = player(LIVING);
        ServerPlayer dead = player(DEAD);

        if (living == null) {
            this.check("'" + LIVING + "' rejoined with the same name", false);
        } else {
            int diamonds = countOf(living, Items.DIAMOND);
            int apples = countOf(living, Items.GOLDEN_APPLE);
            String inventory = describeInventory(living);
            LOG.info("PERSISTTEST '{}' inventory after the restart: {}", LIVING, inventory);
            LOG.info("PERSISTTEST '{}' respawn point after the restart: {}", LIVING,
                    describeRespawn(living));
            this.check("'" + LIVING + "' kept its inventory across the restart ("
                    + diamonds + " diamonds, " + apples + " golden apples)", diamonds == DIAMONDS
                    && apples == GOLDEN_APPLES);
            this.check("'" + LIVING + "' has exactly the inventory the first run saved (" + inventory
                    + ")", inventory.equals(this.expectations.getProperty("living.items")));

            BlockPos spawn = living.getRespawnPosition();
            String dimension = living.getRespawnDimension().location().toString();
            boolean spawnKept = spawn != null
                    && spawn.getX() == Integer.parseInt(this.expectations.getProperty("living.spawnx"))
                    && spawn.getY() == Integer.parseInt(this.expectations.getProperty("living.spawny"))
                    && spawn.getZ() == Integer.parseInt(this.expectations.getProperty("living.spawnz"))
                    && dimension.equals(this.expectations.getProperty("living.spawndim"));
            this.check("'" + LIVING + "' kept its respawn point across the restart (" + spawn
                    + " in " + dimension + ")", spawnKept);
            this.check("'" + LIVING + "' is alive", !living.isDeadOrDying() && !living.isRemoved());
        }

        if (dead == null) {
            this.check("'" + DEAD + "' rejoined at all", false);
            return;
        }
        LOG.info("PERSISTTEST '{}' after the repair: health={}/{} deathTime={} removed={} food={} inventory: {}",
                DEAD, dead.getHealth(), dead.getMaxHealth(), dead.deathTime, dead.isRemoved(),
                dead.getFoodData().getFoodLevel(), describeInventory(dead));
        this.check("'" + DEAD + "' came back alive rather than dead (health "
                + dead.getHealth() + ", deathTime " + dead.deathTime + ")",
                !dead.isDeadOrDying() && !dead.isRemoved());
        this.check("'" + DEAD + "' was saved starving (foodLevel 0 on disk) and came back fed (food "
                + "level " + dead.getFoodData().getFoodLevel() + ")",
                dead.getFoodData().getFoodLevel() > 0);
        this.check("'" + DEAD + "' kept the inventory it died with ("
                + countOf(dead, Items.EMERALD) + " emeralds)",
                countOf(dead, Items.EMERALD) == EMERALDS);
        this.check("'" + DEAD + "' has exactly the inventory the first run saved ("
                + describeInventory(dead) + ")",
                describeInventory(dead).equals(this.expectations.getProperty("dead.items")));
    }

    /** The old bug removed the bot within 20 ticks of joining; it has to survive far longer. */
    private void verifyStillAlive() {
        ServerPlayer dead = player(DEAD);
        if (dead == null) {
            this.check("'" + DEAD + "' is still in the world " + ALIVE_TICKS_REQUIRED
                    + " ticks after joining", false);
        } else {
            this.check("'" + DEAD + "' is still alive and in the world " + ALIVE_TICKS_REQUIRED
                    + " ticks after joining (health " + dead.getHealth() + ", removed "
                    + dead.isRemoved() + ")", !dead.isDeadOrDying() && !dead.isRemoved());
        }

        // The one deliberate way to destroy a bot's save. It must refuse while the bot is online:
        // its state would simply be written back out when it leaves.
        int refused = this.run("mcagent spawn " + LIVING + " fresh");
        this.check("'spawn ... fresh' is refused while the bot is online (result " + refused + ")",
                refused == 0);

        // The removal path a hot reload and a server stop both use: it saves the bot and must not
        // delete what it just saved.
        this.run("mcagent remove " + LIVING);
        this.run("mcagent remove " + DEAD);
        this.check("playerdata is still on disk after a normal removal (the path "
                + "/mcagent reload takes)", Files.isRegularFile(playerFile(LIVING)));
        this.check("the dead bot's repaired state is still on disk after removal",
                Files.isRegularFile(playerFile(DEAD)));
        LOG.info("PERSISTTEST '{}' file after removal: {}", LIVING, describePlayerFile(LIVING));

        // And now the deliberate wipe, which is the only thing that may destroy it.
        int fresh = this.run("mcagent spawn " + LIVING + " fresh");
        ServerPlayer freshBot = player(LIVING);
        this.holdStill(LIVING);
        this.check("'spawn ... fresh' succeeded (result " + fresh + ")", fresh > 0 && freshBot != null);
        this.check("'spawn ... fresh' gave the bot an empty pack: " + describeInventory(freshBot),
                freshBot != null && countOf(freshBot, Items.DIAMOND) == 0
                        && countOf(freshBot, Items.GOLDEN_APPLE) == 0);
        this.check("'spawn ... fresh' also cleared the respawn point ("
                + describeRespawn(freshBot) + ")", freshBot != null
                        && freshBot.getRespawnPosition() == null);

        LOG.info("PERSISTTEST ================ RESULT ================");
        LOG.info("PERSISTTEST restart survived (inventory, respawn point): {}",
                this.failed ? "see the FAIL lines above" : "PASS");
        LOG.info("PERSISTTEST VERDICT      : {}", this.failed ? "FAIL" : "PASS");
        LOG.info("PERSISTTEST ======================================");
        this.finish();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Dispatch a command exactly as the console would, keeping its output in the log.
     *
     * @return the command's result, so a test can tell a refusal from a success
     */
    private int run(String command) {
        try {
            // Dispatched through the dispatcher rather than performPrefixedCommand because that
            // returns void, and this test has to be able to tell "refused" from "done".
            CommandSourceStack source = this.server.createCommandSourceStack().withPermission(4);
            var dispatcher = this.server.getCommands().getDispatcher();
            int result = dispatcher.execute(dispatcher.parse(command, source));
            LOG.info("PERSISTTEST ran: /{} (result {})", command, result);
            return result;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOG.info("PERSISTTEST ran: /{} (refused: {})", command, e.getMessage());
            return 0;
        } catch (Throwable t) {
            LOG.error("PERSISTTEST command failed: /{}", command, t);
            this.fail();
            return 0;
        }
    }

    /**
     * Stop a freshly spawned bot's brain from making decisions.
     *
     * <p>This test is about what is on disk, not about what a model chooses to do, and a bot that
     * wanders off or drops what it was given would ruin the measurement. Pausing is how the operator
     * would do it too, and unlike touching the LLM configuration it changes nothing on disk.
     */
    private void holdStill(String name) {
        var brains = Agent.brainManager();
        ServerPlayer bot = player(name);
        if (brains == null || bot == null) {
            return;
        }
        var brain = brains.get(bot.getUUID());
        if (brain == null) {
            LOG.warn("PERSISTTEST '{}' has no brain to pause; a model could move it during the test",
                    name);
            return;
        }
        brain.setPaused(true, "the persistence test needs the bot to stay put");
        LOG.info("PERSISTTEST paused the brain of '{}' so no model can move it", name);
    }

    private void check(String what, boolean ok) {
        if (!ok) {
            this.failed = true;
        }
        LOG.info("PERSISTTEST {} : {}", ok ? "PASS" : "FAIL", what);
    }

    private void fail() {
        this.failed = true;
    }

    private ServerPlayer player(String name) {
        BotManager manager = Agent.botManager();
        if (manager == null) {
            return null;
        }
        BotManager.BotHandle handle = manager.get(name);
        return handle == null ? null : handle.player();
    }

    private Path playerFile(String name) {
        UUID uuid = UUIDUtil.createOfflineProfile(name).getId();
        return this.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    /** "7x minecraft:diamond, 3x minecraft:golden_apple", or "(empty)". */
    private static String describeInventory(ServerPlayer bot) {
        StringBuilder out = new StringBuilder();
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(stack.getCount()).append("x ")
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        return out.length() == 0 ? "(empty)" : out.toString();
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

    private static String describeRespawn(ServerPlayer bot) {
        BlockPos pos = bot.getRespawnPosition();
        return pos == null ? "(none)"
                : pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        + " in " + bot.getRespawnDimension().location();
    }

    /**
     * What a bot's playerdata file says, read straight from disk.
     *
     * <p>This is the state that has to cross the restart, so it is worth printing as the game itself
     * wrote it rather than as the running bot reports it.
     */
    private String describePlayerFile(String name) {
        Path path = playerFile(name);
        if (!Files.isRegularFile(path)) {
            return path.getFileName() + " MISSING";
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            StringBuilder items = new StringBuilder();
            ListTag inventory = tag.getList("Inventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < inventory.size(); i++) {
                CompoundTag entry = inventory.getCompound(i);
                if (items.length() > 0) {
                    items.append(", ");
                }
                // "count" is omitted by the codec when it is 1.
                items.append(entry.contains("count") ? entry.getInt("count") : 1)
                        .append("x ").append(entry.getString("id"));
            }
            return String.format(Locale.ROOT,
                    "%s (%d bytes) Health=%.1f DeathTime=%d foodLevel=%s "
                            + "Spawn=(%d, %d, %d) SpawnDimension=%s items=[%s]",
                    path.getFileName(), Files.size(path), tag.getFloat("Health"),
                    tag.getShort("DeathTime"),
                    tag.contains("foodLevel", Tag.TAG_ANY_NUMERIC) ? tag.getInt("foodLevel") : "?",
                    tag.getInt("SpawnX"), tag.getInt("SpawnY"), tag.getInt("SpawnZ"),
                    tag.contains("SpawnDimension", Tag.TAG_STRING) ? tag.getString("SpawnDimension") : "-",
                    items);
        } catch (Throwable t) {
            return path.getFileName() + " unreadable: " + t;
        }
    }

    /**
     * A bot's saved food level, read straight from its playerdata file.
     *
     * <p>Read from disk rather than from the live bot because that is the state the restart has to
     * carry: the running bot's food bar is refilled by the game itself on Peaceful difficulty.
     */
    private int foodLevelOnDisk(String name) {
        Path path = playerFile(name);
        if (!Files.isRegularFile(path)) {
            return -1;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            return tag.contains("foodLevel", Tag.TAG_ANY_NUMERIC) ? tag.getInt("foodLevel") : -1;
        } catch (Throwable t) {
            LOG.error("PERSISTTEST: could not read {} for its food level", path, t);
            return -1;
        }
    }

    private void finish() {
        this.finished = true;
        LOG.info("PERSISTTEST: done, stopping server");
        this.server.halt(false);
    }
}
