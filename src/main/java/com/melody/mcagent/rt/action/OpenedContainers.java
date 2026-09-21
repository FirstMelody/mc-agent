package com.melody.mcagent.rt.action;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;

/**
 * Per-bot memory of which containers it has opened.
 *
 * <p>This is the bookkeeping behind the realism rule that a container's contents are unknown until
 * the bot opens it: the perception layer asks here before revealing anything.
 *
 * <p>Deliberately a plain in-memory map keyed by player UUID rather than a data attachment. Bot
 * knowledge is session state — it should not be persisted into the world save, and a map keeps the
 * lifetime obvious and avoids any registry-timing constraints.
 */
public final class OpenedContainers {

    /** uuids of bots -> packed BlockPos values of containers they have opened. */
    private static final Map<UUID, Set<Long>> OPENED = new ConcurrentHashMap<>();

    private OpenedContainers() {
    }

    /** The set of opened-container positions for a player, created on first use. */
    public static Set<Long> of(Player player) {
        return OPENED.computeIfAbsent(player.getUUID(), id -> ConcurrentHashMap.newKeySet());
    }

    /** Forget everything a player knew, e.g. when a bot is removed. */
    public static void clear(UUID uuid) {
        OPENED.remove(uuid);
    }

    /** Drop all memories; used on server shutdown. */
    public static void clearAll() {
        OPENED.clear();
    }
}
