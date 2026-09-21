package com.melody.mcagent;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's configuration spec, and the notification that a file has been (re)read.
 *
 * <p>The spec lives in the core because it has to exist before the runtime does: NeoForge wants it
 * registered during mod construction, and a config file that only appears once a runtime jar is
 * present would be useless to the operator who has to configure one.
 *
 * <p>The values are read by the runtime directly ({@code com.melody.mcagent.rt.Config} assembles
 * them into the shapes it needs). Nothing here may name a runtime type: the core has to stay
 * loadable, and replaceable, independently of the code that consumes these settings.
 *
 * <p>Two files, deliberately separated. The LLM endpoint lives in its own file because it holds an
 * API key — an operator can lock that file down, and it can be handed to a bot operator without
 * exposing gameplay tuning. Everything else is ordinary server configuration.
 *
 * <p>Hot reload is not automatic: NeoForge re-reads a config file only when its file watcher
 * notices a change, or when the server runs {@code /reload}. This class listens for both the
 * initial {@code Loading} and any later {@code Reloading} event and re-applies settings each time,
 * so an edit takes effect on the bot's next decision rather than at the next restart.
 */
public final class AgentConfig {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/config");

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;
    public static final Llm LLM;
    public static final ModConfigSpec LLM_SPEC;

    /**
     * Our config files, as the loader hands them over on load.
     *
     * <p>The only handle on a config file that can be re-read: FML builds a {@code ModConfig} per
     * file and offers no lookup by name, so the events are the one place to keep them from.
     */
    private static final Map<String, ModConfig> CONFIG_FILES = new ConcurrentHashMap<>();

    static {
        var commonPair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = commonPair.getLeft();
        COMMON_SPEC = commonPair.getRight();

        var llmPair = new ModConfigSpec.Builder().configure(Llm::new);
        LLM = llmPair.getLeft();
        LLM_SPEC = llmPair.getRight();
    }

    private AgentConfig() {
    }

    /** Registers both config files. Called from the mod constructor. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC, "mcagent-common.toml");
        container.registerConfig(ModConfig.Type.COMMON, LLM_SPEC, "mcagent-llm.toml");
    }

    /**
     * Fired by NeoForge after a config file is read — the first load and every later reload.
     *
     * <p>Note this is a <b>mod-bus</b> event ({@code IModBusEvent}), not a NeoForge event-bus one.
     * Registering it on {@code NeoForge.EVENT_BUS} would silently never fire, which is why its
     * registration lives in the mod constructor next to the other mod-bus listeners.
     *
     * <p>The runtime is told, not reached into: only it knows which of these values are live and
     * what has to be rebuilt when they change.
     */
    public static void onConfigEvent(ModConfigEvent event) {
        ModConfig config = event.getConfig();
        if (!McAgentMod.MODID.equals(config.getModId())) {
            return;
        }
        CONFIG_FILES.put(config.getFileName(), config);
        boolean reload = event instanceof ModConfigEvent.Reloading;
        LOG.info("Config {} {} - applying new settings",
                config.getFileName(), reload ? "reloaded" : "loaded");
        RuntimeHost.onConfigChanged();
    }

