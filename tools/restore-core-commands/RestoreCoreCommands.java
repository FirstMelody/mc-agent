import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Put the core's {@code /mcagent runtime} and {@code /mcagent reload} back on the live dispatcher.
 *
 * <p>Recovery tool, not a feature. A runtime jar once removed the whole {@code /mcagent} node before
 * re-registering its own tree, which deleted the two subcommands the core module owns - including the
 * one used to load a new jar. Brigadier has no unregister, so the only other way back was a full
 * server restart; the core's own registration can simply be run again, and this does that.
 *
 * <p>Everything is reflective on purpose: this class is compiled against nothing but the JDK, so it
 * never needs rebuilding when the mod or NeoForge changes. It runs inside the target JVM through the
 * Attach API, driven by the same {@code Attach} helper the console tool uses.
 *
 * <p>The work is handed to the server thread, because the dispatcher is not thread-safe.
 */
public final class RestoreCoreCommands {

    private RestoreCoreCommands() {
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        // An attached agent is loaded by the system class loader, which cannot see the mod's classes:
        // NeoForge keeps the game and the mods in their own module layers. Ask the instrumentation
        // object for the class the JVM already loaded, then take its loader for everything else.
        Class<?> host = loaded(inst, "com.melody.mcagent.RuntimeHost");
        if (host == null) {
            System.err.println("[restore-core-commands] the core mod's RuntimeHost is not loaded in "
                    + "this JVM; nothing to restore");
            return;
        }
        final ClassLoader loader = host.getClassLoader();
        Object dispatcher = staticField(host, "dispatcher");
        Object selection = staticField(host, "commandSelection");
        Object context = staticField(host, "commandBuildContext");
        Object server = staticField(host, "server");
        if (dispatcher == null || selection == null || context == null || server == null) {
            System.err.println("[restore-core-commands] RuntimeHost has not cached a dispatcher yet; "
                    + "nothing to restore");
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable work = () -> {
            try {
                Class<?> eventClass = Class.forName(
                        "net.neoforged.neoforge.event.RegisterCommandsEvent", false, loader);
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : eventClass.getDeclaredConstructors()) {
                    if (candidate.getParameterCount() == 3) {
                        constructor = candidate;
                        break;
                    }
                }
                if (constructor == null) {
                    throw new IllegalStateException("no 3-argument RegisterCommandsEvent constructor");
                }
                constructor.setAccessible(true);
                Object event = constructor.newInstance(dispatcher, selection, context);

                Class<?> core = Class.forName("com.melody.mcagent.AgentCommands", false, loader);
                Method register = core.getDeclaredMethod("register", eventClass);
                register.setAccessible(true);
                register.invoke(null, event);
                System.err.println("[restore-core-commands] re-registered the core's /mcagent runtime "
                        + "and /mcagent reload subcommands on the live dispatcher");

                // Tell every online client about the restored nodes, otherwise an operator's own
                // completion list stays stale even though the console can already run the command.
                try {
                    Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
                    Object commands = server.getClass().getMethod("getCommands").invoke(server);
                    Method send = commands.getClass().getMethod("sendCommands",
                            Class.forName("net.minecraft.server.level.ServerPlayer", false, loader));
                    for (Object player : (Iterable<?>) playerList.getClass()
                            .getMethod("getPlayers").invoke(playerList)) {
                        send.invoke(commands, player);
                    }
                    System.err.println("[restore-core-commands] re-sent the command tree to the "
                            + "connected clients");
                } catch (Throwable t) {
                    System.err.println("[restore-core-commands] could not re-send the command tree "
                            + "(the console command still works): " + t);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        };

        server.getClass().getMethod("execute", Runnable.class).invoke(server, work);
        if (!done.await(30, TimeUnit.SECONDS)) {
            System.err.println("[restore-core-commands] timed out waiting for the server thread");
            return;
        }
        if (failure.get() != null) {
            System.err.println("[restore-core-commands] FAILED: " + failure.get());
            failure.get().printStackTrace();
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A class the JVM already has loaded, found by name.
     *
     * <p>Asking the instrumentation object beats guessing loaders: NeoForge keeps the game and the
     * mods in their own module layers, and neither the system loader nor a thread's context loader
     * is guaranteed to be the one that can see {@code com.melody.mcagent}.
     */
    private static Class<?> loaded(Instrumentation inst, String name) {
        for (Class<?> candidate : inst.getAllLoadedClasses()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** The class loader that can see Minecraft and the mod, taken from the server thread. */
    private static ClassLoader serverLoader() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(thread.getName()) && thread.getContextClassLoader() != null) {
                return thread.getContextClassLoader();
            }
        }
        return ClassLoader.getSystemClassLoader();
    }
}
