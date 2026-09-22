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

    /** uuid -> what this bot itself has said, newest last. */
    private static final Map<UUID, Deque<Said>> SAID = new ConcurrentHashMap<>();

    /** How many of its own lines a bot is asked to remember. */
    private static final int MAX_SAID = 6;

    /** One line the bot said itself. */
    public record Said(String text, long gameTime) {
    }

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
    public static void record(ServerPlayer speaker, String text, long gameTime,
                              List<ServerPlayer> bots, List<String> onlineNames) {
        if (text == null || text.isBlank()) {
            return;
        }

        // Who is close enough to be part of this conversation? Deciding "is the bot being addressed"
        // needs that count, not just this bot's own filter.
        List<ServerPlayer> hearers = new ArrayList<>();
        // How many bots this message could possibly be for: every bot but the speaker. This is the
        // number that decides whether "there is nobody else it could be talking to" applies, and it
        // has to be counted separately from earshot - the harness's "player" is itself a bot.
        int candidates = 0;
        for (ServerPlayer bot : bots) {
            if (bot == speaker || bot.isRemoved() || bot.level() != speaker.level()) {
                continue;
            }
            candidates++;
            if (bot.distanceToSqr(speaker) <= EARSHOT_RANGE * EARSHOT_RANGE) {
                hearers.add(bot);
            }
        }

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // Recorded for every bot in the dimension, whether or not it is within earshot. Range is a
        // reasonable way to decide who is being *addressed*; it is not a reason to lose a message
        // entirely. Production: the bot asked a player for food, walked 24 blocks while doing chores,
        // the player answered "转中文", and the answer was never recorded - the bot looked like it was
        // ignoring them, and nothing in the log showed that a message had even arrived.
        for (ServerPlayer bot : bots) {
            if (bot == speaker || bot.isRemoved() || bot.level() != speaker.level()) {
                continue;
            }
            String botName = bot.getName().getString().toLowerCase(java.util.Locale.ROOT);
            // Named, or the only one who could have heard it. A player talking to the single bot on
            // the server does not repeat its name every sentence: "继续挖钻石 多挖点钻石" is plainly
            // addressed to it. Without this the bot treated that as background chatter and its reply
            // was then refused as unprompted narration - the bot looked dead while it was working.
            // With several bots listening, naming is still required, which is what keeps a bot from
            // answering a conversation held with another one.
            // Naming somebody else means the message is for them, not for this bot, however few
            // bots are listening: "渊夜你那个浮空艇放哪了" is a question between two players, and a
            // single bot in earshot must not treat it as addressed to itself.
            boolean namesSomeoneElse = false;
            for (String other : onlineNames) {
                String otherLower = other.toLowerCase(java.util.Locale.ROOT);
                if (!otherLower.equals(botName) && lower.contains(otherLower)) {
                    namesSomeoneElse = true;
                    break;
                }
            }
            // The only bot on the server is addressed whether or not it happens to be standing next
            // to the speaker: there is nobody else the message could be for, and a player who talks
            // to the bot expects an answer, not a 64-block rule.
            boolean soleBot = candidates == 1 && soleBotHearsEverywhere();
            boolean withinEarshot =
                    bot.distanceToSqr(speaker) <= EARSHOT_RANGE * EARSHOT_RANGE;
            boolean directed = lower.contains(botName)
                    || (!namesSomeoneElse && (soleBot || (withinEarshot && hearers.size() == 1)));

            Deque<Heard> log = LOGS.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
            synchronized (log) {
                log.addLast(new Heard(speaker.getName().getString(), text, gameTime, directed));
                while (log.size() > MAX_MESSAGES) {
                    log.removeFirst();
                }
            }
        }
    }

    /** As {@link #record(ServerPlayer, String, long, List, List)} without a roster of other names. */
    public static void record(ServerPlayer speaker, String text, long gameTime, List<ServerPlayer> bots) {
        record(speaker, text, gameTime, bots, List.of());
    }

    /**
     * Whether the only bot on the server counts as addressed from any distance.
     *
     * <p>Gated so {@code MCAGENT_CHAT_INVOKE_TEST}'s positive control can put the old 64-block rule
     * back and show that the test detects the difference, the same way the structure guard and the
     * mining skill are gated. Production needed the rule: a player answered the bot's own question
     * from just outside earshot and the bot looked like it was ignoring them.
     */
    public static boolean soleBotHearsEverywhere() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_CHAT_FAR"));
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

    /**
     * Mark the {@code count} oldest unheard messages as seen, and no more.
     *
     * <p>Used instead of {@link #markRead(ServerPlayer)} by a turn that built its observation from a
     * captured list: a message that arrives while the observation is being assembled is then still
     * unheard afterwards. Marking everything, as the plain version does, consumed it without ever
     * showing it - the bot was told nothing and the message was gone.
     */
    public static void markRead(ServerPlayer bot, int count) {
        if (count <= 0) {
            return;
        }
        Deque<Heard> log = LOGS.get(bot.getUUID());
        if (log == null) {
            return;
        }
        synchronized (log) {
            int marker = READ_MARKERS.getOrDefault(bot.getUUID(), 0);
            READ_MARKERS.put(bot.getUUID(), Math.min(log.size(), marker + count));
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

    /**
     * The bot's own recent lines, as a warning against repeating itself.
     *
     * <p>Set immediately after the address banner, because "you already answered this" is only
     * useful next to the message it applies to. Returns {@code null} when the bot has said nothing.
     */
    @Nullable
    public static String repetitionWarning(ServerPlayer bot) {
        List<Said> own = recentOwn(bot, 3);
        if (own.isEmpty()) {
            return null;
        }
        long now = bot.level().getGameTime();
        StringBuilder sb = new StringBuilder("You have already said, most recently:\n");
        for (int i = own.size() - 1; i >= 0; i--) {
            long secondsAgo = Math.max(0L, (now - own.get(i).gameTime()) / 20L);
            sb.append("  \"").append(own.get(i).text()).append("\"  (").append(secondsAgo)
              .append("s ago)\n");
        }
        sb.append("Do not send another acknowledgement of a request you have already answered, and do "
                + "not repeat a line you have already sent: saying the same thing again is worse than "
                + "staying silent. If nothing new has happened, stay silent.");
        return sb.toString();
    }

    /** Forget everything a bot heard; used when it leaves. */
    public static void clear(UUID uuid) {
        LOGS.remove(uuid);
        READ_MARKERS.remove(uuid);
        SAID.remove(uuid);
    }

    /** Forget everything; used on shutdown. */
    public static void clearAll() {
        LOGS.clear();
        READ_MARKERS.clear();
        SAID.clear();
    }

    /**
     * Remember a line the bot said itself.
     *
     * <p>A bot does not hear its own chat (see {@link #record}: the speaker is skipped), so without
     * this it has no memory of having answered and will happily send the same acknowledgement three
     * times. That is a real observed failure, not a hypothetical one.
     */
    public static void recordOwn(ServerPlayer bot, String text, long gameTime) {
        if (text == null || text.isBlank()) {
            return;
        }
        Deque<Said> said = SAID.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
        synchronized (said) {
            said.addLast(new Said(text, gameTime));
            while (said.size() > MAX_SAID) {
                said.removeFirst();
            }
        }
    }

    /** What the bot itself said recently, newest last. */
    public static List<Said> recentOwn(ServerPlayer bot, int limit) {
        Deque<Said> said = SAID.get(bot.getUUID());
        if (said == null) {
            return List.of();
        }
        synchronized (said) {
            List<Said> all = new ArrayList<>(said);
            int from = Math.max(0, all.size() - limit);
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    /**
     * Whether the bot has just said this, or something that contains it.
     *
     * <p>Comparison is on letters and digits only, lower-cased, so punctuation and spacing do not
     * hide a repeat. A short line has to match exactly; a longer one also counts as a repeat when it
     * merely contains an earlier line, which is what "收到，这就去挖钻石" followed by "收到，这就去挖钻石，
     * 马上出发" is.
     */
    public static boolean saidRecently(ServerPlayer bot, String text, long windowTicks) {
        String candidate = normalise(text);
        if (candidate.isEmpty()) {
            return false;
        }
        long now = bot.level().getGameTime();
        for (Said said : recentOwn(bot, MAX_SAID)) {
            if (now - said.gameTime() > windowTicks) {
                continue;
            }
            String earlier = normalise(said.text());
            if (earlier.isEmpty()) {
                continue;
            }
            if (earlier.equals(candidate)) {
                return true;
            }
            String shorter = earlier.length() <= candidate.length() ? earlier : candidate;
            String longer = earlier.length() <= candidate.length() ? candidate : earlier;
            if (shorter.length() >= 6 && longer.contains(shorter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Letters and digits only, lower-cased: what "the same thing" means for repetition.
     *
     * <p>Public so the brain can count repeats of the same instruction without duplicating the rule.
     */
    public static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
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