    /**
     * Re-read both config files from disk, and let the usual config event apply them.
     *
     * <p>Deliberately not {@code ConfigTracker.loadConfigs}, which is how this used to be done:
     * opening a config that is already open makes FML log the entire previously loaded config —
     * {@code apiKey} included, in clear text — as "Opening a config that was already loaded with
     * value ...". That warning lands in logs operators share, so the re-read goes through the
     * entry point the file watcher itself calls when it notices an edit instead: the file is
     * re-read, corrected if need be, and a {@code Reloading} event is fired, with nothing dumped.
     *
     * <p>Reflection is needed only because that entry point is package-private; FML has no public
     * "re-read this config now". If a future loader renames it the re-read stops working, which is
     * the safe direction to fail in — the caller is told, and the values already in memory stay in
     * force rather than a secret being printed to make the re-read happen.
     *
     * @return true when every config file was re-read
     */
    public static boolean rereadFromDisk() {
        if (CONFIG_FILES.isEmpty()) {
            LOG.warn("Cannot re-read the config files: none has been loaded yet");
            return false;
        }

        Method loadConfig;
        try {
            loadConfig = ConfigTracker.class.getDeclaredMethod("loadConfig",
                    ModConfig.class, Path.class, Function.class);
            loadConfig.setAccessible(true);
        } catch (Throwable t) {
            LOG.warn("Cannot re-read the config files: this version of the mod loader does not "
                    + "expose its per-file reload entry point. The values already in memory still "
                    + "apply, and edits are still picked up by the loader's file watcher.", t);
            return false;
        }

        boolean allRead = true;
        for (ModConfig config : CONFIG_FILES.values()) {
            Path file = config.getFullPath();
            if (file == null) {
                allRead = false;
                LOG.warn("Cannot re-read {}: it has no path on disk", config.getFileName());
                continue;
            }
            try {
                // The event a re-read fires is Reloading, exactly as if the watcher had noticed the
                // edit, so nothing downstream can tell a forced re-read from an automatic one.
                loadConfig.invoke(null, config, file,
                        (Function<ModConfig, ModConfigEvent>) ModConfigEvent.Reloading::new);
                LOG.info("Re-read {}", file);
            } catch (Throwable t) {
                allRead = false;
                LOG.warn("Could not re-read {}; the values already in memory still apply", file, t);
            }
        }
        return allRead;
    }

    /** Gameplay and safety settings. */
    public static final class Common {
        public final ModConfigSpec.BooleanValue brainsEnabled;
        public final ModConfigSpec.IntValue observeRadius;
        public final ModConfigSpec.IntValue actionCooldownTicks;
        public final ModConfigSpec.BooleanValue allowBreaking;
        public final ModConfigSpec.BooleanValue allowPlacing;
        public final ModConfigSpec.BooleanValue allowAttacking;
        public final ModConfigSpec.BooleanValue allowContainers;
        public final ModConfigSpec.ConfigValue<List<? extends String>> allowedCommands;
        public final ModConfigSpec.IntValue contextTokenBudget;

        Common(ModConfigSpec.Builder builder) {
            builder.comment("Gameplay settings for MC Agent bots.").push("general");

            this.brainsEnabled = builder
                    .comment("Whether bots think with an LLM. When false, bots still exist and can be",
                            "driven by /mcagent commands, but make no decisions on their own.")
                    .define("brainsEnabled", true);

            this.observeRadius = builder
                    .comment("Radius (in blocks) a bot scans when observing its surroundings.",
                            "A second, cheaper pass then looks out to twice this distance for",
                            "landmarks - trees, water, chests and machines - so the bot can see a",
                            "forest in the distance even though the detailed list stops here.",
                            "12 was too small to be usable: a bot in open country could not see a",
                            "tree line 25 blocks away and would wander off saying there were no",
                            "trees anywhere. 24 is a defensible 'what a person notices' range.",
                            "Cost grows with the cube of this value; 24 is about 8x a 12 scan.")
                    .defineInRange("observeRadius", 24, 4, 48);

            this.actionCooldownTicks = builder
                    .comment("Minimum ticks between a bot's decisions. Prevents a fast model from",
                            "flooding the server with actions.")
                    .defineInRange("actionCooldownTicks", 20, 1, 200);

            this.allowBreaking = builder.comment("Allow bots to break blocks.").define("allowBreaking", true);
            this.allowPlacing = builder.comment("Allow bots to place blocks.").define("allowPlacing", true);
            this.allowAttacking = builder.comment("Allow bots to attack entities.").define("allowAttacking", true);
            this.allowContainers = builder
                    .comment("Allow bots to open containers and move items.")
                    .define("allowContainers", true);

            this.contextTokenBudget = builder
                    .comment("Token budget for one bot's conversation transcript.",
                            "When the transcript exceeds this, older tool results are collapsed and",
                            "then the oldest turns are dropped, always on turn boundaries. The",
                            "bot's identity and any standing goal are never compacted away.")
                    .defineInRange("contextTokenBudget", 12000, 2000, 500000);

            this.allowedCommands = builder
                    .comment("Slash commands a bot may run, without the leading slash.",
                            "Bots are never operators, so the server's own permission checks still",
                            "apply on top of this list. Keep this minimal.")
                    .defineListAllowEmpty("allowedCommands",
                            List.of("home", "sethome", "spawn", "msg", "tell", "w", "r", "list", "tps", "help"),
                            () -> "home",
                            o -> o instanceof String s && !s.isBlank() && !s.contains(" "));

            builder.pop();
        }
    }

