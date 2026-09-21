package com.melody.mcagent;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MC Agent — LLM-driven synthetic players for a NeoForge server.
 *
 * <p>Everything here is server-side. The bots are real {@code ServerPlayer} entities with no
 * network client: they are moved by vanilla physics, perceive through server-side raycasts, and act
 * through a whitelisted tool API driven by a language model.
 *
 * <p>This class is the permanent core. All of the behaviour lives in a runtime jar that
 * {@link RuntimeHost} loads from disk, so that changing it does not require a server restart. The
 * listeners below are the only game-bus registrations the mod makes, and each one looks the current
 * runtime up at call time: a listener bound to a runtime object would keep the previous class
 * loader alive after a reload, and every reload would then leak a loader.
 */
@Mod(McAgentMod.MODID)
public final class McAgentMod {
    public static final String MODID = "mcagent";
    public static final Logger LOG = LoggerFactory.getLogger("mcagent");

    public McAgentMod(IEventBus modBus, ModContainer container) {
        AgentConfig.register(container);

        // Config lifecycle events are MOD-bus events, not NeoForge event-bus ones. Registering
        // them on NeoForge.EVENT_BUS would compile fine and then silently never fire, so a config
        // edit would appear to work while doing nothing.
        modBus.addListener(AgentConfig::onConfigEvent);

        NeoForge.EVENT_BUS.addListener(McAgentMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(McAgentMod::onServerTick);
        NeoForge.EVENT_BUS.addListener(McAgentMod::onServerStopping);
        NeoForge.EVENT_BUS.addListener(McAgentMod::onServerChat);
        NeoForge.EVENT_BUS.addListener(McAgentMod::onRegisterCommands);

        // Load now rather than on ServerStartedEvent: commands are registered before the server
        // starts, and waiting would cost the runtime its subcommands on the first startup.
        RuntimeHost.reload();

        LOG.info("MC Agent core initialised");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        RuntimeHost.onServerStarted(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        RuntimeHost.onServerTick(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        RuntimeHost.onServerStopping(event.getServer());
    }

    /**
     * Capture player chat so bots can hear it.
     *
     * <p>Recording is per-bot and distance-filtered in the runtime's {@code ChatLog}; this handler
     * only hands the event over. Bots' own speech also passes through here, which is intentional —
     * they should be able to hear each other.
     */
    private static void onServerChat(ServerChatEvent event) {
        RuntimeHost.onServerChat(event);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        AgentCommands.register(event);
        RuntimeHost.onRegisterCommands(event);
    }
}
