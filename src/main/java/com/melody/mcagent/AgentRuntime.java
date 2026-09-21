package com.melody.mcagent;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * The entire surface through which the core talks to the replaceable half of the mod.
 *
 * <p>The direction of this interface is the point of the whole design: the core may reference
 * {@code AgentRuntime}, and the runtime may reference anything, but the core must never name a
 * {@code com.melody.mcagent.rt.*} type. A core that held even one runtime object — a static field,
 * a live thread, or an event-bus listener — would keep the previous {@code ClassLoader} reachable,
 * and a reload would leak the whole loader instead of shedding it.
 *
 * <p>Implementations live in the runtime jar and are instantiated by {@link RuntimeHost}. Every
 * call arrives on the server thread, except {@link #onConfigChanged()}, which NeoForge fires from
 * the config watcher thread and which is expected to marshal itself.
 */
public interface AgentRuntime {

    /** The server is up: build managers, indexes and anything else the runtime needs. */
    void onServerStarted(MinecraftServer server);

    /** One server tick has completed. */
    void onServerTick(MinecraftServer server);

    /** The server is going down: stop threads and drop reference to every live game object. */
    void onServerStopping(MinecraftServer server);

    /** A player spoke; bots within earshot should be able to hear it. */
    void onServerChat(ServerChatEvent event);

    /** Add this module's commands to the server's dispatcher. */
    void registerCommands(RegisterCommandsEvent event);

    /**
     * A config file was (re)loaded. Not one of the game-bus handlers, but part of the same boundary:
     * the config spec lives in the core, and only the runtime knows what to do with its values.
     */
    void onConfigChanged();

    /** One line summarising the runtime, for {@code /mcagent runtime}. */
    String status();

    /**
     * Clean shutdown before this instance is discarded.
     *
     * <p>Called on reload and on server stop, always on the server thread. It must leave nothing
     * behind that the core or Minecraft still points at — in practice that means removing every bot
     * through the normal removal path, stopping worker threads, and clearing static caches.
     */
    void onUnload();
}
