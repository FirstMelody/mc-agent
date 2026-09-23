package com.melody.mcagent.rt.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal client for Jev/System One typed decisions. It owns no threads. */
public final class JevClient {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/jev");
    private static final Gson GSON = new Gson();

    /**
     * How far the speech gate may go.
     *
     * <p>Deliberately separate from {@code shadowMode}, which governs the mining recovery: deciding
     * not to answer a chat line is a social act with an immediate undo (the bot can always be told
     * to answer), while deciding to move a bot is physical. An operator can therefore run the
     * speech gate for real while the recovery stays in shadow, which is the order this project
     * wants: cheap, reversible decisions first.
     */
    public enum GateMode {
        /** Never ask; the planning model decides whether to answer, exactly as before. */
        OFF,
        /** Ask and log the answer, but still let the planning model decide. Calibration only. */
        SHADOW,
        /** A confident STAY_SILENT suppresses the model call for that message entirely. */
        ACTIVE;

        static GateMode parse(String raw) {
            if (raw == null) {
                return SHADOW;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off", "false", "disabled" -> OFF;
                case "active", "on", "true", "enabled" -> ACTIVE;
                default -> SHADOW;
            };
        }
    }

    /**
     * Which wire protocol the endpoint speaks.
     *
     * <p>{@link #SYSTEM_ONE} is OpenCode Zen's typed-choice API: one request carries a state and a set
     * of candidate answers, and the reply carries the chosen one with its probability distribution.
     * {@link #CHAT} is the ordinary OpenAI-compatible {@code /chat/completions} call, which is what
     * every other provider offers (Vercel AI Gateway, a local proxy, anything). The decision is the
     * same; only the envelope differs.
     */
    public enum Protocol {
        SYSTEM_ONE,
        CHAT;

        static Protocol parse(String raw) {
            if (raw == null) {
                return SYSTEM_ONE;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "chat", "openai", "chat_completions", "chatcompletions" -> CHAT;
                default -> SYSTEM_ONE;
            };
        }
    }

    /** Runtime-only configuration, so enabling Jev never requires a shared-server restart. */
    public record Settings(boolean enabled, String endpoint, String apiKey, String model,
                           int timeoutMillis, boolean shadowMode, GateMode speechGate,
                           GateMode routing, GateMode interrupts, Protocol protocol,
                           int maxTokens) {

        public static Settings disabled() {
            return new Settings(false, "https://opencode.ai/zen/v1/systemone", "",
                    "jev-1.13-free", 5000, true, GateMode.OFF, GateMode.OFF, GateMode.OFF,
                    Protocol.SYSTEM_ONE, 1500);
        }

        public boolean isUsable() {
            return this.enabled && this.endpoint != null && !this.endpoint.isBlank()
                    && this.apiKey != null && !this.apiKey.isBlank()
                    && this.model != null && !this.model.isBlank();
        }

        /** Load {@code config/mcagent-jev.properties}; a missing file means cleanly disabled. */
        public static Settings load(Path path) {
            if (!Files.isRegularFile(path)) {
                return disabled();
            }
            Properties values = new Properties();
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException e) {
                LOG.warn("Could not read Jev config {}: {}", path, e.toString());
                return disabled();
            }
            boolean enabled = Boolean.parseBoolean(values.getProperty("enabled", "false").trim());
            String endpoint = values.getProperty(
                    "endpoint", "https://opencode.ai/zen/v1/systemone").trim();
            String apiKey = values.getProperty("apiKey", "").trim();
            String model = values.getProperty("model", "jev-1.13-free").trim();
            int timeout;
            try {
                timeout = Integer.parseInt(values.getProperty("timeoutMillis", "5000").trim());
            } catch (NumberFormatException ignored) {
                timeout = 5000;
            }
            boolean shadow = Boolean.parseBoolean(values.getProperty("shadowMode", "true").trim());
            GateMode gate = GateMode.parse(values.getProperty("speechGate", "shadow"));
            GateMode routing = GateMode.parse(values.getProperty("routing", "shadow"));
            GateMode interrupts = GateMode.parse(values.getProperty("interrupts", "shadow"));
            Protocol protocol = Protocol.parse(values.getProperty("protocol", "systemone"));
            int maxTokens;
            try {
                maxTokens = Integer.parseInt(values.getProperty("maxTokens", "1500").trim());
            } catch (NumberFormatException ignored) {
                maxTokens = 1500;
            }
            return new Settings(enabled, endpoint, apiKey, model,
                    Math.max(500, Math.min(30000, timeout)), shadow, gate, routing, interrupts, protocol,
                    Math.max(64, Math.min(32000, maxTokens)));
        }

