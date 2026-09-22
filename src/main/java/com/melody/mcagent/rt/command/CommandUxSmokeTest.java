package com.melody.mcagent.rt.command;

import java.util.List;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.brain.AgentBrain;
import com.melody.mcagent.rt.bot.BotManager;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end proof of the two operator-facing gaps a player hit in practice:
 *
 * <ol>
 *   <li>{@code /mcagent spawn <name>} with no coordinates used to drop the bot wherever the operator
 *       was standing, so a bot that had been mining 200 blocks away came back at the base. It must
 *       resume at the position it logged out at, in the dimension it logged out in.</li>
 *   <li>Every argument that takes a bot name had no completion at all, so names had to be typed from
 *       memory - and they are case-sensitive player names.</li>
 * </ol>
 *
 * <p>Both assertions are written so that the old behaviour fails them: the resumed position is
 * compared against the position of the command source, which is deliberately far away, and the
 * completion lists are required to contain the bot's name.
 *
 * <p>Enabled with {@code MCAGENT_CMD_UX_TEST=true}.
 */
public final class CommandUxSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/cmduxtest");
    private static final String BOT = "UxBot";
    /** How far from the world spawn the bot is put down, so "resumed" cannot be confused with
     * "spawned where the operator is". */
    private static final int OFFSET = 40;
    /** Every command whose first argument is a bot name. */
    private static final List<String> NAME_COMMANDS = List.of(
            "mcagent goto ", "mcagent pause ", "mcagent resume ", "mcagent think ",
            "mcagent stop ", "mcagent return ", "mcagent escape ", "mcagent remove ",
            "mcagent inventory ", "mcagent goal ");

    private final MinecraftServer server;
    private boolean started;
    private boolean finished;
    private int phase;
    private int phaseTick;
    private int ticks;
    private BlockPos plot;
    private Vec3 sourcePosition;
    private int failures;

    private CommandUxSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_CMD_UX_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("CMDUXTEST: armed");
        return new CommandUxSmokeTest(server);
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
        if (this.ticks - this.phaseTick > 400) {
            this.fail("phase " + this.phase + " timed out");
            this.finish();
            return;
        }
        switch (this.phase) {
            case 0 -> this.spawnAtPlot();
            case 1 -> this.removeBot();
            case 2 -> this.respawnWithoutCoordinates();
            case 3 -> this.checkCompletion();
            default -> this.finish();
        }
    }

    /** Phase 0: put a bot down at a known, far-from-spawn position, with solid ground under it. */
    private void spawnAtPlot() {
        this.run("mcagent spawn " + BOT + " " + this.plot.getX() + " " + this.plot.getY() + " "
                + this.plot.getZ());
        BotManager.BotHandle handle = this.handle();
        if (handle == null) {
            this.fail("the spawn command did not produce a bot");
            this.finish();
            return;
        }
        LOG.info("CMDUXTEST spawn       : bot at {} (requested {})",
                handle.player().blockPosition().toShortString(), this.plot.toShortString());
        this.nextPhase("remove the bot, keeping its save");
    }

    /** Phase 1: remove it. Its playerdata - including the logout position - survives. */
    private void removeBot() {
        if (this.handle() == null) {
            this.fail("the bot disappeared before it could be removed");
            this.finish();
            return;
        }
        this.run("mcagent remove " + BOT);
        if (this.handle() != null) {
            this.fail("the remove command left the bot online");
            this.finish();
            return;
        }
        if (!Agent.botManager().hasSavedData(BOT)) {
            this.fail("removing the bot also removed its saved data, so nothing can be resumed");
            this.finish();
            return;
        }
        this.nextPhase("spawn again with no coordinates");
    }

    /** Phase 2: spawn with no coordinates - it must come back where it logged out. */
    private void respawnWithoutCoordinates() {
        this.run("mcagent spawn " + BOT);
        BotManager.BotHandle handle = this.handle();
        if (handle == null) {
            this.fail("the second spawn produced no bot");
            this.finish();
            return;
        }
        Vec3 landed = handle.player().position();
        double fromPlot = landed.distanceTo(Vec3.atBottomCenterOf(this.plot));
        double fromSource = landed.distanceTo(this.sourcePosition);
        boolean resumed = fromPlot < 2.0D;
        LOG.info("CMDUXTEST resume      : landed={} requested={} distanceFromPlot={} "
                + "distanceFromCommandSource={} (a resume must land on the plot, not on the "
                + "operator)", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f",
                        landed.x, landed.y, landed.z), this.plot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.1f", fromPlot),
                String.format(java.util.Locale.ROOT, "%.1f", fromSource));
        if (!resumed) {
            this.fail("spawn without coordinates did not resume at the last logout position "
                    + "(landed " + String.format(java.util.Locale.ROOT, "%.1f", fromPlot)
                    + " blocks away from it)");
        }
        if (fromSource < 8.0D) {
            this.fail("the test is meaningless: the command source is next to the plot");
        }
        // Freeze it: with a live endpoint the brain starts deciding immediately and would walk off
        // before the completion checks run.
        this.run("mcagent pause " + BOT);
        this.nextPhase("completion for every bot-name argument");
    }

    /** Phase 3: every bot-name argument must complete to the online bot, and spawn to saved names. */
    private void checkCompletion() {
        CommandSourceStack source = this.server.createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(4);
        var dispatcher = this.server.getCommands().getDispatcher();

        for (String command : NAME_COMMANDS) {
            List<String> suggestions = this.suggest(dispatcher, source, command);
            boolean present = suggestions.contains(BOT);
            LOG.info("CMDUXTEST completion  : '{}' -> {} {}", command.trim(),
                    suggestions.isEmpty() ? "[]" : suggestions, present ? "(has " + BOT + ")" : "MISSING");
            if (!present) {
                this.fail("'" + command.trim() + "' does not complete to the online bot name");
            }
        }

        // A prefix must filter, not merely list everything.
        List<String> filtered = this.suggest(dispatcher, source, "mcagent goto Ux");
        if (!filtered.contains(BOT)) {
            this.fail("a partial name ('Ux') does not complete to the bot");
        }

        // Spawn completes to bots that are offline but still have a save: that is what a spawn
        // brings back. The bot is online right now, so pause/remove it first.
        this.run("mcagent remove " + BOT);
        List<String> spawnable = this.suggest(dispatcher, source, "mcagent spawn ");
        boolean resumable = spawnable.contains(BOT);
        LOG.info("CMDUXTEST completion  : 'mcagent spawn' -> {} {}", spawnable,
                resumable ? "(has " + BOT + ")" : "MISSING");
        if (!resumable) {
            this.fail("'mcagent spawn' does not complete to a bot that has saved data");
        }
        this.nextPhase("done");
    }

    /**
     * Ask the dispatcher for completions the way the client's tab key does.
     *
     * <p>Same two calls as {@code ServerGamePacketListenerImpl.handleCustomCommandSuggestions}:
     * parse the line as typed (including the trailing space), then take the suggestion list. It is
     * asynchronous in Brigadier 1.3, hence the join.
     */
    private List<String> suggest(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                 CommandSourceStack source, String command) {
        try {
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
            var suggestions = dispatcher.getCompletionSuggestions(parsed).join();
            List<String> out = new java.util.ArrayList<>();
            for (var suggestion : suggestions.getList()) {
                out.add(suggestion.getText());
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            LOG.warn("CMDUXTEST could not complete '{}': {}", command, t.toString());
            return List.of();
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        this.sourcePosition = this.server.createCommandSourceStack().getPosition();
        int x = spawn.getX() + OFFSET;
        int z = spawn.getZ() + OFFSET;
        int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = Math.max(spawn.getY() + 24, terrain + 16);
        this.plot = new BlockPos(x, ground, z);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= -1; dy++) {
                    level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        AgentBrain.shutdown();
        this.phaseTick = this.ticks;
    }

    private BotManager.BotHandle handle() {
        return Agent.botManager() == null ? null : Agent.botManager().get(BOT);
    }

    private void run(String command) {
        try {
            CommandSourceStack source = this.server.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withPermission(4);
            this.server.getCommands().performPrefixedCommand(source, command);
            LOG.info("CMDUXTEST ran: /{}", command);
        } catch (Throwable t) {
            LOG.error("CMDUXTEST command failed: /{}", command, t);
        }
    }

    private void nextPhase(String what) {
        this.phase++;
        this.phaseTick = this.ticks;
        LOG.info("CMDUXTEST phase {} : {}", this.phase, what);
    }

    private void fail(String why) {
        this.failures++;
        LOG.warn("CMDUXTEST problem     : {}", why);
    }

    private void finish() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        boolean pass = this.failures == 0;
        LOG.info("CMDUXTEST failures    : {}", this.failures);
        LOG.info("CMDUXTEST VERDICT     : {}", pass ? "PASS" : "FAIL");

        BotManager manager = Agent.botManager();
        if (manager != null) {
            manager.remove(BOT);
            manager.wipeSavedData(BOT);
        }
        if (this.plot != null) {
            ServerLevel level = this.server.overworld();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -3; dy <= -1; dy++) {
                        level.setBlockAndUpdate(this.plot.offset(dx, dy, dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        AgentBrain.shutdown();
        this.server.halt(false);
    }
}
