package com.melody.mcagent.rt.bot;

import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import org.jetbrains.annotations.Nullable;

/**
 * The packet listener for a synthetic player. A real client never sends us anything, so this class
 * exists for two reasons:
 *
 * <ol>
 *   <li><b>It runs the bot's physics.</b> Vanilla only integrates player movement from
 *       {@code ServerGamePacketListenerImpl.tick()} — {@code ServerPlayer.tick()} is bookkeeping
 *       only, and {@code ServerPlayer.doTick()} (the method containing the real
 *       {@code travel()} path) has exactly one caller in the whole game: the listener's
 *       {@code tick()}. A bot with no listener therefore has no physics at all.</li>
 *   <li><b>It removes the client-authority rewind.</b> The vanilla {@code tick()} ends with
 *       {@code player.absMoveTo(firstGoodX, firstGoodY, firstGoodZ, ...)}, because in vanilla the
 *       client owns its own position and reports it via {@code ServerboundMovePlayerPacket}. A
 *       synthetic player never sends that packet, so the rewind would discard every tick of
 *       movement we generate and the bot would stand still forever. We reproduce the vanilla tick
 *       without that final {@code absMoveTo}.</li>
 * </ol>
 *
 * <p>The second point is the single most important detail in this mod. It is verified against
 * {@code ServerGamePacketListenerImpl.java:256-261}, and {@code firstGood*} is written nowhere else
 * in the game, so omitting the rewind is both necessary and sufficient.
 */
public class BotGamePacketListener extends ServerGamePacketListenerImpl {

    private final ServerPlayer bot;

    /** Optional hook invoked each tick, after physics, to drive inputs for the next tick. */
    @Nullable
    private Runnable afterPhysicsHook;

    /** Last section position we refreshed chunk tracking for; see tick(). */
    private long lastMoveSection = Long.MIN_VALUE;

    public BotGamePacketListener(MinecraftServer server, Connection connection, ServerPlayer bot, CommonListenerCookie cookie) {
        super(server, connection, bot, cookie);
        this.bot = bot;
    }

    public void setAfterPhysicsHook(@Nullable Runnable hook) {
        this.afterPhysicsHook = hook;
    }

    /**
     * Vanilla's tick, minus the {@code absMoveTo(firstGood*)} rewind.
     *
     * <p>Kept deliberately close to the original so that vanilla side effects (flying revocation,
     * idle timeout, keep-alive bookkeeping) still apply. Deliberately omitted:
     * <ul>
     *   <li>the rewind itself (see class docs) — this is the whole point;</li>
     *   <li>{@code keepConnectionAlive()} — it would send a keep-alive we never answer and kick
     *       the bot after 15s ({@code ServerCommonPacketListenerImpl.java:137-145});</li>
     *   <li>the "floating too long" kick — {@code clientIsFloating} is only ever set by
     *       {@code handleMovePlayer}, which a synthetic player never reaches.</li>
     * </ul>
     */
    @Override
    public void tick() {
        // The packet-flow counters that vanilla's tick() maintains (tickCount,
        // knownMovePacketCount, ackBlockChangesUpTo) are private and exist only to police a real
        // client's packet stream. A synthetic player sends none, so they are irrelevant here.

        this.bot.xo = this.bot.getX();
        this.bot.yo = this.bot.getY();
        this.bot.zo = this.bot.getZ();

        // Run the physics: Player.tick() -> LivingEntity.tick() -> aiStep() -> travel().
        this.bot.doTick();

        // NOTE: no `absMoveTo(firstGoodX, firstGoodY, firstGoodZ, ...)` here — that is the fix.

        // Refresh chunk/entity tracking, which vanilla does from handleMovePlayer.
        //
        // Guarded on an actual block change because ChunkMap.move() walks EVERY tracked entity in
        // the level and updates its visibility pairings for this player. Vanilla only pays that
        // when a movement packet arrives, i.e. when the player really moved; calling it
        // unconditionally would repeat that full sweep every tick for every bot, for no effect.
        // SectionPos is the granularity the chunk source itself compares on, so testing it here
        // is both correct and cheap.
        long section = net.minecraft.core.SectionPos.asLong(this.player.blockPosition());
        if (section != this.lastMoveSection) {
            this.lastMoveSection = section;
            this.player.serverLevel().getChunkSource().move(this.player);
        }

        if (this.afterPhysicsHook != null) {
            this.afterPhysicsHook.run();
        }
    }

    // --- teleport overrides ---------------------------------------------------------------------
    //
    // The vanilla teleport() sets `awaitingPositionFromClient`, which is cleared ONLY by
    // ServerboundAcceptTeleportationPacket (ServerGamePacketListenerImpl.java:515). A synthetic
    // player never sends that packet, so the latch would stay set forever, and while it is set
    // `handleUseItemOn` refuses EVERY block interaction (line 1120) — which would silently break
    // chest opening and block placing. PlayerList.placeNewPlayer calls teleport() on the normal
    // join path (PlayerList.java:220), so this would definitely be hit.
    //
    // We therefore perform the move directly and never arm the latch.

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        this.bot.absMoveTo(x, y, z, yaw, pitch);
        this.resetPosition();

        // Refresh chunk/entity tracking, but only once the player is actually registered with the
        // level. This is not a defensive nicety - it is required.
        //
        // PlayerList.respawn teleports the new body at line 482 and only registers it at line 490
        // (addRespawnedPlayer). ChunkMap.move() on a player the distance manager has never seen
        // dereferences a null entry and throws
        //   NullPointerException: ... DistanceManager.removePlayer(DistanceManager.java:240)
        // which aborts respawn entirely: the bot is left dead and is then dropped. Vanilla's own
        // teleport() only sends a packet, so it never hits this; we are the ones who added the move.
        //
        // Deferring is safe because the tick() below already calls move() whenever the player's
        // section changes, and a respawned bot is issued a fresh listener whose lastMoveSection
        // starts at MIN_VALUE - so the very next tick performs the refresh anyway.
        if (this.isRegisteredWithLevel()) {
            this.bot.serverLevel().getChunkSource().move(this.bot);
        }
    }

    /** Whether the level has this player in its player list yet. */
    private boolean isRegisteredWithLevel() {
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        return level.players().contains(this.bot);
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeMovements) {
        // Callers pass already-absolute coordinates; the set only shapes the outbound packet in
        // vanilla, so it must NOT be re-applied here.
        this.teleport(x, y, z, yaw, pitch);
    }

    /** Do not send a disconnect packet to a client that does not exist. */
    @Override
    public void disconnect(Component message) {
    }

    @Override
    public void onDisconnect(net.minecraft.network.DisconnectionDetails details) {
        // Intentionally does nothing: BotManager owns the bot's teardown.
    }

    /** Never kick the bot for idling — an LLM-driven bot may legitimately think for a while. */
    @Override
    public void resetPosition() {
        super.resetPosition();
    }
}