        public String describeMasked() {
            return !this.enabled ? "disabled"
                    : "model=" + this.model + " endpoint=" + this.endpoint
                    + " key=" + (this.apiKey == null || this.apiKey.isBlank() ? "(missing)" : "****")
                    + " mode=" + (this.shadowMode ? "shadow" : "active")
                    + " speech_gate=" + this.speechGate.name().toLowerCase(java.util.Locale.ROOT)
                    + " routing=" + this.routing.name().toLowerCase(java.util.Locale.ROOT)
                    + " interrupts=" + this.interrupts.name().toLowerCase(java.util.Locale.ROOT)
                    + " protocol=" + this.protocol.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One typed choice result. */
    public record Choice(String choice, double confidence, Map<String, Double> probabilities,
                         @Nullable String error) {
        public boolean failed() {
            return this.error != null;
        }
    }

    /**
     * Consecutive failures before this client stops calling the endpoint for a while.
     *
     * <p>The production server was rate-limited (HTTP 429) on 480 of 735 calls in two hours - two out
     * of three - and every one of those was a fresh request to an endpoint that had already said no.
     * Besides being useless traffic, it competed for the same quota the speech gate needs to answer a
     * player. Backing off is what keeps the cheap decision available.
     */
    private static final int BREAKER_THRESHOLD = 3;
    /** How long to stay quiet once the endpoint has refused this many times in a row. */
    private static final long BREAKER_COOLDOWN_MILLIS = 45_000L;

    private final Settings settings;
    private final HttpClient http;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long breakerOpenUntil;

    public JevClient(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Settings settings() {
        return this.settings;
    }

    /** Blocking typed-choice call; AgentBrain always invokes this on its worker executor. */
    public Choice choose(String state, String questionId, String instructions,
                         Map<String, String> criteria) {
        long now = System.currentTimeMillis();
        if (now < this.breakerOpenUntil) {
            return new Choice("", 0.0D, Map.of(), "breaker open for another "
                    + ((this.breakerOpenUntil - now) / 1000L) + "s");
        }
        if (this.settings.protocol() == Protocol.CHAT) {
            return this.chooseViaChat(state, instructions, criteria);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", this.settings.model());
        body.addProperty("state", state);
        JsonObject question = new JsonObject();
        question.addProperty("type", "choice");
        question.addProperty("instructions", instructions);
        JsonObject options = new JsonObject();
        criteria.forEach(options::addProperty);
        question.add("criteria", options);
        JsonObject questions = new JsonObject();
        questions.add(questionId, question);
        body.add("questions", questions);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.settings.endpoint()))
                .timeout(Duration.ofMillis(this.settings.timeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        try {
            HttpResponse<String> response = this.http.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return this.failed("HTTP " + response.statusCode());
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject answer = root.getAsJsonObject("answers").getAsJsonObject(questionId);
            String chosen = answer.get("choice").getAsString().trim();
            // System One is still an untrusted model response. Apply the same candidate whitelist
            // as the chat-protocol fallback so an invented/misspelled choice can never reach a
            // physical-action switch merely because it came through the typed endpoint.
            if (!criteria.isEmpty() && !criteria.containsKey(chosen)) {
                return this.failed("the model answered '" + chosen + "', which was not offered");
            }
            double confidence = answer.has("confidence")
                    ? answer.get("confidence").getAsDouble() : 0.0D;
            Map<String, Double> probabilities = new LinkedHashMap<>();
            JsonObject distribution = answer.getAsJsonObject("probabilities");
            if (distribution != null) {
                for (Map.Entry<String, JsonElement> entry : distribution.entrySet()) {
                    probabilities.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            this.consecutiveFailures.set(0);
            return new Choice(chosen, confidence, Map.copyOf(probabilities), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Choice("", 0.0D, Map.of(), "interrupted");
        } catch (Exception e) {
            return this.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The same decision asked of any OpenAI-compatible {@code /chat/completions} endpoint.
     *
     * <p>The typed-choice envelope is OpenCode Zen's; every other provider - Vercel AI Gateway, a
     * local proxy - speaks chat completions. The contract is therefore carried in the prompt: one
     * JSON object with the choice and a confidence, and the answer is validated against the
     * candidates the caller actually offered, so a model that invents an option is a failure rather
     * than an unknown action.
     */
    private Choice chooseViaChat(String state, String instructions, Map<String, String> criteria) {
        JsonObject body = new JsonObject();
        body.addProperty("model", this.settings.model());
        body.addProperty("temperature", 0.0D);
        body.addProperty("max_tokens", this.settings.maxTokens());

        StringBuilder contract = new StringBuilder(instructions);
        contract.append("\n\nAnswer with ONE JSON object and nothing else, in exactly this shape:\n")
                .append("{\"choice\":\"<one of: ").append(String.join(" | ", criteria.keySet()))
                .append(">\",\"confidence\":<0.0-1.0>}\nOptions:\n");
        criteria.forEach((option, meaning) ->
                contract.append("  - ").append(option).append(": ").append(meaning).append('\n'));
        contract.append("Do not explain. Do not use markdown.");

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", contract.toString());
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", state);
        messages.add(user);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.settings.endpoint()))
                .timeout(Duration.ofMillis(this.settings.timeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        try {
            HttpResponse<String> response = this.http.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return this.failed("HTTP " + response.statusCode() + " "
                        + firstLine(response.body()));
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";
            JsonObject answer = extractJsonObject(content);
            if (answer == null) {
                return this.failed("no JSON object in the answer ("
                        + (content.isBlank() ? "empty content: the model spent its whole budget "
                                + "thinking; raise maxTokens" : firstLine(content)) + ")");
            }
            String chosen = answer.has("choice") ? answer.get("choice").getAsString().trim() : "";
            if (!criteria.isEmpty() && !criteria.containsKey(chosen)) {
                return this.failed("the model answered '" + chosen + "', which was not offered");
            }
            double confidence = answer.has("confidence") ? answer.get("confidence").getAsDouble() : 0.0D;
            Map<String, Double> probabilities = new LinkedHashMap<>();
            if (answer.has("probabilities") && answer.get("probabilities").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : answer.getAsJsonObject("probabilities").entrySet()) {
                    probabilities.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            this.consecutiveFailures.set(0);
            return new Choice(chosen, confidence, Map.copyOf(probabilities), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Choice("", 0.0D, Map.of(), "interrupted");
        } catch (Exception e) {
            return this.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The first JSON object in a model's answer, tolerating prose or a fenced code block around it. */
    @Nullable
    private static JsonObject extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(content.substring(start, end + 1));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** First line of a response body, for a log line that stays one line. */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        int newline = trimmed.indexOf('\n');
        String line = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /**
     * Record a failed call, opening the breaker once the endpoint has refused repeatedly.
     *
     * <p>Logged once per opening rather than per call: the callers already log every decision, and a
     * rate-limited endpoint would otherwise fill the log with the same line.
     */
    private Choice failed(String error) {
        if (this.consecutiveFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            this.consecutiveFailures.set(0);
            this.breakerOpenUntil = System.currentTimeMillis() + BREAKER_COOLDOWN_MILLIS;
            LOG.warn("Jev endpoint failed {} times in a row ({}); pausing Jev calls for {}s so the "
                    + "quota is not spent on refusals", BREAKER_THRESHOLD, error,
                    BREAKER_COOLDOWN_MILLIS / 1000L);
        }
        return new Choice("", 0.0D, Map.of(), error);
    }

    /** True while the breaker is holding calls back; used by tests and diagnostics. */
    public boolean breakerOpen() {
        return System.currentTimeMillis() < this.breakerOpenUntil;
    }
}
