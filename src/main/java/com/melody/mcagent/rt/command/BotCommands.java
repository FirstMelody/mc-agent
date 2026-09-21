package com.melody.mcagent.rt.command;

import java.util.Collection;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.Config;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.bot.BotManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Operator-facing commands for managing bots.
 *
 * <p>These require permission level 2 (operator), so ordinary players cannot create or destroy
 * bots. The bots themselves are never operators: they act through the whitelisted action API only.
 */
public final class BotCommands {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("mcagent/commands");

    private BotCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("mcagent")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(BotCommands::list))
                .then(Commands.literal("status").executes(BotCommands::status))
                .then(Commands.literal("inventory")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::openInventory)))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> spawn(ctx, null, false))
                                // "fresh" is the one deliberate way to destroy a bot's saved state,
                                // so it is spelled out on the command line rather than implied by
                                // anything: spawning, removing or restarting all keep it.
                                .then(Commands.literal("fresh")
                                        .executes(ctx -> spawn(ctx, null, true)))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"), false))
                                        .then(Commands.literal("fresh")
                                                .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                                        true))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::remove)))
                .then(Commands.literal("goto")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(BotCommands::gotoPos))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::stop)))
                .then(Commands.literal("return")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::returnToSpawn)))
                .then(Commands.literal("escape")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> escapeUp(ctx, ""))
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> escapeUp(ctx,
                                                StringArgumentType.getString(ctx, "item"))))))
                .then(Commands.literal("removeall").executes(BotCommands::removeAll))
                .then(Commands.literal("pause")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setPaused(ctx, true)))
                        .then(Commands.literal("all").executes(ctx -> setPausedAll(ctx, true))))
                .then(Commands.literal("resume")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setPaused(ctx, false)))
                        .then(Commands.literal("all").executes(ctx -> setPausedAll(ctx, false))))
                .then(Commands.literal("think")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::thinkNow)))
                // Convention across this mod is "<verb> <bot> [args]", so the goal text follows the
                // bot name rather than being buried after a keyword. Two explicit subcommands
                // ("show"/"clear") are kept for the cases where the intent is not the text itself.
                .then(Commands.literal("goal")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BotCommands::goalShow)
                                .then(Commands.literal("show").executes(BotCommands::goalShow))
                                .then(Commands.literal("clear").executes(BotCommands::goalClear))
                                .then(Commands.literal("once")
                                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                                .executes(ctx -> goalOnce(ctx,
                                                        StringArgumentType.getString(ctx, "goal")))))
                                .then(Commands.argument("goal", StringArgumentType.greedyString())
                                        .executes(ctx -> goalSet(ctx,
                                                StringArgumentType.getString(ctx, "goal"))))))
                .then(Commands.literal("reloadconfig").executes(BotCommands::reloadConfig))
                .then(Commands.literal("llm")
                        .executes(BotCommands::llmShow)
                        .then(Commands.literal("show").executes(BotCommands::llmShow))
                        .then(Commands.literal("test").executes(BotCommands::llmTest))
                        .then(Commands.literal("endpoint")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> llmSet(ctx, "endpoint",
                                                StringArgumentType.getString(ctx, "url")))))
                        .then(Commands.literal("key")
                                .then(Commands.argument("key", StringArgumentType.greedyString())
                                        .executes(ctx -> llmSet(ctx, "key",
                                                StringArgumentType.getString(ctx, "key")))))
                        .then(Commands.literal("model")
                                .then(Commands.argument("model", StringArgumentType.greedyString())
                                        .executes(ctx -> llmSet(ctx, "model",
                                                StringArgumentType.getString(ctx, "model")))))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("tokens", IntegerArgumentType.integer(1000, 500000))
                                        .executes(ctx -> llmSet(ctx, "budget",
                                                String.valueOf(IntegerArgumentType.getInteger(ctx, "tokens"))))))
                        .then(Commands.literal("maxtokens")
                                .then(Commands.argument("tokens", IntegerArgumentType.integer(64, 32768))
                                        .executes(ctx -> llmSet(ctx, "maxtokens",
                                                String.valueOf(IntegerArgumentType.getInteger(ctx, "tokens"))))))
                        .then(Commands.literal("header")
                                .then(Commands.argument("namevalue", StringArgumentType.greedyString())
                                        .executes(ctx -> llmAddHeader(ctx,
                                                StringArgumentType.getString(ctx, "namevalue")))))
                        .then(Commands.literal("opencodego")
                                .executes(ctx -> llmPresetOpencodeGo(ctx)))
                        .then(Commands.literal("clear").executes(BotCommands::llmClear))));
    }

    private static BotManager manager() {
        return Agent.botManager();
    }

    /** Report the brain/knowledge state, which is what you check when a bot seems inert. */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        var brains = Agent.brainManager();
        if (brains == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "LLM configured: " + brains.isConfigured()), false);

        var policy = brains.policy();
        if (policy != null) {
            ctx.getSource().sendSuccess(() -> Component.literal(policy.describe()), false);
        }

        var knowledge = Agent.knowledge();
        if (knowledge == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("Item/recipe index: NOT BUILT"), false);
        } else {
            var summary = knowledge.summary();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Item/recipe index: " + summary.get("items") + " items, "
                            + summary.get("recipes") + " recipes"), false);
        }

        String brainsText = brains.describe();
        for (String line : brainsText.split("\n")) {
            if (!line.isBlank()) {
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        BotManager manager = manager();
        if (manager == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }
        Collection<BotManager.BotHandle> handles = manager.handles();
        if (handles.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No bots online"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Bots online: " + handles.size()), false);
        for (BotManager.BotHandle handle : handles) {
            var bot = handle.player();
            String line = String.format(
                    " - %s at %.1f %.1f %.1f (%s)%s",
                    handle.name(),
                    bot.getX(), bot.getY(), bot.getZ(),
                    bot.serverLevel().dimension().location(),
                    handle.movement().hasTarget() ? " -> moving" : "");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return handles.size();
    }

    /** Open an online bot's 36-slot pack as a live, remotely usable inventory screen. */
    private static int openInventory(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        if (viewer == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Only an in-game player can open an Agent inventory screen"));
            return 0;
        }
        BotManager manager = manager();
        String name = StringArgumentType.getString(ctx, "name");
        BotManager.BotHandle handle = manager == null ? null : manager.get(name);
        if (handle == null) {
            ctx.getSource().sendFailure(Component.literal("Bot '" + name + "' is not online"));
            return 0;
        }

        ServerPlayer target = handle.player();
        Container pack = new MainInventoryView(target);
        viewer.openMenu(new SimpleMenuProvider(
                (id, viewerInventory, player) -> new ChestMenu(
                        MenuType.GENERIC_9x4, id, viewerInventory, pack, 4),
                Component.literal(name + "'s inventory")));
        ctx.getSource().sendSuccess(
                () -> Component.literal("Opened bot '" + name + "' inventory"), false);
        return 1;
    }

    /**
     * A live rectangular view of a bot's main inventory.
     *
     * <p>{@link Inventory} also exposes armor and offhand as slots 36-40 and refuses interaction
     * beyond four blocks. The command promises the ordinary 36-slot pack and is explicitly a
     * remote operator control, so this adapter exposes exactly those slots and keeps the menu valid
     * while the bot remains online.
     */
    private static final class MainInventoryView implements Container {
        private static final int SIZE = 36;
        private final ServerPlayer bot;
        private final Inventory inventory;

        private MainInventoryView(ServerPlayer bot) {
            this.bot = bot;
            this.inventory = bot.getInventory();
        }

        @Override
        public int getContainerSize() {
            return SIZE;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < SIZE; slot++) {
                if (!this.inventory.getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot >= 0 && slot < SIZE ? this.inventory.getItem(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return this.inventory.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return this.inventory.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot >= 0 && slot < SIZE) {
                this.inventory.setItem(slot, stack);
            }
        }

        @Override
        public void setChanged() {
            this.inventory.setChanged();
        }

        @Override
        public boolean stillValid(Player viewer) {
            return !this.bot.isRemoved() && !this.bot.hasDisconnected();
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < SIZE; slot++) {
                this.inventory.setItem(slot, ItemStack.EMPTY);
            }
            this.inventory.setChanged();
        }
    }

    /**
     * Spawn a bot, restoring whatever it was saved with.
     *
     * <p>Usage: {@code /mcagent spawn <name> [pos] [fresh]}.
     *
     * <p>Without {@code fresh} the bot rejoins with its inventory, XP and respawn point, exactly as a
     * player does after a restart. {@code fresh} deletes that saved state first, so the bot starts
     * over with nothing; it is the only command in this mod that destroys a bot's belongings, and it
     * has to be typed on purpose.
     */
    private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 requested, boolean fresh) {
        BotManager manager = manager();
        if (manager == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        ServerLevel level = ctx.getSource().getLevel();

        if (fresh && manager.get(name) != null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bot '" + name + "' is online. Remove it first, then spawn it with 'fresh'."));
            return 0;
        }

        Vec3 requestedPos = requested;
        if (requestedPos == null) {
            var sourcePos = ctx.getSource().getPosition();
            requestedPos = new Vec3(sourcePos.x, sourcePos.y, sourcePos.z);
        }
        Vec3 pos = requestedPos;

        if (fresh) {
            manager.wipeSavedData(name);
        }

        // Persist by default. A bot's save file is its inventory, its XP, its effects and its
        // respawn point - its memory of the world - and it must survive both a restart and the
        // removal that a hot reload performs.
        BotManager.BotHandle handle = manager.spawn(name, level, pos, true);
        if (handle == null) {
            ctx.getSource().sendFailure(Component.literal("Failed to spawn bot '" + name + "'"));
            return 0;
        }

        // Attach the LLM brain, if a model is configured. Without one the bot still exists and can
        // be steered by /mcagent goto, which is useful for testing movement in isolation.
        boolean brainAttached = Agent.attachBrain(handle.player());

        ctx.getSource().sendSuccess(
                () -> Component.literal("Spawned bot '" + name + "' at " + pos
                        + (fresh ? " with a fresh save (its previous inventory and respawn point are gone)"
                                 : " (its saved inventory and respawn point, if any, were restored)")
                        + (brainAttached ? " with an LLM brain" : " (no LLM configured - command-driven only)")),
                true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        BotManager manager = manager();
        if (manager == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (!manager.remove(name)) {
            ctx.getSource().sendFailure(Component.literal("No such bot: " + name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed bot '" + name + "'"), true);
        return 1;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        BotManager manager = manager();
        if (manager == null) {
            return 0;
        }
        int count = manager.handles().size();
        manager.removeAll();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + count + " bot(s)"), true);
        return count;
    }

    private static int gotoPos(CommandContext<CommandSourceStack> ctx) {
        BotManager manager = manager();
        if (manager == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        BotManager.BotHandle handle = manager.get(name);
        if (handle == null) {
            ctx.getSource().sendFailure(Component.literal("No such bot: " + name));
            return 0;
        }
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        // A* around obstacles, rather than walking blindly into them.
        var plan = handle.movement().setPathTarget(net.minecraft.core.BlockPos.containing(pos), 128);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Bot '" + name + "' walking to " + pos
                        + (plan == com.melody.mcagent.rt.bot.MovementDriver.Plan.FOUND ? ""
                                : " (" + plan.name().toLowerCase(java.util.Locale.ROOT) + ")")), true);
        return plan == com.melody.mcagent.rt.bot.MovementDriver.Plan.FOUND ? 1 : 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        BotManager manager = manager();
        if (manager == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        BotManager.BotHandle handle = manager.get(name);
        if (handle == null) {
            ctx.getSource().sendFailure(Component.literal("No such bot: " + name));
            return 0;
        }
        handle.movement().clear();
        ctx.getSource().sendSuccess(() -> Component.literal("Bot '" + name + "' stopped"), true);
        return 1;
    }

    /** Emergency operator control: move a bot to its vanilla respawn point without killing it. */
    private static int returnToSpawn(CommandContext<CommandSourceStack> ctx) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brains = Agent.brainManager();
        var brain = brains == null ? null : brains.get(handle.player().getUUID());
        String outcome;
        if (brain != null) {
            outcome = brain.returnToSpawnNow();
        } else {
            handle.movement().clear();
            Actions.Result result = Actions.returnToRespawn(handle.player());
            outcome = result.success() ? result.message() : "failed: " + result.message();
        }
        if (outcome.startsWith("failed:")) {
            ctx.getSource().sendFailure(Component.literal(outcome));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' " + outcome), true);
        return 1;
    }

    /** Ask the bot's deterministic escape planner to excavate and climb a real staircase. */
    private static int escapeUp(CommandContext<CommandSourceStack> ctx, String item) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        String outcome = brain.escapeUp(item);
        if (outcome.startsWith("failed:")) {
            ctx.getSource().sendFailure(Component.literal(outcome));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' " + outcome), true);
        return 1;
    }

    // --- bot control ----------------------------------------------------------------------------

    /** Resolve a bot handle and its brain, or report why we cannot. */
    @Nullable
    private static BotManager.BotHandle resolve(CommandContext<CommandSourceStack> ctx, String name) {
        BotManager manager = manager();
        if (manager == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return null;
        }
        BotManager.BotHandle handle = manager.get(name);
        if (handle == null) {
            ctx.getSource().sendFailure(Component.literal("No such bot: " + name));
            return null;
        }
        return handle;
    }

    /** The brain for a bot, or null with a message explaining that it has none. */
    @Nullable
    private static com.melody.mcagent.rt.brain.AgentBrain brainOf(CommandContext<CommandSourceStack> ctx,
                                                               BotManager.BotHandle handle) {
        var brains = Agent.brainManager();
        var brain = brains == null ? null : brains.get(handle.player().getUUID());
        if (brain == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bot '" + handle.name() + "' has no brain attached. Set the LLM settings "
                    + "(/mcagent llm ...) and it will be attached automatically."));
        }
        return brain;
    }

    /** Freeze or unfreeze one bot. */
    private static int setPaused(CommandContext<CommandSourceStack> ctx, boolean paused) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        brain.setPaused(paused, actor(ctx));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' " + (paused ? "paused" : "resumed")), true);
        return 1;
    }

    /** Freeze or unfreeze every bot that has a brain. */
    private static int setPausedAll(CommandContext<CommandSourceStack> ctx, boolean paused) {
        var brains = Agent.brainManager();
        if (brains == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }
        int count = 0;
        for (var brain : brains.all()) {
            brain.setPaused(paused, actor(ctx));
            count++;
        }
        final int total = count;
        ctx.getSource().sendSuccess(() -> Component.literal(
                (paused ? "Paused " : "Resumed ") + total + " bot(s)"), true);
        return count;
    }

    /**
     * Who is issuing this command, for the log.
     *
     * <p>Resolved here rather than in the brain: only the command layer knows, and "who paused this
     * bot" is the first thing anyone asks when a bot turns out to have been frozen for an hour.
     */
    private static String actor(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getTextName();
    }

    /** Ask a bot to make a decision immediately, instead of waiting for its cooldown. */
    private static int thinkNow(CommandContext<CommandSourceStack> ctx) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        if (brain.isPaused()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bot '" + handle.name() + "' is paused; resume it first."));
            return 0;
        }
        if (brain.isThinking()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Bot '" + handle.name() + "' is already thinking."));
            return 0;
        }
        brain.requestDecisionNow();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' is thinking now"), true);
        return 1;
    }

    /** Show a bot's standing objective. */
    private static int goalShow(CommandContext<CommandSourceStack> ctx) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        String goal = brain.standingGoal();
        ctx.getSource().sendSuccess(() -> Component.literal(goal == null
                ? "Bot '" + handle.name() + "' has no standing goal."
                : "Bot '" + handle.name() + "' goal: " + goal), false);
        return 1;
    }

    /**
     * Set a standing objective that survives compaction.
     *
     * <p>Pinned beside the bot's identity prompt, so a long-running task does not get forgotten when
     * the transcript is compacted — which is exactly what would happen to an ordinary message.
     */
    private static int goalSet(CommandContext<CommandSourceStack> ctx, String goal) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        brain.setStandingGoal(goal);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' goal set: " + goal), true);
        return 1;
    }

    /** Remove a bot's standing objective. */
    private static int goalClear(CommandContext<CommandSourceStack> ctx) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        brain.setStandingGoal(null);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' goal cleared"), true);
        return 1;
    }

    /** Give a one-off instruction, delivered as the next user message. */
    private static int goalOnce(CommandContext<CommandSourceStack> ctx, String goal) {
        BotManager.BotHandle handle = resolve(ctx, StringArgumentType.getString(ctx, "name"));
        if (handle == null) {
            return 0;
        }
        var brain = brainOf(ctx, handle);
        if (brain == null) {
            return 0;
        }
        brain.primeGoal(goal);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Bot '" + handle.name() + "' instructed: " + goal), true);
        return 1;
    }

    // --- configuration and LLM management -------------------------------------------------------

    /**
     * Re-read the config files from disk and apply them, keeping the bots alive.
     *
     * <p>NeoForge normally notices file edits by itself, but that relies on a watcher and on the
     * edit preserving the file in a way the watcher sees. An explicit command makes the behaviour
     * deterministic: an operator who has just edited a file can force the change to take effect
     * rather than wondering whether it was picked up. The re-read itself lives in the core
     * ({@code AgentConfig.rereadFromDisk}) because the path that used to be taken here made the
     * loader print the whole config — including the API key — into the log.
     *
     * <p>{@code /mcagent reload} is a different thing — it replaces the runtime jar and removes all
     * bots — so this one is named for what it actually does.
     */
    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        boolean reread = AgentConfig.rereadFromDisk();

        // The re-read fires a config event, which applies the values; calling apply() again is
        // harmless and covers the failure path, where nothing was re-read.
        Config.apply();

        if (!reread) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Applied the in-memory configuration (the files could not be re-read; see the log)"),
                    false);
        }

        var settings = Config.settings();
        ctx.getSource().sendSuccess(() -> Component.literal("Configuration reloaded: "
                + settings.describeMasked()), true);
        if (!settings.isUsable()) {
            ctx.getSource().sendFailure(Component.literal(
                    "LLM is not usable yet: " + settings.problem()));
        }
        return 1;
    }

    /** Show the current LLM settings with the key masked. */
    private static int llmShow(CommandContext<CommandSourceStack> ctx) {
        var brains = Agent.brainManager();
        if (brains == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }
        var settings = Config.settings();
        ctx.getSource().sendSuccess(() -> Component.literal("LLM: " + settings.describeMasked()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Active: " + brains.isConfigured() + "  brains attached: " + brains.all().size()), false);
        if (!settings.isUsable()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Problem: " + settings.problem()), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set with: /mcagent llm endpoint <url> | key <key> | model <id> | budget <tokens>"
                        + " | maxtokens <tokens>"), false);
        return 1;
    }

    /**
     * Probe the endpoint for real.
     *
     * <p>Run off-thread: a network round trip on the server thread would stall the tick, and an
     * unreachable host would stall it for the full timeout. The result is reported back to the
     * server thread once it arrives.
     */
    private static int llmTest(CommandContext<CommandSourceStack> ctx) {
        var brains = Agent.brainManager();
        if (brains == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }

        var source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("Testing the LLM endpoint..."), false);

        Thread worker = new Thread(() -> {
            String verdict = brains.testConnection();
            source.getServer().execute(() -> {
                source.sendSuccess(() -> Component.literal("LLM test: " + verdict), true);
            });
        }, "mcagent-llm-test");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /**
     * Change one LLM setting, persist it, and adopt it immediately.
     *
     * <p>{@code ConfigValue.set} updates the in-memory value and {@code save} writes the file; doing
     * both means the change survives a restart and applies to the running server without one.
     */
    private static int llmSet(CommandContext<CommandSourceStack> ctx, String what, String value) {
        var brains = Agent.brainManager();
        if (brains == null) {
            ctx.getSource().sendFailure(Component.literal("MC Agent is not initialised"));
            return 0;
        }

        String cleaned = value.trim();
        if (cleaned.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Value must not be empty"));
            return 0;
        }

        try {
            switch (what) {
                case "endpoint" -> {
                    // Accept a URL with or without the /v1 suffix, since both are common.
                    String url = cleaned.endsWith("/") ? cleaned.substring(0, cleaned.length() - 1) : cleaned;
                    AgentConfig.LLM.baseUrl.set(url);
                    AgentConfig.LLM.baseUrl.save();
                }
                case "key" -> {
                    AgentConfig.LLM.apiKey.set(cleaned);
                    AgentConfig.LLM.apiKey.save();
                    restrictKeyFilePermissions(ctx);
                }
                case "model" -> {
                    AgentConfig.LLM.model.set(cleaned);
                    AgentConfig.LLM.model.save();
                }
                case "budget" -> {
                    int tokens = Integer.parseInt(cleaned);
                    AgentConfig.COMMON.contextTokenBudget.set(tokens);
                    AgentConfig.COMMON.contextTokenBudget.save();
                    brains.applyContextBudget(tokens);
                }
                case "maxtokens" -> {
                    // Output cap for one reply. Worth a live command because the right value depends
                    // on the model: a thinking model can spend the whole budget before it emits a
                    // tool call, and a bot that hits the cap simply does nothing that turn.
                    int tokens = Integer.parseInt(cleaned);
                    if (tokens < 64 || tokens > 32768) {
                        ctx.getSource().sendFailure(Component.literal(
                                "maxTokens must be between 64 and 32768"));
                        return 0;
                    }
                    AgentConfig.LLM.maxTokens.set(tokens);
                    AgentConfig.LLM.maxTokens.save();
                }
                default -> {
                    ctx.getSource().sendFailure(Component.literal("Unknown setting: " + what));
                    return 0;
                }
            }
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal("Could not set " + what + ": " + t.getMessage()));
            return 0;
        }

        // Adopt immediately so the operator does not have to reload as well.
        Config.apply();

        var settings = Config.settings();
        // Never echo a key back, even to an operator: chat and logs are retained.
        String shown = what.equals("key") ? "(hidden)" : cleaned;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Set llm " + what + " = " + shown + ". Now: " + settings.describeMasked()), true);

        if (settings.isUsable() && !brains.isConfigured()) {
            int attached = brains.attachAll();
            if (attached > 0) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "Attached brains to " + attached + " existing bot(s)"), true);
            }
        }
        if (!settings.isUsable()) {
            ctx.getSource().sendFailure(Component.literal("Still incomplete: " + settings.problem()));
        }
        return 1;
    }

    /**
     * Tighten the permissions on the file that now holds an API key.
     *
     * <p>NeoForge writes config files with the default umask, which on a typical server leaves them
     * world-readable. Having just written a secret into one, we narrow it to owner-only where the
     * filesystem supports POSIX permissions. Failure is logged, not fatal: the key is already in
     * use and the operator can fix permissions themselves.
     */
    private static void restrictKeyFilePermissions(CommandContext<CommandSourceStack> ctx) {
        try {
            var path = ctx.getSource().getServer().getServerDirectory()
                    .resolve("config").resolve("mcagent-llm.toml");
            if (java.nio.file.Files.exists(path)) {
                java.nio.file.Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                LOG.info("Restricted {} to owner-only", path);
            }
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem (e.g. Windows); nothing to do.
        } catch (Throwable t) {
            LOG.warn("Could not restrict permissions on the LLM config file; "
                    + "it contains an API key and should be protected manually", t);
        }
    }

    /**
     * Add or replace an extra request header, e.g. {@code x-opencode-session: mybot}.
     *
     * <p>Some gateways need a routing header on top of Authorization. OpenCode Go, for example,
     * rejects requests with {@code MissingSessionID} unless {@code x-opencode-session} is present.
     */
    private static int llmAddHeader(CommandContext<CommandSourceStack> ctx, String nameValue) {
        int colon = nameValue.indexOf(':');
        if (colon <= 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "Expected \"Name: value\", for example: x-opencode-session: mcagent"));
            return 0;
        }
        String name = nameValue.substring(0, colon).trim();
        String value = nameValue.substring(colon + 1).trim();
        if (name.isEmpty() || value.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Header name and value must not be empty"));
            return 0;
        }

        java.util.List<String> current = new java.util.ArrayList<>(
                AgentConfig.LLM.extraHeaders.get().stream().map(Object::toString).toList());
        // Replace an existing entry with the same name rather than accumulating duplicates.
        current.removeIf(entry -> entry.split(":", 2)[0].trim().equalsIgnoreCase(name));
        current.add(name + ": " + value);

        AgentConfig.LLM.extraHeaders.set(java.util.List.copyOf(current));
        AgentConfig.LLM.extraHeaders.save();
        Config.apply();

        ctx.getSource().sendSuccess(() -> Component.literal("Header set: " + name + ": " + value), true);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Run /mcagent llm test to confirm the endpoint accepts it."), false);
        return 1;
    }

    /**
     * Configure the endpoint for OpenCode Go in one step.
     *
     * <p>Exists because this provider needs a non-obvious combination: a specific base path, and the
     * {@code x-opencode-session} header without which it answers {@code MissingSessionID}.
     */
    private static int llmPresetOpencodeGo(CommandContext<CommandSourceStack> ctx) {
        AgentConfig.LLM.baseUrl.set("https://opencode.ai/zen/go/v1");
        AgentConfig.LLM.baseUrl.save();
        if (AgentConfig.LLM.model.get() == null || AgentConfig.LLM.model.get().isBlank()) {
            AgentConfig.LLM.model.set("deepseek-v4.1-flash");
            AgentConfig.LLM.model.save();
        }

        java.util.List<String> headers = new java.util.ArrayList<>(
                AgentConfig.LLM.extraHeaders.get().stream().map(Object::toString).toList());
        headers.removeIf(e -> e.split(":", 2)[0].trim().equalsIgnoreCase("x-opencode-session"));
        headers.add("x-opencode-session: mcagent");
        AgentConfig.LLM.extraHeaders.set(java.util.List.copyOf(headers));
        AgentConfig.LLM.extraHeaders.save();

        Config.apply();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "Configured for OpenCode Go: baseUrl + x-opencode-session header. "
                + "Now set the key with /mcagent llm key <key>, then /mcagent llm test."), true);
        return 1;
    }

    /** Clear the key, disabling LLM calls without disturbing the bots. */
    private static int llmClear(CommandContext<CommandSourceStack> ctx) {
        AgentConfig.LLM.apiKey.set("");
        AgentConfig.LLM.apiKey.save();
        Config.apply();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "LLM key cleared. Bots remain in the world but will not think until a key is set."), true);
        return 1;
    }
}
