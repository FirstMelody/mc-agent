package com.melody.mcagent.rt.bot;

import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Connection} that goes nowhere: the network half of a synthetic player.
 *
 * <p>Nothing in {@link Connection} is {@code final}, so it can be subclassed, and we override
 * exactly the members that would otherwise assume a real remote peer.
 *
 * <p>The connection still needs a <em>real</em> Netty channel object, even though no bytes are ever
 * sent. NeoForge stores per-connection state (connection type, payload setup, registered channels)
 * as Netty channel attributes and reads them via {@code connection.channel().attr(...)} — for
 * example {@code ChannelAttributes.getPayloadSetup} calls it unconditionally, and
 * {@code NeoForgeEventHandler.onDpSync} calls that on every player during the join that
 * {@code PlayerList.placeNewPlayer} performs. With a null channel that path throws a
 * {@code NullPointerException} and the join fails. We therefore register a {@link LocalChannel}
 * on the same shared event loop group vanilla uses for in-memory connections, which gives every
 * attribute lookup a working home while never actually transporting anything.
 *
 * <p>All outbound traffic is dropped by the {@code send} overrides, so the channel is purely an
 * attribute holder.
 */
public class NullConnection extends Connection {

    private final LocalChannel channel;

    public NullConnection() {
        super(PacketFlow.SERVERBOUND);
        // A LocalChannel is what vanilla itself uses for in-memory connections
        // (Connection.connectToLocalServer). Registering it hands us a working event loop and
        // attribute map without opening a socket.
        this.channel = new LocalChannel();
        LOCAL_WORKER_GROUP.get().register(this.channel).syncUninterruptibly();

        // Tell NeoForge that this connection accepts every payload the server has registered.
        //
        // This is the crucial step for living alongside mods, and it replaces what a real client
        // negotiates during the configuration phase. Without it, any mod that pushes data to a
        // joining player throws from inside PlayerList.placeNewPlayer - *before* we can install our
        // own listener - because NeoForge's NetworkRegistry.checkPacket rejects custom payloads on a
        // connection that never negotiated the channel:
        //
        //   UnsupportedOperationException: Payload kubejs:sync_server_data may not be sent to the client!
        //   UnsupportedOperationException: Payload sable:udp_activation may not be sent to the client!
        //
        // Both were observed on the production server (259 mods); each syncs on player join via
        // OnDatapackSyncEvent or a join hook. Patching mods one at a time does not scale, so we use
        // NeoForge's own facility for synthetic connections, which installs a payload setup
        // containing every registered payload.
        //
        // We never decode anything NeoForge sends us (see the send overrides), so accepting all
        // channels costs nothing and cannot desynchronise a client that does not exist.
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(this);
    }

    /** Release the backing channel. Called when the bot leaves. */
    public void closeChannel() {
        if (this.channel.isOpen()) {
            this.channel.close().syncUninterruptibly();
        }
    }

    /**
     * Report a local address, so mods treat this connection as an in-memory player.
     *
     * <p>This is load-bearing, and the reason is subtle. Mods distinguish a real remote client from
     * a local/synthetic one by testing the remote address, and change behaviour accordingly. Sable's
     * UDP transport, for instance, returns early from its handshake when
     * {@code getRemoteAddress() instanceof LocalAddress}; otherwise it sends a custom payload, which
     * NeoForge rejects with "Payload sable:udp_activation may not be sent to the client!" because a
     * synthetic client never negotiated any mod channels. That exception is thrown from inside
     * {@code PlayerList.placeNewPlayer}, before we get a chance to install our own listener, so the
     * bot could not join the server at all.
     *
     * <p>We report the address directly rather than arranging it through a real Netty connect:
     * {@code LocalChannel.connect} requires a bound in-memory peer with a matching pipeline, and
     * hangs or fails without one. A field is honest here — the connection genuinely has no remote
     * peer, and saying "local" describes that precisely.
     */
    @Override
    public java.net.SocketAddress getRemoteAddress() {
        return new LocalAddress("mcagent-bot");
    }

    /**
     * Expose our channel.
     *
     * <p>This override is essential. {@code Connection.channel} is a private field that is only
     * assigned from Netty's {@code channelActive} callback, which never runs for a connection we
     * build and register by hand. Without this override {@code channel()} would keep returning
     * {@code null} and NeoForge's channel-attribute lookups would still NPE.
     */
    @Override
    public io.netty.channel.Channel channel() {
        return this.channel;
    }

    // --- outbound: there is no client, so every packet is dropped -------------------------------

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
    }

    // --- protocol setup: the vanilla implementations dereference the null channel ---------------

    /**
     * Vanilla ends by calling {@code this.channel.writeAndFlush(...)} (Connection.java:254) and
     * would NPE on our null channel. {@code PlayerList.placeNewPlayer} calls this, so it must be
     * overridden. We never receive packets, so skipping the registration is correct behaviour.
     */
    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void tick() {
    }

    // --- lifecycle: never "drop" the bot, its lifetime is owned by BotManager -------------------

    /**
     * Report a live connection. Outbound sends are overridden above, so this cannot cause an
     * accidental channel access, and it keeps {@code isAcceptingMessages()} true for vanilla
     * code paths that check it (e.g. ender pearl teleportation).
     */
    @Override
    public boolean isConnected() {
        return true;
    }

    /**
     * Report an in-memory connection.
     *
     * <p>True to our nature, and several mods and NeoForge internals use this to decide whether
     * data should be synced to us at all — for a synthetic client the answer is generally no.
     */
    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    /** Called by our listener's {@code disconnect}; the manager performs the real teardown. */
    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public void handleDisconnection() {
    }
}
