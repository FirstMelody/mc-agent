package com.melody.mcagent.rt.bot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the lifecycle of every synthetic player: joining, ticking, and leaving.
 *
 * <p>Bots join through the ordinary {@link PlayerList#placeNewPlayer} path, so they are first-class
 * players — they appear in the tab list, other players see them join, they receive chat and can be
 * targeted by commands. The only thing we change afterwards is swapping in
 * {@link BotGamePacketListener}, because {@code placeNewPlayer} installs the vanilla listener that
 * would rewind the bot's position every tick (see that class for the full explanation).
 */
public final class BotManager {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/bots");

    /**
     * How long a dead bot lies there before it is revived.
     *
     * <p>Vanilla removes a dead entity at {@code deathTime} 20 ({@code LivingEntity.tickDeath}).
     * Respawning a little before that keeps the death animation and the chat death message visible
     * while still calling {@code PlayerList.respawn} on a player object that is still valid — after
     * removal there is no body left to hand to it.
     */
    private static final int DEATH_TICKS_BEFORE_RESPAWN = 10;

    private final MinecraftServer server;
    private final Map<UUID, BotHandle> bots = new LinkedHashMap<>();

    public BotManager(MinecraftServer server) {
        this.server = server;
    }

    /** A joined bot together with the state needed to drive and remove it. */
    public static final class BotHandle {
        /**
         * The live player entity.
         *
         * <p>Not final: dying replaces the entity. Vanilla {@code PlayerList.respawn} constructs a
         * brand new {@code ServerPlayer} rather than reviving the old one, so the handle has to be
         * updated to the new body or every later operation would address a discarded corpse.
         */
        private ServerPlayer player;
        private final MovementDriver movement;
        private final boolean persistPlayerData;
        private final String name;
        /** The dead socket this bot joined through; reused verbatim across a respawn. */
        private final NullConnection connection;
        /** True while the bot has died and its respawn is pending. */
        private boolean awaitingRespawn;

        BotHandle(ServerPlayer player, NullConnection connection, MovementDriver movement,
                  boolean persistPlayerData, String name) {
            this.player = player;
            this.connection = connection;
            this.movement = movement;
            this.persistPlayerData = persistPlayerData;
            this.name = name;
        }

        public ServerPlayer player() {
            return this.player;
        }

        public MovementDriver movement() {
            return this.movement;
        }

        public String name() {
            return this.name;
        }

        public boolean persistPlayerData() {
            return this.persistPlayerData;
        }
    }

    public Collection<BotHandle> handles() {
        return List.copyOf(this.bots.values());
    }

    public List<String> names() {
        return this.bots.values().stream().map(BotHandle::name).toList();
    }

    @Nullable
    public BotHandle get(String name) {
        for (BotHandle handle : this.bots.values()) {
            if (handle.name().equalsIgnoreCase(name)) {
                return handle;
            }
        }
        return null;
    }

    public boolean isBot(ServerPlayer player) {
        return this.bots.containsKey(player.getUUID());
    }

    /**
     * Create and join a new synthetic player.
     *
     * <p>A name that has been used before rejoins with the state it was saved with: inventory, XP,
     * effects and respawn point all come back. That state is the bot's memory of the world and is
     * only ever destroyed by {@link #wipeSavedData}, which a caller has to ask for.
     *
     * @param name        the player name; must not collide with an online player
     * @param level       the level to spawn into
     * @param position    where to place the bot
     * @param persistData whether the bot's playerdata should survive its removal
     * @return the handle, or {@code null} if the name was taken or the join failed
     */
    @Nullable
    public BotHandle spawn(String name, ServerLevel level, Vec3 position, boolean persistData) {
        return this.spawn(name, level, position, 0.0F, 0.0F, persistData);
    }

    public BotHandle spawn(String name, ServerLevel level, Vec3 position, float yaw, float pitch,
                           boolean persistData) {
        PlayerList playerList = this.server.getPlayerList();

        if (this.get(name) != null || playerList.getPlayerByName(name) != null) {
            LOG.warn("Refusing to spawn bot '{}': a player with that name is already present", name);
            return null;
        }

        // Offline-mode profile: deterministic UUID derived from the name, exactly as vanilla does
        // for a cracked/offline player. This keeps a bot's identity stable across restarts.
        GameProfile profile = UUIDUtil.createOfflineProfile(name);

        if (playerList.getPlayer(profile.getId()) != null) {
            LOG.warn("Refusing to spawn bot '{}': UUID {} is already online", name, profile.getId());
            return null;
        }

        try {
            BotPlayer bot = new BotPlayer(this.server, level, profile, name);
            bot.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);

            NullConnection connection = new NullConnection();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

            // Join through the normal path. This installs a vanilla ServerGamePacketListenerImpl,
            // which we replace immediately below. The join is also what loads this bot's saved
            // playerdata, so it must happen before the repair below.
            playerList.placeNewPlayer(connection, bot, cookie);

            // Replace the vanilla listener. Our subclass runs the bot's physics without the
            // client-authority position rewind, and never arms the teleport latch.
            BotGamePacketListener listener = new BotGamePacketListener(this.server, connection, bot, cookie);
            bot.connection = listener;

            // The join may have loaded a death that was saved to disk. Repair it here - on the
            // server thread, before the bot is ticked even once, because the first tick is what
            // removes a corpse.
            repairLoadedDeathState(bot, name);

            MovementDriver movement = new MovementDriver(bot);
            listener.setAfterPhysicsHook(movement::tick);

            // Put the bot where the caller asked (placeNewPlayer respawns it at world spawn).
            listener.teleport(position.x, position.y, position.z, yaw, pitch);

            BotHandle handle = new BotHandle(bot, connection, movement, persistData, name);
            this.bots.put(bot.getUUID(), handle);
            LOG.info("Bot '{}' joined at {} in {}", name, position, level.dimension().location());
            return handle;
        } catch (Throwable t) {
            LOG.error("Failed to spawn bot '{}'", name, t);
            return null;
        }
    }

    /**
     * Undo a death that was saved to disk, instead of deleting the file that holds it.
     *
     * <p>{@code PlayerList.placeNewPlayer} loads playerdata verbatim - {@code Health} and
     * {@code DeathTime} included ({@code LivingEntity.readAdditionalSaveData}). A bot killed by a mob
     * and then removed, or killed moments before the server stopped, is therefore saved dead; the
     * next join replays exactly that. {@code LivingEntity.tickDeath} removes the entity as soon as
     * {@code deathTime} reaches 20, and a synthetic player has no client to click respawn, so the bot
     * simply vanishes on its first or second tick. That is the "the bot came back dead" report.
     *
     * <p>The tempting fix is to delete the playerdata before joining, and that is what this code used
     * to do. Do not put it back: that file is the bot's inventory, XP, effects and respawn point, so
     * deleting it made every restart and every re-spawn wipe everything the bot owned. Repair the
     * state instead, and leave the rest of the file alone.
     */
    private void repairLoadedDeathState(ServerPlayer bot, String name) {
        boolean removed = bot.isRemoved();
        if (!removed && !bot.isDeadOrDying() && bot.deathTime <= 0) {
            return;
        }

        float healthFound = bot.getHealth();
        int deathTimeFound = bot.deathTime;
        int foodFound = bot.getFoodData().getFoodLevel();

        bot.setHealth(bot.getMaxHealth());
        bot.deathTime = 0;
        bot.hurtTime = 0;
        bot.clearFire();
        if (foodFound <= 0) {
            // A bot that starved to death is saved with an empty food bar, and starvation would only
            // start killing it again. The food has to come back with the health.
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(5.0F);
        }

        LOG.info("Bot '{}' was loaded from saved playerdata in a dead state (health={}, deathTime={}"
                + "{}) - repaired to health={}, deathTime=0. Its inventory and respawn point are kept.",
                name, healthFound, deathTimeFound,
                foodFound <= 0 ? ", food level " + foodFound : "",
                bot.getHealth());

        if (removed) {
            // Nothing here can put a removed entity back into the level, so the revive path in
            // tick() has to rebuild the body. Repairing the state first means the new body starts
            // alive rather than inheriting the death.
            LOG.warn("Bot '{}' was loaded already removed from the level; the revive path will "
                    + "rebuild its body", name);
        }
    }

    /** Remove a bot from the world. Its saved state is kept; see {@link #wipeSavedData}. */
    public boolean remove(String name) {
        BotHandle handle = this.get(name);
        if (handle == null) {
            return false;
        }
        this.remove(handle);
        return true;
    }

    public void remove(BotHandle handle) {
        ServerPlayer bot = handle.player();
        this.bots.remove(bot.getUUID());

        // Release per-bot memory. Without this, every bot ever spawned would keep its opened-container
        // set alive for the lifetime of the JVM - a slow leak on a server that spawns bots regularly.
        com.melody.mcagent.rt.action.OpenedContainers.clear(bot.getUUID());

        try {
            // This saves the bot's state on the way out, which is what makes its inventory and
            // respawn point survive a restart or a hot reload. Only a caller that explicitly asked
            // for a throwaway bot (persistPlayerData == false) has that save deleted afterwards.
            this.server.getPlayerList().remove(bot);
        } catch (Throwable t) {
            LOG.warn("Error while removing bot '{}'", handle.name(), t);
        }

        if (!handle.persistPlayerData()) {
            // PlayerList.remove already flushed the bot's state to disk, so the files exist by now
            // and can simply be deleted.
            deletePersistedData(bot.getUUID());
        }

        LOG.info("Bot '{}' left", handle.name());
    }

    /**
     * Destroy everything a bot's name owns on disk: playerdata (inventory, XP, health, effects,
     * respawn point), stats and advancements.
     *
     * <p>Deliberately explicit and deliberately rare. {@link #spawn} restores a saved bot rather than
     * wiping it, so this is the only path that destroys a bot's belongings, and it exists so that
     * destroying them is a decision someone makes - {@code /mcagent spawn <name> fresh} - rather than
     * a side effect of restarting the server or spawning a bot again.
     *
     * @return false if the bot is online, in which case nothing was deleted: its state would be
     *         written straight back out when it leaves
     */
    public boolean wipeSavedData(String name) {
        if (this.get(name) != null) {
            LOG.warn("Refusing to wipe the saved data of bot '{}': it is online, and its state would "
                    + "be saved again the moment it is removed", name);
            return false;
        }
        UUID uuid = UUIDUtil.createOfflineProfile(name).getId();
        LOG.info("Wiping all saved data of bot '{}' ({}) at the caller's request", name, uuid);
        deletePersistedData(uuid);
        return true;
    }

    /**
     * Delete a bot's on-disk state, file by file.
     *
     * <p>The extensions matter and are not uniform: playerdata and stats are NBT ({@code .dat}),
     * only advancements are JSON. An earlier version deleted {@code <uuid>.json} from all three
     * directories, which silently matched nothing in the two that count — so a bot's playerdata
     * survived its own removal, and the next spawn of the same name inherited it.
     *
     * <p>Only ever called by {@link #wipeSavedData} and by the removal of a bot whose caller asked
     * for it to be throwaway. Never call it from {@code spawn}: see
     * {@link #repairLoadedDeathState} for what that cost.
     */
    private void deletePersistedData(UUID uuid) {
        String[] suffixes = { ".dat", ".dat_old", ".json" };
        for (String dir : new String[] { "playerdata", "stats", "advancements" }) {
            for (String suffix : suffixes) {
                try {
                    java.nio.file.Path path = this.server.getWorldPath(
                            net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve(dir).resolve(uuid + suffix);
                    if (java.nio.file.Files.deleteIfExists(path)) {
                        LOG.info("Deleted {}/{} for bot {}", dir, uuid + suffix, uuid);
                    }
                } catch (Throwable t) {
                    LOG.warn("Could not delete {} file for bot {}", dir, uuid, t);
                }
            }
        }
    }

    /**
     * Where a bot last logged out: its saved position, rotation and dimension.
     *
     * <p>Vanilla restores a returning player's <em>dimension</em> from playerdata but not their
     * coordinates - the position normally arrives from the client - so a bot spawned without
     * coordinates used to land wherever the operator happened to be standing, which is how a bot
     * that had been mining 200 blocks away came back at the base. Resuming at the logout spot is
     * what makes {@code /mcagent spawn <name>} a "come back" rather than a "start over here".
     */
    public record SavedLogout(ServerLevel level, Vec3 position, float yaw, float pitch) {
    }

    /**
     * The position this bot logged out at, read from its own playerdata, or {@code null} when it has
     * no save file (a brand new bot) or the file cannot be trusted.
     */
    @Nullable
    public SavedLogout savedLogout(String name) {
        java.nio.file.Path file = playerDataFile(name);
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        try {
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(
                    file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            net.minecraft.nbt.ListTag pos = tag.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
            if (pos.size() < 3) {
                return null;
            }
            double x = pos.getDouble(0);
            double y = pos.getDouble(1);
            double z = pos.getDouble(2);
            float yaw = 0.0F;
            float pitch = 0.0F;
            net.minecraft.nbt.ListTag rotation =
                    tag.getList("Rotation", net.minecraft.nbt.Tag.TAG_FLOAT);
            if (rotation.size() >= 2) {
                yaw = rotation.getFloat(0);
                pitch = rotation.getFloat(1);
            }
            ServerLevel level = this.server.overworld();
            String dimension = tag.getString("Dimension");
            if (!dimension.isBlank()) {
                ServerLevel saved = this.server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(dimension)));
                if (saved != null) {
                    level = saved;
                } else {
                    LOG.warn("Bot '{}' logged out in unknown dimension {}; using the overworld",
                            name, dimension);
                }
            }
            if (level.isOutsideBuildHeight(net.minecraft.util.Mth.floor(y))) {
                LOG.warn("Saved position of bot '{}' is outside the world ({}); ignoring it", name, y);
                return null;
            }
            return new SavedLogout(level, new Vec3(x, y, z), yaw, pitch);
        } catch (Throwable t) {
            LOG.warn("Could not read the saved position of bot '{}': {}", name, t.toString());
            return null;
        }
    }

    /** Whether this bot name has saved state on disk, i.e. a spawn would resume it rather than
     * start a new bot. */
    public boolean hasSavedData(String name) {
        java.nio.file.Path file = playerDataFile(name);
        return file != null && java.nio.file.Files.isRegularFile(file);
    }

    /** The playerdata file a bot's name owns, or {@code null} when the world path is unavailable. */
    @Nullable
    private java.nio.file.Path playerDataFile(String name) {
        try {
            UUID uuid = UUIDUtil.createOfflineProfile(name).getId();
            return this.server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("playerdata").resolve(uuid + ".dat");
        } catch (Throwable t) {
            LOG.warn("Could not resolve the playerdata path for bot '{}': {}", name, t.toString());
            return null;
        }
    }

    public void removeAll() {
        for (BotHandle handle : new ArrayList<>(this.bots.values())) {
            this.remove(handle);
        }
    }

    /**
     * Drive every bot's physics and per-tick behaviour.
     *
     * <p>This is the analogue of vanilla's {@code ServerConnectionListener.tick()}, which is what
     * normally calls {@code Connection.tick()} → {@code player.doTick()} for real players. Our
     * synthetic connections are deliberately not registered with that listener, so we drive them
     * here instead.
     */
    public void tick() {
        for (BotHandle handle : List.copyOf(this.bots.values())) {
            ServerPlayer bot = handle.player();
            try {
                // A dying bot still has to be ticked. deathTime only advances from LivingEntity's own
                // tick, and deathTime is what drives both the death animation and the entity's
                // removal at 20 ticks - so skipping the tick for a corpse freezes it dead forever,
                // which is precisely the bug this whole branch exists to fix.
                if (!bot.isRemoved()) {
                    bot.connection.tick();
                }

                // A dead player is never revived by vanilla. Death is a client-driven transition:
                // the client shows the death screen and sends ServerboundClientCommandPacket when
                // the player clicks respawn, and PlayerList.respawn is what installs the new body.
                // A synthetic player has no client, so nothing ever asks.
                //
                // Respawn happens at deathTime 10 rather than 20: the animation and the death
                // message still play, but PlayerList.respawn gets a body that has not yet been
                // removed from the level.
                if (bot.isRemoved()
                        || (bot.isDeadOrDying() && bot.deathTime >= DEATH_TICKS_BEFORE_RESPAWN)) {
                    LOG.info("Bot '{}' needs reviving: removed={} dead={} deathTime={} health={}",
                            handle.name(), bot.isRemoved(), bot.isDeadOrDying(), bot.deathTime,
                            bot.getHealth());
                    this.respawn(handle);
                }
            } catch (Throwable t) {
                LOG.error("Error ticking bot '{}'", handle.name(), t);
            }
        }
    }

    /**
     * Put a dead bot back in the world.
     *
     * <p>Goes through {@code PlayerList.respawn} rather than removing and re-adding the player,
     * because that is the path every mod expects: it fires {@code PlayerRespawnPositionEvent} and
     * {@code PlayerRespawnEvent}, restores stats, advancements and the recipe book, and drops the
     * old entity from the level exactly once. Rejoining instead would replay the whole
     * {@code placeNewPlayer} sequence, which on a heavily modded server stalls the tick loop for
     * seconds.
     *
     * <p>Two things have to be repaired by hand afterwards, because vanilla only does them for a
     * client-driven respawn:
     * <ul>
     *   <li>vanilla copies the <em>old</em> packet listener onto the new player, and that listener
     *       holds a reference to the dead body — so the new player would receive no physics at
     *       all. A fresh {@link BotGamePacketListener} bound to the new entity replaces it.</li>
     *   <li>the handle and the brain both hold the player object, so both are re-pointed.</li>
     * </ul>
     */
    private void respawn(BotHandle handle) {
        if (handle.awaitingRespawn) {
            return;
        }
        handle.awaitingRespawn = true;

        ServerPlayer old = handle.player();
        try {
            ServerPlayer fresh = this.server.getPlayerList().respawn(
                    old, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);

            // `respawn` hands the new player the old listener (PlayerList.java:459), which is bound
            // to the discarded entity. Swap in one bound to the living body.
            BotGamePacketListener listener = new BotGamePacketListener(
                    this.server, handle.connection, fresh, CommonListenerCookie.createInitial(
                            fresh.getGameProfile(), false));
            fresh.connection = listener;
            listener.setAfterPhysicsHook(handle.movement()::tick);

            // MovementDriver drives its player through an accessor, so re-point it explicitly:
            // otherwise it would steer the corpse and the bot would stand still after respawning.
            handle.movement().rebind(fresh);

            handle.player = fresh;
            handle.awaitingRespawn = false;

            com.melody.mcagent.rt.brain.BrainManager brains = com.melody.mcagent.rt.Agent.brainManager();
            if (brains != null) {
                brains.rebind(fresh);
            }

            LOG.info("Bot '{}' respawned at {}", handle.name(), fresh.blockPosition().toShortString());
        } catch (Throwable t) {
            handle.awaitingRespawn = false;
            LOG.error("Could not respawn bot '{}'; removing it to avoid a stuck corpse", handle.name(), t);
            try {
                this.server.getPlayerList().remove(old);
            } catch (Throwable ignored) {
                // Nothing further we can do; the bot is already gone from the world's point of view.
            }
            this.bots.remove(old.getUUID());
        }
    }
}
