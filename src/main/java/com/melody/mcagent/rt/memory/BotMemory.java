package com.melody.mcagent.rt.memory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a bot has learned and wants to keep.
 *
 * <p>A conversation transcript is not memory. It is compacted, it is bounded, and it disappears
 * entirely when the server restarts — so a bot that spends an afternoon being shown around a base
 * starts the next session knowing nothing about it, asks where the chests are again, and re-derives
 * facts it already worked out. This is the durable layer: small, hand-written notes that survive
 * restarts and are pinned into every prompt.
 *
 * <p>Deliberately <em>not</em> an automatic transcript of everything seen. Memory that writes itself
 * fills with noise and buries the facts worth keeping; the model decides what was worth
 * remembering, which is a judgement it is good at and a filter we cannot write.
 *
 * <p>One exception is recorded automatically, because it is exactly the kind of thing a player
 * remembers without deciding to and the bot would otherwise have to be told twice: the contents of a
 * container it has opened. Everything else — where home is, what a machine does, who owns which base
 * — is written through the {@code remember} tool, by the model's own judgement.
 *
 * <p>Storage is one small JSON file per bot under {@code <world>/mcagent-memory/}, keyed by name
 * rather than UUID so a bot keeps its notes if it is removed and spawned again.
 */
public final class BotMemory {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/memory");

    /** Most facts a single bot may keep. Beyond this the oldest-written entries are dropped. */
    public static final int MAX_ENTRIES = 60;
    /** Facts shown inline in the prompt; the rest are reachable with the recall tool. */
    public static final int PROMPT_ENTRIES = 18;
    /** Longest a stored value may be, in characters. */
    public static final int MAX_VALUE_CHARS = 240;
    /** Longest a stored key may be, in characters. */
    public static final int MAX_KEY_CHARS = 64;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** name (lowercase) -> memory. Loaded once per server, kept for the process lifetime. */
    private static final Map<String, BotMemory> CACHE = new ConcurrentHashMap<>();

    private final String botName;
    private final Path file;
    /** key -> value. Insertion-ordered, so the prompt shows the oldest notes first and eviction
     *  removes them first: a note that has survived a long time is usually the stable one. */
    private final Map<String, String> facts = new LinkedHashMap<>();
    /** key -> the tick it was last written, for the "recently learned" ordering in the prompt. */
    private final Map<String, Long> writtenAt = new LinkedHashMap<>();
    /** Runtime-owned durable state, saved here but never shown to or editable by the model. */
    private final Map<String, String> systemValues = new LinkedHashMap<>();

    private BotMemory(String botName, Path file) {
        this.botName = botName;
        this.file = file;
    }

    /** The memory for a bot, loading it from disk on first use. */
    public static BotMemory of(MinecraftServer server, String botName) {
        String key = botName.toLowerCase(Locale.ROOT);
        return CACHE.computeIfAbsent(key, ignored -> {
            BotMemory memory = new BotMemory(botName, resolveFile(server, botName));
            memory.load();
            return memory;
        });
    }

    /** Forget every cached memory; used on shutdown so a reopened world reloads from disk. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Path resolveFile(MinecraftServer server, String botName) {
        // Bot names are player names, but they are also user input from a command, so anything that
        // could escape the directory is replaced rather than trusted.
        StringBuilder safe = new StringBuilder();
        for (char c : botName.toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("mcagent-memory")
                .resolve(safe + ".json");
    }

    // --- reading and writing --------------------------------------------------------------------

    /**
     * Store a fact, replacing any note under the same key.
     *
     * @return a short description of what happened, for the model
     */
    public String remember(String key, String value, long gameTime) {
        String cleanKey = clean(key, MAX_KEY_CHARS);
        String cleanValue = clean(value, MAX_VALUE_CHARS);
        if (cleanKey.isEmpty()) {
            return "failed: a memory needs a short key, e.g. 'home' or 'iron chest'";
        }
        if (cleanValue.isEmpty()) {
            return "failed: a memory needs something to remember";
        }

        boolean replaced = this.facts.containsKey(cleanKey);
        // Re-inserting moves the key to the end of the insertion order, which is what makes eviction
        // drop the least recently written note rather than the least recently written *once*.
        this.facts.remove(cleanKey);
        this.facts.put(cleanKey, cleanValue);
        this.writtenAt.remove(cleanKey);
        this.writtenAt.put(cleanKey, gameTime);

        while (this.facts.size() > MAX_ENTRIES) {
            String oldest = this.facts.keySet().iterator().next();
            this.facts.remove(oldest);
            this.writtenAt.remove(oldest);
        }

        this.save();
        return (replaced ? "updated memory '" : "remembered '") + cleanKey + "': " + cleanValue;
    }

