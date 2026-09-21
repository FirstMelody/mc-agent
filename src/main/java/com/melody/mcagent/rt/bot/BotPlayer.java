package com.melody.mcagent.rt.bot;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * A synthetic, fully server-side player.
 *
 * <p>This is a real {@link ServerPlayer}: it is a genuine entity in the level, appears to other
 * players as an ordinary player, receives chat, is targeted by {@code /msg}, triggers mod event
 * hooks, and is moved by the vanilla physics in {@code LivingEntity.travel()}. What it lacks is a
 * network client — {@link NullConnection} supplies the dead socket and
 * {@link BotGamePacketListener} supplies the tick that vanilla normally gets from the packet flow.
 *
 * <p>This deliberately does <b>not</b> extend NeoForge's {@code FakePlayer}, which no-ops both
 * {@code tick()} and {@code resetPosition()} and is designed for inventory automation rather than
 * locomotion. We want the opposite: a bot that is as close to "a player" as the server can express.
 */
public class BotPlayer extends ServerPlayer {

    /** The name this bot presents to the server and to other players. */
    private final String botName;

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, String botName) {
        // ClientInformation.createDefault() gives us a vanilla-equivalent view distance, skin
        // parts, and main-hand preference, so the bot is indistinguishable from a normal client
        // for every code path that inspects player options.
        super(server, level, profile, ClientInformation.createDefault());
        this.botName = botName;
    }

    public String botName() {
        return this.botName;
    }

    /**
     * Bots are not {@code FakePlayer}s: to every other mod they are an ordinary player. This
     * override exists only so our own code can tell them apart at a glance.
     */
    public boolean isBot() {
        return true;
    }

    /** Convenience: is the given player one of ours? */
    public static boolean isBot(Player player) {
        return player instanceof BotPlayer;
    }
}