    /** Model endpoint settings. A separate file because it holds a secret. */
    public static final class Llm {
        public final ModConfigSpec.ConfigValue<String> baseUrl;
        public final ModConfigSpec.ConfigValue<String> apiKey;
        public final ModConfigSpec.ConfigValue<String> model;
        public final ModConfigSpec.DoubleValue temperature;
        public final ModConfigSpec.IntValue maxTokens;
        public final ModConfigSpec.IntValue timeoutSeconds;
        public final ModConfigSpec.ConfigValue<List<? extends String>> extraHeaders;

        Llm(ModConfigSpec.Builder builder) {
            builder.comment(
                    "LLM endpoint. Any OpenAI-compatible /chat/completions API works.",
                    "This file holds an API key - restrict its permissions.",
                    "Edits are picked up without a restart: use /mcagent reloadconfig to re-read now,",
                    "and /mcagent llm test to verify the endpoint really answers.").push("llm");

            this.baseUrl = builder
                    .comment("Base URL, ending before /chat/completions.",
                            "e.g. https://api.openai.com/v1 or a local server's /v1",
                            "Set in game with: /mcagent llm endpoint <url>")
                    .define("baseUrl", "");

            this.apiKey = builder
                    .comment("API key.",
                            "Set in game with: /mcagent llm key <key>   (writes it here and adopts it)")
                    .define("apiKey", "");

            this.model = builder
                    .comment("Model id to request. Set in game with: /mcagent llm model <id>")
                    .define("model", "");

            this.temperature = builder
                    .comment("Sampling temperature. Lower is more predictable and better for tool use.")
                    .defineInRange("temperature", 0.3D, 0.0D, 2.0D);

            this.maxTokens = builder
                    .comment("Maximum tokens in a single reply.",
                            "This is an output cap, and it has to clear the model's thinking as well",
                            "as its answer: a reasoning model that thinks hard can spend the entire",
                            "budget before it ever emits a tool call, and that whole turn is lost.",
                            "Too low looks like the bot staring into space - it 'thinks' for a full",
                            "turn and does nothing. The default is sized for a thinking model.",
                            "Raising it does not make replies longer; it only stops them being cut off.",
                            "Thinking depth itself is left at the provider default; no reasoning-effort",
                            "parameter is sent.")
                    .defineInRange("maxTokens", 8192, 64, 32768);

            this.timeoutSeconds = builder
                    .comment("HTTP timeout for one request.")
                    .defineInRange("timeoutSeconds", 90, 5, 600);

            this.extraHeaders = builder
                    .comment("Extra HTTP headers, as \"Name: value\" entries.",
                            "Some gateways require a routing header beyond Authorization.",
                            "OpenCode Go needs: x-opencode-session: <any-stable-id>",
                            "without it the API returns MissingSessionID.")
                    .defineListAllowEmpty("extraHeaders", List.of(),
                            () -> "",
                            o -> o instanceof String s2 && s2.contains(":"));

            builder.pop();
        }
    }
}
