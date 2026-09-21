package com.melody.mcagent.rt.brain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.melody.mcagent.rt.action.ActionPolicy;
import com.melody.mcagent.rt.bot.BotManager;
import com.melody.mcagent.rt.llm.LlmClient;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link AgentBrain} per bot, and the live LLM settings those brains use.
 *
 * <p>Kept separate from {@link BotManager} so the life-support for a synthetic player (joining,
 * physics, removal) has no dependency on the LLM at all. A bot can exist and be steered by commands
 * with no model configured, and the brain can be attached or detached independently.
 *
 * <p>Settings are re-applied through {@link #applySettings} rather than captured once at startup,
 * so a config edit or an in-game command takes effect on the next request instead of requiring a
 * restart. Brains are re-pointed rather than recreated, so a bot keeps its conversation and its
 * standing goal across a config change — which is what an operator fixing a typo expects.
 */
public final class BrainManager {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/brains");

    /** The live settings a brain uses. Replaced wholesale when configuration changes. */
    public record Settings(String baseUrl, String apiKey, String model,
                           double temperature, int maxTokens, int timeoutSeconds,
                           int contextTokenBudget, int observeRadius, Map<String, String> extraHeaders) {

        /** True when there is enough information to actually call a model. */
        public boolean isUsable() {
            return this.apiKey != null && !this.apiKey.isBlank()
                    && this.baseUrl != null && !this.baseUrl.isBlank()
                    && this.model != null && !this.model.isBlank();
        }

        /** Why the settings are unusable, phrased for an operator. */
        public String problem() {
            if (this.baseUrl == null || this.baseUrl.isBlank()) {
                return "baseUrl is empty";
            }
            if (this.model == null || this.model.isBlank()) {
                return "model is empty";
            }
            if (this.apiKey == null || this.apiKey.isBlank()) {
                return "apiKey is empty";
            }
            return "ok";
        }

        /** A short description with the key masked. */
        public String describeMasked() {
            String masked;
            if (this.apiKey == null || this.apiKey.isBlank()) {
                masked = "(no key)";
            } else if (this.apiKey.length() <= 8) {
                masked = "****";
            } else {
                masked = this.apiKey.substring(0, 4) + "..." + this.apiKey.substring(this.apiKey.length() - 4);
            }
            return "baseUrl=" + this.baseUrl + " model=" + this.model + " key=" + masked
                    + " contextBudget=" + this.contextTokenBudget
                    + " maxTokens=" + this.maxTokens
                    + (this.extraHeaders.isEmpty() ? "" : " headers=" + this.extraHeaders.keySet());
        }
    }

    private final MinecraftServer server;
    private final Map<UUID, AgentBrain> brains = new ConcurrentHashMap<>();

    @Nullable
    private volatile LlmClient client;
    @Nullable
    private volatile Settings settings;
    @Nullable
    private volatile ActionPolicy policy;

    public BrainManager(MinecraftServer server) {
        this.server = server;
    }

    /** The server this manager belongs to, used to marshal work onto the server thread. */
    public MinecraftServer server() {
        return this.server;
    }

    /**
     * Install new settings: rebuild the client and point every existing brain at it.
     *
     * <p>If the new settings are unusable we disable the client rather than keeping the previous
     * one. Continuing to call an endpoint the operator has just cleared or broken would be
     * surprising; disabling is the honest interpretation of "no valid configuration".
     */
    public void applySettings(Settings newSettings) {
        this.settings = newSettings;

        if (!newSettings.isUsable()) {
            this.client = null;
            LOG.warn("LLM disabled: {}", newSettings.problem());
            return;
        }

        LlmClient newClient = new LlmClient(
                newSettings.baseUrl(),
                newSettings.apiKey(),
                newSettings.model(),
                newSettings.temperature(),
                newSettings.maxTokens(),
                Duration.ofSeconds(newSettings.timeoutSeconds()),
                newSettings.extraHeaders());
        this.client = newClient;

        for (AgentBrain brain : this.brains.values()) {
            brain.rebind(newClient);
            brain.setTokenBudget(newSettings.contextTokenBudget());
            brain.setObserveRadius(newSettings.observeRadius());
        }

        LOG.info("LLM settings applied: {} ({} active brain(s) re-pointed)",
                newClient.describe(), this.brains.size());
    }

    public void setPolicy(ActionPolicy policy) {
        this.policy = policy;
    }

    /** Whether a model can currently be called. */
    public boolean isConfigured() {
        return this.client != null;
    }

    @Nullable
    public LlmClient client() {
        return this.client;
    }

    @Nullable
    public Settings settings() {
        return this.settings;
    }

    @Nullable
    public ActionPolicy policy() {
        return this.policy;
    }

    /**
     * Probe the configured endpoint for real, so an operator gets a definite answer.
     *
     * <p>Runs on the calling thread and does a real round trip. Callers on the server thread should
     * hand this to a worker, since a slow endpoint would otherwise stall the tick.
     */
    public String testConnection() {
        LlmClient current = this.client;
        if (current == null) {
            Settings s = this.settings;
            return "no usable LLM configuration (" + (s == null ? "none loaded" : s.problem()) + ")";
        }
        return current.testConnection();
    }

    /** Attach a brain to a bot, if a model is configured. */
    public boolean attach(ServerPlayer bot) {
        LlmClient current = this.client;
        ActionPolicy currentPolicy = this.policy;
        if (current == null || currentPolicy == null) {
            LOG.warn("Cannot attach a brain to {}: no LLM configured", bot.getName().getString());
            return false;
        }
        AgentBrain brain = new AgentBrain(bot, current, currentPolicy);
        brain.setTokenBudget(this.contextTokenBudget());
        brain.setObserveRadius(this.observeRadius());
        this.brains.put(bot.getUUID(), brain);
        LOG.info("Brain attached to {}", bot.getName().getString());
        return true;
    }

    /** Attach a brain to every registered bot that does not already have one. */
    public int attachAll() {
        int attached = 0;
        var bots = com.melody.mcagent.rt.Agent.botManager();
        if (bots == null) {
            return 0;
        }
        for (BotManager.BotHandle handle : bots.handles()) {
            if (!this.brains.containsKey(handle.player().getUUID()) && this.attach(handle.player())) {
                attached++;
            }
        }
        return attached;
    }

    public void detach(UUID uuid) {
        this.brains.remove(uuid);
    }

    /**
     * Point a bot's brain at its new body after a respawn.
     *
     * <p>The UUID survives respawn (vanilla sets the new entity's id from the same profile), so the
     * conversation is looked up rather than rebuilt: the bot keeps its memory of the chat and its
     * standing goal across dying, which is what a player would expect and what makes dying feel like
     * an event rather than a reset.
     *
     * @return true if a brain was found and re-pointed
     */
    public boolean rebind(net.minecraft.server.level.ServerPlayer fresh) {
        AgentBrain brain = this.brains.get(fresh.getUUID());
        if (brain == null) {
            // No brain (LLM was not configured when it joined), or the UUID changed unexpectedly.
            // Fall back to matching on name so a respawn cannot silently orphan a running brain.
            for (AgentBrain candidate : this.brains.values()) {
                if (candidate.bot().getName().getString().equals(fresh.getName().getString())) {
                    brain = candidate;
                    break;
                }
            }
        }
        if (brain == null) {
            return false;
        }
        brain.rebindPlayer(fresh);
        LOG.info("Brain re-pointed to respawned body of {}", fresh.getName().getString());
        return true;
    }

    public void detachAll() {
        this.brains.clear();
    }

    @Nullable
    public AgentBrain get(UUID uuid) {
        return this.brains.get(uuid);
    }

    /** Every attached brain. */
    public List<AgentBrain> all() {
        return List.copyOf(this.brains.values());
    }

    /** Tick every brain. Called on the server thread. */
    public void tick() {
        for (AgentBrain brain : this.brains.values()) {
            if (brain.bot().isRemoved()) {
                this.brains.remove(brain.bot().getUUID());
                continue;
            }
            try {
                brain.tick();
            } catch (Throwable t) {
                LOG.error("Brain tick failed for {}", brain.bot().getName().getString(), t);
            }
        }
    }

    /** Remove brains whose bots have left. */
    public void prune() {
        this.brains.entrySet().removeIf(entry -> {
            ServerPlayer player = this.server.getPlayerList().getPlayer(entry.getKey());
            return player == null || player.isRemoved();
        });
    }

    /** A short status line for each active brain, for operator diagnostics. */
    public String describe() {
        if (this.brains.isEmpty()) {
            return "No brains attached.";
        }
        StringBuilder sb = new StringBuilder();
        this.brains.values().forEach(brain -> {
            var state = brain.debugState();
            // A paused bot is invisible in every other field on this line: it is not thinking, not
            // mining, not cooling down. That is how a bot frozen for fifteen minutes read as
            // perfectly healthy, so the state is shouted rather than added as one more field, and
            // it carries the command that undoes it.
            String paused = Boolean.TRUE.equals(state.get("paused"))
                    ? " PAUSED (frozen: no decisions, no actions until /mcagent resume "
                            + state.get("bot") + ")"
                    : "";
            sb.append(String.format(
                    " - %s: thinking=%s cooldown=%s mining=%s history=%s ctx~%s/%s tokens (reported %s)%s%n",
                    state.get("bot"), state.get("thinking"), state.get("cooldownTicks"),
                    state.get("mining"), state.get("historyMessages"),
                    state.get("contextTokens"), state.get("tokenBudget"),
                    state.get("reportedPromptTokens"), paused));

            long cached = ((Number) state.get("cachedPromptTokens")).longValue();
            long uncached = ((Number) state.get("uncachedPromptTokens")).longValue();
            int cacheTurns = ((Number) state.get("cacheReportedTurns")).intValue();
            String cache = cacheTurns == 0
                    ? "n/a (provider returned no cache breakdown)"
                    : String.format(java.util.Locale.ROOT, "%.1f%% (hit=%d miss=%d, %d call%s)",
                            cached * 100.0D / Math.max(1L, cached + uncached),
                            cached, uncached, cacheTurns, cacheTurns == 1 ? "" : "s");
            sb.append(String.format(
                    "   usage since attach: calls=%s input=%s output=%s total=%s cache=%s%n",
                    state.get("usageTurns"), state.get("usagePromptTokens"),
                    state.get("usageCompletionTokens"), state.get("usageTotalTokens"), cache));
        });
        return sb.toString();
    }

    /** The configured context budget, used when attaching new brains. */
    public int contextTokenBudget() {
        Settings s = this.settings;
        return s == null ? 12000 : s.contextTokenBudget();
    }

    /** The configured perception radius, used when attaching new brains. */
    public int observeRadius() {
        Settings s = this.settings;
        return s == null ? 24 : s.observeRadius();
    }

    /** Apply a new context budget to every attached brain without rebuilding the client. */
    public void applyContextBudget(int tokens) {
        for (AgentBrain brain : this.brains.values()) {
            brain.setTokenBudget(tokens);
        }
    }
}
