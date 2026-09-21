package com.melody.mcagent.rt.perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * What each bot has heard said around it.
 *
 * <p>A bot that can talk but not listen is not conversing, it is broadcasting: it will happily
 * describe the surroundings while ignoring every question put to it. This is the other half of the
 * speech loop.
 *
 * <p>Hearing is subject to the same realism rule as sight: a bot only hears messages from players
 * it could actually share a conversation with — same dimension, and within earshot. Chat is
 * therefore filtered per bot rather than handed to everyone.
 *
 * <p>Deliberately in-memory and bounded. A bot needs recent context to reply sensibly, not a
 * transcript of the session, and an unbounded buffer on a busy server would grow without limit.
 */
public final class ChatLog {

    /** How many recent messages to retain per bot. */
    private static final int MAX_MESSAGES = 20;

    /**
     * How far a message carries, in blocks.
     *
     * <p>Vanilla chat is global, but a bot that reacted to a conversation held on the far side of
     * the world would look psychic. 64 blocks is roughly "same base, different room", which is a
     * defensible notion of earshot without being pedantic about walls.
     */
    private static final double EARSHOT_RANGE = 64.0D;

    /** One heard message. */
    public record Heard(String speaker, String text, long gameTime, boolean directedAtBot) {

        /** A line as it should appear to a model. */
        public String format() {
            return "<" + this.speaker + "> " + this.text;
        }
    }

    /** uuid -> recent messages heard. */
    private static final Map<UUID, Deque<Heard>> LOGS = new ConcurrentHashMap<>();

    /**
     * uuids -> index of the last message a bot has already been shown.
     *
     * <p>Kept separately from the log so that reading does not consume, and each bot tracks its own
     * position independently.
     */
    private static final Map<UUID, Integer> READ_MARKERS = new ConcurrentHashMap<>();

    private ChatLog() {
    }

    /**
     * Record a message for every bot close enough to hear it.
     *
     * <p>Called from the chat event, so it must be cheap: the filter is a dimension check and a
     * squared-distance comparison.
     */
    public static void record(ServerPlayer speaker, String text, long gameTime, List<ServerPlayer> bots) {
        if (text == null || text.isBlank()) {
            return;
        }

        for (ServerPlayer bot : bots) {
            if (bot == speaker || bot.isRemoved()) {
                continue;
            }
            if (bot.level() != speaker.level()) {
                continue;
            }
            if (bot.distanceToSqr(speaker) > EARSHOT_RANGE * EARSHOT_RANGE) {
                continue;
            }

            // A message is "directed" when it names the bot or opens with its name, which is a
            // strong hint the bot is being addressed rather than overhearing background chatter.
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            String botName = bot.getName().getString().toLowerCase(java.util.Locale.ROOT);
            boolean directed = lower.contains(botName);

            Deque<Heard> log = LOGS.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
            synchronized (log) {
                log.addLast(new Heard(speaker.getName().getString(), text, gameTime, directed));
                while (log.size() > MAX_MESSAGES) {
                    log.removeFirst();
                }
            }
        }
    }

