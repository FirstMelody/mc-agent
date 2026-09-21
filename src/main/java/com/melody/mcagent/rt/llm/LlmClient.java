package com.melody.mcagent.rt.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal async client for OpenAI-compatible chat completions with tool calling.
 *
 * <p>Deliberately dependency-free beyond Gson (which Minecraft already ships) and the JDK's own
 * {@link HttpClient}: the mod must not add libraries to a 258-mod production server.
 *
 * <p>Every call is <b>asynchronous</b>. A bot's decision must never block the server tick, and an
 * LLM round trip can take seconds.
 */
public final class LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/llm");
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    /** Total tries per request, including the first. See the retry loop in {@link #complete}. */
    private static final int MAX_ATTEMPTS = 2;
    private final Duration timeout;
    private final Map<String, String> extraHeaders;

    public LlmClient(String baseUrl, String apiKey, String model,
                     double temperature, int maxTokens, Duration timeout) {
        this(baseUrl, apiKey, model, temperature, maxTokens, timeout, Map.of());
    }

    /**
     * @param extraHeaders additional request headers. Needed because some gateways require a
     *                     routing header beyond the standard Authorization - OpenCode Go, for
     *                     example, rejects requests without {@code x-opencode-session}.
     */
    public LlmClient(String baseUrl, String apiKey, String model,
                     double temperature, int maxTokens, Duration timeout,
                     Map<String, String> extraHeaders) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * One message in the conversation.
     *
     * <p>{@code reasoningContent} carries the model's thinking back to the provider. Some endpoints
     * (DeepSeek-style "thinking" models served through OpenCode Go, for instance) require the
     * assistant's reasoning to be echoed on subsequent turns; dropping it makes multi-turn tool use
     * misbehave. It is ignored by providers that do not use it.
     */
    public record Message(String role, @Nullable String content, @Nullable String toolCallId,
                          @Nullable String name, @Nullable List<ToolCall> toolCalls,
                          @Nullable String reasoningContent) {

        public static Message system(String content) {
            return new Message("system", content, null, null, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null, null, null);
        }

        public static Message assistant(@Nullable String content, @Nullable List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, null, toolCalls, null);
        }

        public static Message assistant(@Nullable String content, @Nullable List<ToolCall> toolCalls,
                                        @Nullable String reasoningContent) {
            return new Message("assistant", content, null, null, toolCalls, reasoningContent);
        }

        public static Message toolResult(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, null, null, null);
        }
    }

    /** A tool invocation requested by the model. */
    public record ToolCall(String id, String name, JsonObject arguments) {
    }

    /** A tool the model may call, described as a JSON schema. */
    public record ToolSpec(String name, String description, JsonObject parameters) {
    }

    /**
     * Token accounting for one exchange, as reported by the provider.
     *
     * <p>We report what the provider tells us rather than estimating: it is free, it is exact, and
     * it is the only trustworthy basis for a budget. {@code promptTokens} is the whole transcript,
     * so watching it over successive turns is how we detect that our own history is what is
     * growing.
     */
    public record Usage(int promptTokens, int completionTokens, int totalTokens,
                        int cachedPromptTokens, int uncachedPromptTokens,
                        boolean reported, boolean cacheReported) {

        public static final Usage UNKNOWN = new Usage(0, 0, 0, 0, 0, false, false);

        /** True when the provider gave us real numbers. */
        public boolean isKnown() {
            return this.reported;
        }

        /** True when the provider explicitly returned a cache-token breakdown. */
        public boolean isCacheKnown() {
            return this.reported && this.cacheReported;
        }
    }

    /** The model's response: text, tool calls, reasoning, usage, or an error. */
    public record Completion(@Nullable String content, List<ToolCall> toolCalls,
                             Usage usage, @Nullable String reasoningContent, @Nullable String error,
                             @Nullable String finishReason) {

        /**
         * True when the provider stopped because it hit the output token cap.
         *
         * <p>Worth distinguishing because a length-truncated turn is not an answer: the text may end
         * mid-sentence and any tool call it was about to emit is simply gone. The turn was wasted,
         * and the fix is a bigger output budget rather than a retry.
         */
        public boolean truncated() {
            return "length".equals(this.finishReason);
        }

        public boolean hasToolCalls() {
            return !this.toolCalls.isEmpty();
        }

        public boolean failed() {
            return this.error != null;
        }

        public static Completion error(String message) {
            return new Completion(null, List.of(), Usage.UNKNOWN, null, message, null);
        }
    }

    /**
     * Send a conversation and return the model's reply.
     *
     * <p>Blocking; callers on the server thread must invoke this off-thread (see
     * {@code AgentBrain}, which runs it on a small executor) so a slow or unreachable endpoint
     * cannot stall the tick loop.
     */
    public Completion complete(List<Message> messages, List<ToolSpec> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", this.model);
        body.addProperty("temperature", this.temperature);
        body.addProperty("max_tokens", this.maxTokens);

        JsonArray messageArray = new JsonArray();
        for (Message message : messages) {
            messageArray.add(toJson(message));
        }
        body.add("messages", messageArray);

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (ToolSpec tool : tools) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", tool.name());
                fn.addProperty("description", tool.description());
                fn.add("parameters", tool.parameters());
                t.add("function", fn);
                toolArray.add(t);
            }
            body.add("tools", toolArray);
            body.addProperty("tool_choice", "auto");
        }

        HttpRequest request = this.newRequest(body);

        // One retry for transient failures. Gateways routinely return a bare 500 or drop a
        // connection under load, and a bot that loses a turn to a momentary blip looks broken to the
        // players watching it: it stops, then says or does nothing for several seconds. A 4xx is a
        // request problem and is never retried, because repeating it just wastes another round trip.
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    String detail = response.body() == null ? "" : response.body();
                    if (detail.length() > 500) {
                        detail = detail.substring(0, 500);
                    }
                    boolean transientFailure = response.statusCode() >= 500
                            || response.statusCode() == 429;
                    if (transientFailure && attempts < MAX_ATTEMPTS) {
                        LOG.warn("LLM returned HTTP {}; retrying once", response.statusCode());
                        continue;
                    }
                    LOG.warn("LLM returned HTTP {}: {}", response.statusCode(), detail);
                    return Completion.error("HTTP " + response.statusCode() + ": " + detail);
                }
                return parse(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Completion.error("interrupted");
            } catch (Exception e) {
                // Timeouts and connection resets are indistinguishable from a blip at this level.
                if (attempts < MAX_ATTEMPTS) {
                    LOG.warn("LLM request failed ({}); retrying once", e.toString());
                    continue;
                }
                LOG.warn("LLM request failed", e);
                return Completion.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /** Build the HTTP request, including any provider-specific headers. */
    private HttpRequest newRequest(JsonObject body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "/chat/completions"))
                .timeout(this.timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.apiKey);
        this.extraHeaders.forEach((name, value) -> {
            if (name != null && value != null && !name.isBlank()) {
                builder.header(name, value);
            }
        });
        return builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
    }

    private JsonObject toJson(Message message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", message.role());
        if (message.content() != null) {
            obj.addProperty("content", message.content());
        }
        if (message.toolCallId() != null) {
            obj.addProperty("tool_call_id", message.toolCallId());
        }
        if (message.name() != null) {
            obj.addProperty("name", message.name());
        }
        // Echo the model's reasoning back when we have it. Some providers require this on
        // assistant turns that carry tool calls, and it is harmless elsewhere.
        if (message.reasoningContent() != null && !message.reasoningContent().isEmpty()) {
            obj.addProperty("reasoning_content", message.reasoningContent());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : message.toolCalls()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", call.id());
                c.addProperty("type", "function");
                JsonObject fn = new JsonObject();
                fn.addProperty("name", call.name());
                fn.addProperty("arguments", GSON.toJson(call.arguments()));
                c.add("function", fn);
                calls.add(c);
            }
            obj.add("tool_calls", calls);
        }
        return obj;
    }

    private Completion parse(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Completion.error("response contained no choices");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                return Completion.error("response contained no message");
            }

            String content = null;
            if (message.has("content") && !message.get("content").isJsonNull()) {
                content = message.get("content").getAsString();
            }

            List<ToolCall> calls = new ArrayList<>();
            if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                    JsonObject call = element.getAsJsonObject();
                    String id = call.has("id") ? call.get("id").getAsString() : "call_" + calls.size();
                    JsonObject fn = call.getAsJsonObject("function");
                    if (fn == null) {
                        continue;
                    }
                    String name = fn.get("name").getAsString();
                    JsonObject args = parseArguments(fn);
                    calls.add(new ToolCall(id, name, args));
                }
            }

            String reasoning = null;
            if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                reasoning = message.get("reasoning_content").getAsString();
            }
            String finish = null;
            if (choices.get(0).getAsJsonObject().has("finish_reason")
                    && !choices.get(0).getAsJsonObject().get("finish_reason").isJsonNull()) {
                finish = choices.get(0).getAsJsonObject().get("finish_reason").getAsString();
            }
            return new Completion(content, calls, parseUsage(root), reasoning, null, finish);
        } catch (Exception e) {
            LOG.warn("Could not parse LLM response: {}", raw, e);
            return Completion.error("malformed response: " + e.getMessage());
        }
    }

    /**
     * Read the provider's token accounting, if it sent any.
     *
     * <p>Not all OpenAI-compatible servers include {@code usage}, and some only include it when
     * asked, so a missing block is normal and reported as {@link Usage#UNKNOWN} rather than zero.
     */
    private Usage parseUsage(JsonObject root) {
        if (!root.has("usage") || !root.get("usage").isJsonObject()) {
            return Usage.UNKNOWN;
        }
        try {
            JsonObject usage = root.getAsJsonObject("usage");
            int prompt = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
            int completion = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0;
            int total = usage.has("total_tokens") ? usage.get("total_tokens").getAsInt() : prompt + completion;

            // SCNet/OpenAI-compatible gateways expose this as
            // usage.prompt_tokens_details.cached_tokens. Native DeepSeek-compatible endpoints use
            // prompt_cache_hit_tokens and prompt_cache_miss_tokens instead. Accept both so the
            // status is truthful across providers rather than silently showing 0% for an unknown
            // schema.
            Integer cached = null;
            Integer uncached = null;
            if (usage.has("prompt_tokens_details")
                    && usage.get("prompt_tokens_details").isJsonObject()) {
                JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
                if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) {
                    cached = details.get("cached_tokens").getAsInt();
                }
            }
            if (usage.has("prompt_cache_hit_tokens")
                    && !usage.get("prompt_cache_hit_tokens").isJsonNull()) {
                cached = usage.get("prompt_cache_hit_tokens").getAsInt();
            }
            if (usage.has("prompt_cache_miss_tokens")
                    && !usage.get("prompt_cache_miss_tokens").isJsonNull()) {
                uncached = usage.get("prompt_cache_miss_tokens").getAsInt();
            }
            boolean cacheReported = cached != null || uncached != null;
            int cacheHits = 0;
            int cacheMisses = 0;
            if (cacheReported) {
                cacheHits = cached == null ? Math.max(0, prompt - uncached) : Math.max(0, cached);
                cacheMisses = uncached == null
                        ? Math.max(0, prompt - cacheHits) : Math.max(0, uncached);
            }
            return new Usage(prompt, completion, total, cacheHits, cacheMisses,
                    true, cacheReported);
        } catch (Exception e) {
            return Usage.UNKNOWN;
        }
    }

    /**
     * Read a tool call's arguments.
     *
     * <p>Models are inconsistent here: the field is usually a JSON string, but some servers send
     * an object directly. Accepting both avoids a whole class of confusing failures.
     */
    private JsonObject parseArguments(JsonObject function) {
        if (!function.has("arguments") || function.get("arguments").isJsonNull()) {
            return new JsonObject();
        }
        JsonElement args = function.get("arguments");
        try {
            if (args.isJsonObject()) {
                return args.getAsJsonObject();
            }
            String text = args.getAsString();
            if (text == null || text.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOG.warn("Could not parse tool arguments: {}", args, e);
            return new JsonObject();
        }
    }

    /**
     * Verify the endpoint, key and model actually work, with a minimal request.
     *
     * <p>Exists because misconfiguration is the single most likely reason a bot does nothing, and
     * "no output" is a terrible diagnostic. This performs a real round trip and reports exactly
     * what went wrong: unreachable host, rejected key, unknown model, or a malformed body.
     *
     * @return a human-readable verdict, suitable for showing an operator in chat
     */
    public String testConnection() {
        long start = System.nanoTime();
        JsonObject body = new JsonObject();
        body.addProperty("model", this.model);
        body.addProperty("max_tokens", 16);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Reply with the single word: ok");
        messages.add(user);
        body.add("messages", messages);

        HttpRequest request;
        try {
            request = this.newRequest(body);
        } catch (Exception e) {
            return "FAILED to build request (is the baseUrl a valid URL?): " + e.getMessage();
        }

        try {
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - start) / 1_000_000L;
            int code = response.statusCode();

            if (code / 100 == 2) {
                Completion parsed = parse(response.body());
                if (parsed.failed()) {
                    return "reachable and authorised, but the reply could not be parsed: " + parsed.error();
                }
                String reply = parsed.content() == null ? "(no text)" : parsed.content().trim();
                String usage = parsed.usage().isKnown()
                        ? " tokens=" + parsed.usage().totalTokens()
                        : "";
                return "OK in " + ms + " ms - model replied \"" + reply + "\"" + usage;
            }

            // Give the operator the actionable cause rather than just a status code.
            String hint = switch (code) {
                case 401, 403 -> " (the API key was rejected - check apiKey)";
                case 404 -> " (endpoint or model not found - check baseUrl and model)";
                case 429 -> " (rate limited or out of quota)";
                default -> "";
            };
            String detail = response.body() == null ? "" : response.body();
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "...";
            }
            return "FAILED with HTTP " + code + hint + ": " + detail;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return "FAILED to connect within " + this.timeout.toSeconds() + "s - is the endpoint reachable?";
        } catch (java.net.ConnectException e) {
            return "FAILED to connect: " + e.getMessage() + " (is the host up and the port open?)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** A masked description of where this client points, safe to show in chat. */
    public String describe() {
        String masked = this.apiKey == null || this.apiKey.isBlank()
                ? "(no key)"
                : this.apiKey.length() <= 8
                        ? "****"
                        : this.apiKey.substring(0, 4) + "..." + this.apiKey.substring(this.apiKey.length() - 4);
        return this.baseUrl + " model=" + this.model + " key=" + masked;
    }

    /** Build a JSON-schema parameter block from ordered property descriptors. */
    public static JsonObject schema(Map<String, String> properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            JsonObject prop = new JsonObject();
            // The description carries the type hint in a simple "type: description" form.
            String value = entry.getValue();
            int colon = value.indexOf(':');
            if (colon > 0) {
                prop.addProperty("type", value.substring(0, colon).trim());
                prop.addProperty("description", value.substring(colon + 1).trim());
            } else {
                prop.addProperty("type", "string");
                prop.addProperty("description", value);
            }
            props.add(entry.getKey(), prop);
        }
        schema.add("properties", props);

        JsonArray req = new JsonArray();
        for (String name : required) {
            req.add(name);
        }
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Convenience for building a schema with no required fields. */
    public static JsonObject schema(Map<String, String> properties) {
        return schema(properties, List.of());
    }

    /** Ordered map helper, so schema properties keep a sensible order. */
    public static Map<String, String> params(String... keyValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
