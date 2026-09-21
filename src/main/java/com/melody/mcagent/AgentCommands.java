package com.melody.mcagent;

import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The commands that must exist whether or not a runtime is loaded.
 *
 * <p>{@code /mcagent reload} in particular cannot live in the runtime: its job is to recover from a
 * runtime that failed to load or has just been rebuilt, so it has to be registered by the code that
 * survives the reload.
 *
 * <p>The rest of {@code /mcagent} is registered by the runtime on the same dispatcher. The two
 * registrations merge, which is why this class registers its subcommands before delegating.
 */
public final class AgentCommands {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/commands");

    private AgentCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mcagent")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("runtime").executes(AgentCommands::runtime))
                .then(Commands.literal("reload").executes(AgentCommands::reload)));
    }

    /** Report which runtime is loaded, from where, and what it says about itself. */
    private static int runtime(CommandContext<CommandSourceStack> ctx) {
        for (String line : RuntimeHost.describe().split("\n")) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return RuntimeHost.runtime() == null ? 0 : 1;
    }

    /**
     * Replace the loaded runtime with whatever the jar now contains.
     *
     * <p>Removing every bot first is deliberate: a reload must not try to carry live players across
     * two generations of code, and an orphaned {@code ServerPlayer} is also a hard reference from
     * the server to the old loader. The removal saves each bot's playerdata like any logout but does
     * not delete it, so the world after a reload is not the same as a restart: the bots are gone,
     * what they were carrying is not, and spawning them again restores it.
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(
                "Reloading the MC Agent runtime from " + RuntimeHost.jarPath()
                        + " - this removes all bots; their saved inventory and respawn point are "
                        + "kept, so spawn them again afterwards"), true);

        // Re-read the config files first, so a jar swap also picks up an edit. Failure is not
        // fatal: the values already in memory are what the fresh runtime will use, and the file
        // watcher has normally applied an edit long before this command is typed.
        if (!AgentConfig.rereadFromDisk()) {
            LOG.warn("Proceeding with the configuration already in memory");
        }

        boolean loaded = RuntimeHost.reload();
        AgentRuntime current = RuntimeHost.runtime();
        if (loaded && current != null) {
            source.sendSuccess(() -> Component.literal("Runtime reloaded: " + current.status()), true);
            return 1;
        }
        source.sendFailure(Component.literal(
                "Runtime reload FAILED - no runtime is installed, so /mcagent is reduced to "
                        + "runtime/reload. See the log for the cause, fix the jar, then run "
                        + "/mcagent reload again."));
        return 0;
    }
}