    /**
     * Messages this bot has not been shown yet, without consuming them.
     *
     * <p>Non-consuming on purpose: a message must stay visible across several observations, because
     * the bot decides only every so often and a turn may be spent acting rather than replying.
     */
    public static List<Heard> unheard(ServerPlayer bot) {
        Deque<Heard> log = LOGS.get(bot.getUUID());
        if (log == null) {
            return List.of();
        }
        synchronized (log) {
            List<Heard> all = new ArrayList<>(log);
            int from = Math.min(READ_MARKERS.getOrDefault(bot.getUUID(), 0), all.size());
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    /**
     * Mark everything currently heard as seen.
     *
     * <p>Called after an observation has been handed to the model, so the next observation only
     * highlights what is genuinely new and the transcript does not repeat itself every turn.
     */
    public static void markRead(ServerPlayer bot) {
        Deque<Heard> log = LOGS.get(bot.getUUID());
        if (log == null) {
            return;
        }
        synchronized (log) {
            READ_MARKERS.put(bot.getUUID(), log.size());
        }
    }

    /** The most recent messages, newest last, for context. */
    public static List<Heard> recent(ServerPlayer bot, int limit) {
        Deque<Heard> log = LOGS.get(bot.getUUID());
        if (log == null) {
            return List.of();
        }
        synchronized (log) {
            List<Heard> all = new ArrayList<>(log);
            int from = Math.max(0, all.size() - limit);
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    /** True if the bot has heard anything it has not yet been told about. */
    public static boolean hasUnheard(ServerPlayer bot) {
        return !unheard(bot).isEmpty();
    }

    /** Unheard messages that name this bot. */
    public static List<Heard> unheardDirected(ServerPlayer bot) {
        List<Heard> out = new ArrayList<>();
        for (Heard heard : unheard(bot)) {
            if (heard.directedAtBot()) {
                out.add(heard);
            }
        }
        return List.copyOf(out);
    }

    /**
     * A short, unmissable banner naming the freshest question put to this bot, or {@code null}.
     *
     * <p>Buried at the bottom of a long observation dump, a chat line is easy for a model to skim
     * past — observed in practice: a player asked "can you speak Chinese?" and the bot answered with
     * an unrelated remark about the weather. Addressing is therefore surfaced at the very top of the
     * observation as well, where it cannot be missed, rather than only in the chat section.
     *
     * <p>Returns {@code null} when nobody is addressing the bot, so ordinary background chatter does
     * not hijack the top of every observation.
     */
    @Nullable
    public static String directAddressBanner(ServerPlayer bot) {
        List<Heard> directed = unheardDirected(bot);
        if (directed.isEmpty()) {
            return null;
        }

        Heard latest = directed.get(directed.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("*** ").append(latest.speaker()).append(" IS TALKING TO YOU RIGHT NOW: \"")
                .append(latest.text()).append("\"\n");
        sb.append("    Decide whether this needs a response. You may stay silent; if a reply is useful,");
        sb.append(" use the say tool in the same language they used. Do not narrate your surroundings.");

        if (directed.size() > 1) {
            sb.append("\n    You also have not answered:");
            for (int i = 0; i < directed.size() - 1; i++) {
                Heard earlier = directed.get(i);
                sb.append("\n      <").append(earlier.speaker()).append("> ").append(earlier.text());
            }
        }

        sb.append("\n***");
        return sb.toString();
    }

    /** Forget everything a bot heard; used when it leaves. */
    public static void clear(UUID uuid) {
        LOGS.remove(uuid);
        READ_MARKERS.remove(uuid);
    }

    /** Forget everything; used on shutdown. */
    public static void clearAll() {
        LOGS.clear();
        READ_MARKERS.clear();
    }

    /**
     * Render what the bot has heard for a model prompt.
     *
     * <p>Unheard messages are marked so the model can tell what is new and respond to it, while the
     * older lines give it conversational context.
     */
    public static String describe(ServerPlayer bot) {
        List<Heard> recent = recent(bot, 8);
        if (recent.isEmpty()) {
            return "Nobody has said anything you can hear.";
        }

        List<Heard> newOnes = unheard(bot);
        StringBuilder sb = new StringBuilder();

        if (!newOnes.isEmpty()) {
            sb.append("NEW - said just now, you have not responded to this yet:\n");
            for (Heard heard : newOnes) {
                sb.append("  ").append(heard.format());
                if (heard.directedAtBot()) {
                    sb.append("   <- mentions you");
                }
                sb.append('\n');
            }
            sb.append("Recent conversation:\n");
        } else {
            sb.append("Recent conversation (nothing new):\n");
        }

        for (Heard heard : recent) {
            sb.append("  ").append(heard.format()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether fresh chat should trigger an immediate model decision.
     *
     * <p>Every audible message is evaluated immediately, even if it does not name the bot. The
     * model may stay silent; this controls decision latency, not whether a reply is produced.
     */
    public static boolean shouldRespondPromptly(ServerPlayer bot) {
        return hasUnheard(bot);
    }

    @Nullable
    public static String lastSpeaker(ServerPlayer bot) {
        List<Heard> recent = recent(bot, 1);
        return recent.isEmpty() ? null : recent.get(0).speaker();
    }
}
