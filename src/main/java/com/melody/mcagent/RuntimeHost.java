package com.melody.mcagent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the replaceable half of the mod: the jar on disk, the {@link ClassLoader} it is read
 * through, and the live {@link AgentRuntime} instance.
 *
 * <p>This is the only place in the core allowed to hold the current runtime, and it holds it typed
 * as the core interface. Nothing here is registered with Minecraft or NeoForge, so dropping the
 * instance and closing its loader on reload is enough to make the previous generation collectable.
 *
 * <p>The runtime jar lives in {@code mcagent-runtime/} next to the server, deliberately not in
 * {@code mods/}: a jar in {@code mods/} would be picked up by NeoForge as a mod in its own right.
 */
public final class RuntimeHost {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/runtime");

    /** Entry point class inside the runtime jar, instantiated through its no-arg constructor. */
    private static final String ENTRYPOINT = "com.melody.mcagent.rt.RuntimeEntry";

    private static final Path RUNTIME_DIR = Path.of("mcagent-runtime");
    private static final String RUNTIME_JAR_NAME = "mcagent-runtime.jar";

    @Nullable
    private static volatile AgentRuntime runtime;
    @Nullable
    private static RuntimeClassLoader loader;
    @Nullable
    private static Path loadedJar;
    private static long loadedSize;
    @Nullable
    private static String loadedHash;
    private static long loadedAtMillis;
    @Nullable
    private static volatile String lastError;

    @Nullable
    private static MinecraftServer server;

    // The live command tree, kept so a freshly loaded module can add its commands to it without a
    // server restart. All three are Minecraft-side objects, never runtime ones.
    @Nullable
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    @Nullable
    private static Commands.CommandSelection commandSelection;
    @Nullable
    private static CommandBuildContext commandBuildContext;

    private RuntimeHost() {
    }

    /** The jar a runtime is loaded from. Relative to the server's working directory on purpose. */
    public static Path jarPath() {
        return RUNTIME_DIR.resolve(RUNTIME_JAR_NAME).toAbsolutePath().normalize();
    }

    /** The live runtime, or {@code null} when none is loaded. */
    @Nullable
    public static AgentRuntime runtime() {
        return runtime;
    }

    /**
     * Load the runtime jar, replacing any currently loaded instance.
     *
     * <p>Safe to call at any time on the server thread, and the only way back from a broken jar: a
     * failure leaves no instance installed, so the operator can fix the jar and try again.
     *
     * @return true if a runtime is loaded and running at the end of the call
     */
    public static synchronized boolean reload() {
        unloadCurrent();

        Path jar = jarPath();
        if (!Files.isRegularFile(jar)) {
            fail("no runtime jar at " + jar
                    + " - build one with './gradlew runtimeJar' and copy it there");
            return false;
        }

        RuntimeClassLoader fresh = null;
        AgentRuntime instance;
        String hash;
        long size;
        try {
            hash = sha256(jar);
            size = Files.size(jar);
            fresh = new RuntimeClassLoader(new URL[] { jar.toUri().toURL() },
                    RuntimeHost.class.getClassLoader());
            Class<?> entrypoint = Class.forName(ENTRYPOINT, true, fresh);
            instance = entrypoint.asSubclass(AgentRuntime.class).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            // Nothing is installed on this path, so the mod stays in a state where /mcagent reload
            // can be tried again after the jar is fixed.
            closeQuietly(fresh);
            fail("could not load " + ENTRYPOINT + " from " + jar + ": " + t);
            LOG.error("MC Agent runtime load failed", t);
            return false;
        }

        loader = fresh;
        runtime = instance;
        loadedJar = jar;
        loadedSize = size;
        loadedHash = hash;
        loadedAtMillis = System.currentTimeMillis();
        lastError = null;

        LOG.info("MC Agent runtime loaded from {} ({} bytes, sha256={})", jar, size, hash);
        LOG.info("MC Agent runtime status: {}", instance.status());

        MinecraftServer current = server;
        if (current != null) {
            try {
                // A module loaded into a server that is already running has to adopt it, otherwise
                // it would sit there with no managers while /mcagent list reports nothing.
                instance.onServerStarted(current);
            } catch (Throwable t) {
                fail("the loaded runtime failed to start on the running server: " + t);
                LOG.error("Unloading the MC Agent runtime", t);
                unloadCurrent();
                return false;
            }
            refreshCommands();
        }
        return true;
    }

    /**
     * Call {@link AgentRuntime#onUnload()} and drop every reference to the instance and its loader.
     *
     * <p>This is what makes a reload leak-free: once the loader is closed and the instance is gone,
     * only the JVM's own bookkeeping stands between the previous generation and collection — unless
     * something outside still points at it, which is why the runtime must not leave bots, threads or
     * event listeners behind.
     */
    public static synchronized void unloadCurrent() {
        AgentRuntime previous = runtime;
        runtime = null;
        if (previous != null) {
            try {
                previous.onUnload();
            } catch (Throwable t) {
                LOG.error("The MC Agent runtime failed during unload", t);
            }
        }

        RuntimeClassLoader previousLoader = loader;
        loader = null;
        if (previousLoader != null) {
            try {
                previousLoader.close();
            } catch (Throwable t) {
                LOG.warn("Could not close the previous runtime class loader", t);
            }
        }

        loadedJar = null;
        loadedHash = null;
        loadedSize = 0L;
    }

