package com.melody.mcagent.rt;

import java.util.List;
import java.nio.file.Path;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.rt.action.ActionPolicy;
import com.melody.mcagent.rt.brain.BrainManager;
import com.melody.mcagent.rt.llm.JevClient;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the core's config spec into the values the runtime runs on.
 *
 * <p>The spec itself has to stay in the core, because NeoForge wants it registered at mod
 * construction. Applying it is runtime work — it rebuilds the HTTP client and re-points live brains
 * — so it lives here, on the side of the boundary that knows what a brain is.
 */
public final class Config {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/config");

    private Config() {
    }

    /**
     * Apply the current config to the running system.
     *
     * <p>Idempotent and safe to call at any time. Called when the server starts, on every config
     * reload, and after an in-game command changes a value.
     */
    public static void apply() {
        BrainManager brains = Agent.brainManager();
        if (brains == null) {
            // Config can load before the server exists; the server start applies it then.
            return;
        }

        // Config events arrive on the mod-loading or file-watcher thread, but applying them
        // rebuilds clients and re-points live brains. Hop to the server thread so config mutation
        // is serialised with the tick that reads it, and so a future change to applyNow
        // cannot introduce a race that only shows up under load.
        MinecraftServer server = brains.server();
        if (server != null && !server.isSameThread()) {
            server.execute(Config::applyNow);
        } else {
            applyNow();
        }
    }

    /** The actual application step; must run on the server thread. */
    private static void applyNow() {
        BrainManager brains = Agent.brainManager();
        if (brains == null) {
            return;
        }

        BrainManager.Settings newSettings = settings();

        // The watcher can fire more than once for a single edit, and the initial load overlaps with
        // server start. Rebuilding the HTTP client and re-pointing every brain is not free, so skip
        // when nothing actually changed.
        if (!newSettings.equals(brains.settings())) {
            brains.applySettings(newSettings);
        }
        JevClient.Settings jev = JevClient.Settings.load(
                Path.of("config", "mcagent-jev.properties"));
        // Most deployments use the same OpenCode Zen account for the planning model and Jev. A
        // blank Jev key deliberately reuses the already-loaded LLM key, avoiding a second secret in
        // another file; an explicit Jev key still wins for split-provider setups.
        if (jev.enabled() && (jev.apiKey() == null || jev.apiKey().isBlank())
                && newSettings.apiKey() != null && !newSettings.apiKey().isBlank()) {
            jev = new JevClient.Settings(true, jev.endpoint(), newSettings.apiKey(), jev.model(),
                    jev.timeoutMillis(), jev.shadowMode(), jev.speechGate(), jev.routing(),
                    jev.protocol(), jev.maxTokens());
        }
        brains.applyJevSettings(jev);
        brains.setPolicy(policy());
        LOG.debug("Configuration applied: {}", newSettings.describeMasked());
    }

    /** Assemble the live LLM settings from the config files. */
    public static BrainManager.Settings settings() {
        return new BrainManager.Settings(
                safeGet(() -> AgentConfig.LLM.baseUrl.get(), ""),
                safeGet(() -> AgentConfig.LLM.apiKey.get(), ""),
                safeGet(() -> AgentConfig.LLM.model.get(), ""),
                safeGet(() -> AgentConfig.LLM.temperature.get(), 0.3D),
                safeGet(() -> AgentConfig.LLM.maxTokens.get(), 8192),
                safeGet(() -> AgentConfig.LLM.timeoutSeconds.get(), 90),
                safeGet(() -> AgentConfig.COMMON.contextTokenBudget.get(), 12000),
                safeGet(() -> AgentConfig.COMMON.observeRadius.get(), 24),
                parseHeaders(safeGet(() -> AgentConfig.LLM.extraHeaders.get(), List.<String>of())));
    }

    /** Parse "Name: value" config entries into a header map. Malformed entries are skipped. */
    private static java.util.Map<String, String> parseHeaders(List<? extends String> entries) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (Object raw : entries) {
            String entry = String.valueOf(raw);
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                LOG.warn("Ignoring malformed header config entry: {}", entry);
                continue;
            }
            String name = entry.substring(0, colon).trim();
            String value = entry.substring(colon + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    /** Assemble the permission policy from the config. */
    public static ActionPolicy policy() {
        return ActionPolicy.custom(
                safeGet(() -> AgentConfig.COMMON.allowedCommands.get(), List.<String>of()).stream()
                        .map(Object::toString).toList(),
                safeGet(() -> AgentConfig.COMMON.allowBreaking.get(), true),
                safeGet(() -> AgentConfig.COMMON.allowPlacing.get(), true),
                safeGet(() -> AgentConfig.COMMON.allowAttacking.get(), true),
                safeGet(() -> AgentConfig.COMMON.allowContainers.get(), true));
    }

    /**
     * Read a config value, falling back if the spec is not loaded yet.
     *
     * <p>NeoForge throws when a value is read before its config has loaded, which is easy to hit
     * during startup ordering. A default is always better than a crash here.
     */
    private static <T> T safeGet(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