    /** Drop a fact. */
    public String forget(String key) {
        String cleanKey = clean(key, MAX_KEY_CHARS);
        String removed = this.facts.remove(cleanKey);
        this.writtenAt.remove(cleanKey);
        if (removed == null) {
            return "failed: nothing remembered under '" + cleanKey + "'";
        }
        this.save();
        return "forgot '" + cleanKey + "'";
    }

    /** Facts whose key or value contains the query, newest first. */
    public List<Map.Entry<String, String>> search(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : this.facts.entrySet()) {
            if (needle.isEmpty()
                    || entry.getKey().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.getValue().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        // Most recently written first: if several notes match, the fresh one is usually the one
        // being asked about.
        out.sort((a, b) -> Long.compare(
                this.writtenAt.getOrDefault(b.getKey(), 0L),
                this.writtenAt.getOrDefault(a.getKey(), 0L)));
        return out;
    }

    /** Everything remembered, oldest first. */
    public List<Map.Entry<String, String>> all() {
        return new ArrayList<>(this.facts.entrySet());
    }

    public int size() {
        return this.facts.size();
    }

    public boolean isEmpty() {
        return this.facts.isEmpty();
    }

    /** Read durable runtime state without consuming one of the model's memory entries. */
    @Nullable
    public String systemValue(String key) {
        return this.systemValues.get(key);
    }

    /** Store durable runtime state such as the established mine route. */
    public void putSystemValue(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        this.systemValues.put(key, value);
        this.save();
    }

    /** Where this bot's notes live. Exposed for diagnostics and tests. */
    public Path file() {
        return this.file;
    }

    /**
     * Render the memory block for the system prompt.
     *
     * <p>Bounded on purpose: memory is pinned, so every character of it is paid for on every single
     * request for the rest of the bot's life. Only the most recently written notes are shown inline;
     * the rest stay reachable through the recall tool, and the model is told how many there are so
     * it knows to go looking rather than assuming it has the whole picture.
     */
    public String render() {
        if (this.facts.isEmpty()) {
            return "";
        }

        List<Map.Entry<String, String>> entries = this.all();
        // Newest first: when the block has to be cut, the freshest notes are the ones in play.
        List<Map.Entry<String, String>> newestFirst = new ArrayList<>(entries);
        newestFirst.sort((a, b) -> Long.compare(
                this.writtenAt.getOrDefault(b.getKey(), 0L),
                this.writtenAt.getOrDefault(a.getKey(), 0L)));

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== THINGS YOU REMEMBER ===\n");
        sb.append("Notes you wrote for yourself in earlier sessions. They are still true unless you\n");
        sb.append("have seen otherwise since.\n");
        for (int i = 0; i < Math.min(newestFirst.size(), PROMPT_ENTRIES); i++) {
            Map.Entry<String, String> entry = newestFirst.get(i);
            sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        if (newestFirst.size() > PROMPT_ENTRIES) {
            sb.append("  ... and ").append(newestFirst.size() - PROMPT_ENTRIES)
              .append(" older note(s) you can look up with the recall tool.\n");
        }
        return sb.toString();
    }

    // --- persistence ----------------------------------------------------------------------------

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject stored = root.has("facts") ? root.getAsJsonObject("facts") : new JsonObject();
            for (String key : stored.keySet()) {
                this.facts.put(key, stored.get(key).getAsString());
            }
            JsonObject times = root.has("writtenAt") ? root.getAsJsonObject("writtenAt") : new JsonObject();
            for (String key : times.keySet()) {
                this.writtenAt.put(key, times.get(key).getAsLong());
            }
            JsonObject system = root.has("system") ? root.getAsJsonObject("system") : new JsonObject();
            for (String key : system.keySet()) {
                this.systemValues.put(key, system.get(key).getAsString());
            }
            LOG.info("Loaded {} remembered fact(s) for {}", this.facts.size(), this.botName);
        } catch (Throwable t) {
            // A corrupt or older memory file must never stop a bot from thinking.
            LOG.warn("Could not read memory for {}; starting empty", this.botName, t);
        }
    }

    /** Write the memory out. Called on every change: the file is tiny and losses are permanent. */
    private void save() {
        try {
            Files.createDirectories(this.file.getParent());
            JsonObject root = new JsonObject();
            JsonObject stored = new JsonObject();
            this.facts.forEach(stored::addProperty);
            root.add("facts", stored);
            JsonObject times = new JsonObject();
            this.writtenAt.forEach(times::addProperty);
            root.add("writtenAt", times);
            JsonObject system = new JsonObject();
            this.systemValues.forEach(system::addProperty);
            root.add("system", system);
            try (Writer writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Throwable t) {
            LOG.warn("Could not save memory for {}", this.botName, t);
        }
    }

    /** Trim, collapse whitespace, and bound a stored string. */
    private static String clean(@Nullable String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        return text.length() > maxChars ? text.substring(0, maxChars).trim() : text;
    }
}