    /** One operator-facing block describing the loaded runtime, or why there is none. */
    public static String describe() {
        AgentRuntime current = runtime;
        Path jar = jarPath();
        StringBuilder sb = new StringBuilder();
        sb.append("runtime jar : ").append(jar)
                .append(Files.isRegularFile(jar) ? " (present on disk)" : " (MISSING)");
        if (current == null) {
            sb.append("\nruntime     : NOT LOADED");
            if (lastError != null) {
                sb.append("\nlast error  : ").append(lastError);
            }
            sb.append("\nfix the jar and run /mcagent reload");
            return sb.toString();
        }
        sb.append("\nruntime     : ").append(current.getClass().getName());
        sb.append("\nloaded at   : ").append(new java.util.Date(loadedAtMillis))
                .append(" (").append((System.currentTimeMillis() - loadedAtMillis) / 1000L).append("s ago)");
        sb.append("\nsize/sha256 : ").append(loadedSize).append(" bytes / ").append(loadedHash);
        sb.append("\nstatus      : ").append(current.status());
        return sb.toString();
    }

    // --- delegation -------------------------------------------------------------------------

    static void onServerStarted(MinecraftServer server) {
        RuntimeHost.server = server;
        AgentRuntime current = runtime;
        if (current == null) {
            LOG.error("No MC Agent runtime is loaded ({} is missing or broken), so bots and commands "
                    + "are unavailable. Fix the jar and run /mcagent reload.", jarPath());
            return;
        }
        current.onServerStarted(server);
    }

    static void onServerTick(MinecraftServer server) {
        AgentRuntime current = runtime;
        if (current != null) {
            current.onServerTick(server);
        }
    }

    static void onServerStopping(MinecraftServer server) {
        AgentRuntime current = runtime;
        if (current != null) {
            current.onServerStopping(server);
        }
        RuntimeHost.server = null;
        dispatcher = null;
        commandSelection = null;
        commandBuildContext = null;
    }

    static void onServerChat(ServerChatEvent event) {
        AgentRuntime current = runtime;
        if (current != null) {
            current.onServerChat(event);
        }
    }

    static void onRegisterCommands(RegisterCommandsEvent event) {
        dispatcher = event.getDispatcher();
        commandSelection = event.getCommandSelection();
        commandBuildContext = event.getBuildContext();
        AgentRuntime current = runtime;
        if (current != null) {
            current.registerCommands(event);
        } else {
            LOG.warn("/mcagent is reduced to the core's runtime/reload commands: no runtime is loaded");
        }
    }

    static void onConfigChanged() {
        AgentRuntime current = runtime;
        if (current != null) {
            current.onConfigChanged();
        }
    }

    /**
     * Register the current runtime's commands on the dispatcher that is already live.
     *
     * <p>Brigadier merges a repeated registration into the existing nodes rather than rejecting it:
     * children are folded in and executors replaced. That is what revives {@code /mcagent <verb>}
     * after a reload, and it clears the previous generation's lambdas out of the command tree, which
     * would otherwise pin the old loader until the next command rebuild.
     */
    private static void refreshCommands() {
        CommandDispatcher<CommandSourceStack> live = dispatcher;
        Commands.CommandSelection selection = commandSelection;
        CommandBuildContext context = commandBuildContext;
        AgentRuntime current = runtime;
        if (live == null || selection == null || context == null || current == null) {
            return;
        }
        try {
            current.registerCommands(new RegisterCommandsEvent(live, selection, context));
            MinecraftServer currentServer = server;
            if (currentServer != null) {
                for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                    currentServer.getCommands().sendCommands(player);
                }
            }
            LOG.info("Runtime commands re-registered on the live command dispatcher");
        } catch (Throwable t) {
            LOG.warn("Could not re-register the runtime's commands; they return on the next server "
                    + "start or datapack /reload", t);
        }
    }

    private static void fail(String message) {
        lastError = message;
        LOG.error("MC Agent runtime not loaded: {} (/mcagent reload retries)", message);
    }

    private static void closeQuietly(@Nullable URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            LOG.warn("Could not close a failed runtime class loader", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The runtime jar's loader: child-first for the runtime's own package, parent-first for
     * everything else.
     *
     * <p>Parent-first is mandatory for Minecraft, NeoForge and the core. If this loader defined its
     * own copy of {@code ServerPlayer} the two classes would be distinct types and the first
     * hand-off to the server would fail with a {@code ClassCastException}.
     *
     * <p>For {@code com.melody.mcagent.rt} the order is deliberately reversed. In a development run
     * the same classes are also on the launch classpath, and a parent-first lookup would resolve
     * them from there: the mod would appear to reload while continuing to run the old code. Reading
     * them from the jar makes the jar the single source of truth, which is the whole contract.
     */
    private static final class RuntimeClassLoader extends URLClassLoader {

        private static final String RUNTIME_PACKAGE = "com.melody.mcagent.rt.";

        RuntimeClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(RUNTIME_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
