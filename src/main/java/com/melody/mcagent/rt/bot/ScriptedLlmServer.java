package com.melody.mcagent.rt.bot;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.rt.Config;
import com.sun.net.httpserver.HttpServer;

/**
 * A scripted, OpenAI-compatible endpoint running inside the server process.
 *
 * <p>Exists so a gated test can drive the <em>real</em> decision loop - the same client, the same
 * tool dispatcher, the same transcript - against a model whose answers are fixed. A test that
 * depends on what a real model chooses to do is a test that fails for unrelated reasons; a test that
 * bypasses the loop proves nothing about the loop.
 *
 * <p>Only ever created by a gated test; it binds to loopback on an ephemeral port.
 */
final class ScriptedLlmServer implements Closeable {

    /** Turns the request body into the response body. */
    private final Function<String, String> script;
    private final HttpServer http;
    /** Every request body the model was sent, in order. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    ScriptedLlmServer(Function<String, String> script) throws IOException {
        this.script = script;
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.http.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            this.requests.add(body);
            byte[] response = this.script.apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        this.http.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcagent-scripted-llm");
            thread.setDaemon(true);
            return thread;
        }));
        this.http.start();
    }

    /** Base URL to hand the mod's configuration, e.g. {@code http://127.0.0.1:34567/v1}. */
    String baseUrl() {
        return "http://127.0.0.1:" + this.http.getAddress().getPort() + "/v1";
    }

    /**
     * Point the mod at this stub, remembering nothing: call {@link #settings()} first.
     *
     * <p>NeoForge writes a config file back to disk when the config has been changed and the server
     * unloads, so a test that overrides the endpoint has to put the operator's settings back before
     * it stops - otherwise running a test silently rewrites the developer's {@code mcagent-llm.toml}
     * and, worse, drops the API key out of it.
     */
    void pointModAtThisServer() {
        AgentConfig.LLM.baseUrl.set(baseUrl());
        AgentConfig.LLM.apiKey.set("scripted-test");
        AgentConfig.LLM.model.set("scripted-test");
        Config.apply();
    }

    /** The LLM settings as they were, so they can be put back untouched. */
    static String[] settings() {
        return new String[] {
                String.valueOf(AgentConfig.LLM.baseUrl.get()),
                String.valueOf(AgentConfig.LLM.apiKey.get()),
                String.valueOf(AgentConfig.LLM.model.get())
        };
    }

    /** Put the operator's LLM settings back exactly as {@link #settings()} found them. */
    static void restoreSettings(String[] saved) {
        if (saved == null || saved.length < 3) {
            return;
        }
        AgentConfig.LLM.baseUrl.set(saved[0]);
        AgentConfig.LLM.apiKey.set(saved[1]);
        AgentConfig.LLM.model.set(saved[2]);
        Config.apply();
    }

    int requestCount() {
        return this.requests.size();
    }

    /** Most recent raw request body, for assertions about the exact prompt sent to the model. */
    String lastRequest() {
        synchronized (this.requests) {
            return this.requests.isEmpty() ? "" : this.requests.get(this.requests.size() - 1);
        }
    }

    /**
     * How often a phrase appears in the transcript the model was shown, at its highest.
     *
     * <p>Counted across every request rather than only the last one, because the model is the only
     * observer of tool results and old results are eventually collapsed by compaction. The maximum
     * is therefore the number of times the phrase had been reported by then.
     */
    int maxOccurrences(String phrase) {
        int most = 0;
        synchronized (this.requests) {
            for (String body : this.requests) {
                int count = 0;
                int from = 0;
                while ((from = body.indexOf(phrase, from)) >= 0) {
                    count++;
                    from += phrase.length();
                }
                most = Math.max(most, count);
            }
        }
        return most;
    }

    @Override
    public void close() {
        this.http.stop(0);
    }

    // --- response builders ----------------------------------------------------------------------

    /** A reply that asks for one tool call. {@code argumentsJson} is a JSON object literal. */
    static String toolCall(String id, String tool, String argumentsJson) {
        return """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"%s","type":"function","function":{"name":"%s","arguments":%s}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110,
                   "prompt_tokens_details":{"cached_tokens":64}}}
                """.formatted(id, tool, jsonString(argumentsJson));
    }

    /** A reply that asks for nothing: the model thinks and stays quiet. */
    static String silent() {
        return """
                {"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":100,"completion_tokens":1,"total_tokens":101,
                   "prompt_cache_hit_tokens":50,"prompt_cache_miss_tokens":50}}
                """;
    }

    /** A {@code say} call with the message escaped as a JSON string. */
    static String say(String id, String message) {
        return toolCall(id, "say", "{\"message\": " + quote(message) + "}");
    }

    /** JSON-escape a string, quotes included. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Wrap a JSON object literal as a JSON string, which is how tool arguments are sent. */
    private static String jsonString(String objectLiteral) {
        return quote(objectLiteral);
    }
}
