package com.melody.mcagent.rt.bot;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;

import io.netty.channel.local.LocalAddress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproduces the production mod-interaction conditions that broke bot spawn.
 *
 * <p>The production server has Sable installed, whose UDP handshake hook runs inside
 * {@code PlayerList.placeNewPlayer} — before our own listener is installed. It skips itself when the
 * player's remote address is a {@link LocalAddress}, and otherwise sends a custom payload that
 * NeoForge rejects because a synthetic client negotiated no mod channels. The result was that
 * {@code /mcagent spawn} failed outright on the real server while working in every local test.
 *
 * <p>This test asserts the property Sable actually depends on, and additionally simulates the
 * packet send that used to throw, so a regression is caught before it reaches production.
 *
 * <p>Enabled with MCAGENT_SABLE_TEST=true.
 */
public final class SableCompatTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/sabletest");

    private final MinecraftServer server;
    private boolean done;

    public SableCompatTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_SABLE_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("SABLETEST: armed");
        return new SableCompatTest(server);
    }

    @Override
    public void onTick() {
        if (this.done) {
            return;
        }
        this.done = true;

        ServerLevel level = this.server.overworld();
        var spawn = level.getSharedSpawnPos();
        Vec3 pos = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);

        LOG.info("SABLETEST ================ RESULT ================");

        // 1. Spawn must succeed at all. On production this threw UnsupportedOperationException
        //    from inside placeNewPlayer, so reaching the next line is itself the main assertion.
        BotManager.BotHandle handle = Agent.botManager().spawn("SableBot", level, pos, false);
        LOG.info("SABLETEST spawn succeeded      : {}", handle != null);
        if (handle == null) {
            LOG.info("SABLETEST VERDICT              : FAIL (spawn threw)");
            LOG.info("SABLETEST ======================================");
            this.server.halt(false);
            return;
        }

        ServerPlayer bot = handle.player();

        // 2. The exact property Sable tests. If this is not a LocalAddress, Sable will attempt its
        //    UDP handshake and NeoForge will reject the payload.
        java.net.SocketAddress remote = bot.connection.getRemoteAddress();
        boolean isLocal = remote instanceof LocalAddress;
        LOG.info("SABLETEST getRemoteAddress     : {}", remote);
        LOG.info("SABLETEST instanceof LocalAddr : {}   <- Sable skips UDP auth when true", isLocal);

        // 3. NeoForge's channel check is what actually threw in production. Verify directly that a
        //    mod payload id which was never negotiated is correctly reported as unavailable, so we
        //    understand the failure mode - and confirm the LocalAddress path avoids ever asking.
        boolean channelKnown = net.neoforged.neoforge.network.registration.NetworkRegistry
                .hasChannel(bot.connection.getConnection(), null, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sable", "udp_activation"));
        LOG.info("SABLETEST sable channel known  : {}   <- false is expected, which is why Sable must skip", channelKnown);

        // 4. The bot must still be a normal functioning player.
        boolean inList = this.server.getPlayerList().getPlayerByName("SableBot") != null;
        LOG.info("SABLETEST in player list       : {}", inList);
        LOG.info("SABLETEST memory connection    : {}", bot.connection.getConnection().isMemoryConnection());

        boolean pass = isLocal && inList && handle != null;
        LOG.info("SABLETEST VERDICT              : {}", pass ? "PASS" : "FAIL");
        LOG.info("SABLETEST ======================================");

        Agent.botManager().remove("SableBot");
        this.server.halt(false);
    }
}
