package com.melody.mcagent.rt.knowledge;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current {@link RecipeKnowledgeIndex}.
 *
 * <p>Exists so the index has one well-defined lifetime. A datapack reload replaces the server's
 * entire recipe manager, so the index must be rebuilt rather than mutated; keeping the swap in one
 * place makes that hard to get wrong.
 *
 * <p>The build runs on a worker thread, not on the caller's. It is the most expensive thing a reload
 * used to do on the server thread - 107-591 ms in production, for 54191 items and 27221 recipes - and
 * the caller is the server thread inside {@code /mcagent reload}. Recipes do not change when the
 * runtime jar is swapped, so all that time bought the same answer. What a reload <em>cannot</em> do
 * is keep the previous answer: the index object belongs to the class loader being closed, and holding
 * on to it would pin the whole generation. Each generation therefore builds its own; the only thing
 * worth removing was the wait for it.
 *
 * <p>Only the <em>snapshot</em> of the recipe list happens on the caller's thread, because reading
 * the recipe manager is world state. Everything after it is computation over immutable holders. Until
 * the worker publishes, {@link #get} returns null, and the callers already answer for that case:
 * the item and recipe tools say "the item/recipe index is not ready yet; try again shortly" for the
 * couple of hundred milliseconds it takes, instead of the server standing still for them.
 */
public final class KnowledgeManager {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/knowledge");

    /** How long a reload waits for an in-flight build before closing its class loader anyway. */
    private static final long JOIN_GRACE_MILLIS = 500L;

    @Nullable
    private static volatile RecipeKnowledgeIndex index;
    @Nullable
    private static volatile Thread worker;

    private KnowledgeManager() {
    }

    /**
     * Start building a fresh index, replacing any previous one.
     *
     * <p>Called at server start and on every reload, always from the server thread. Returns as soon
     * as the recipe list has been snapshotted - a copy of references, about a millisecond - and never
     * throws: recipe knowledge is a nice-to-have and must not take the server down.
     */
    public static void rebuild(MinecraftServer server) {
        List<RecipeHolder<?>> recipes;
        HolderLookup.Provider registries;
        try {
            // The one part that has to be on this thread: it reads the live recipe manager.
            recipes = List.copyOf(server.getRecipeManager().getRecipes());
            registries = server.registryAccess();
        } catch (Throwable t) {
            LOG.error("Failed to read the recipe manager; bots will lack recipe knowledge", t);
            return;
        }

        stopWorker();
        // The previous index answered for the previous generation. Dropping it here means a query
        // during the build is told "not ready" instead of being served data owned by a closed loader.
        index = null;

        Thread building = new Thread(() -> {
            try {
                index = RecipeKnowledgeIndex.build(registries, recipes);
            } catch (Throwable t) {
                LOG.error("Failed to build the item/recipe index", t);
            }
        }, "mcagent-knowledge");
        building.setDaemon(true);
        building.setPriority(Thread.MIN_PRIORITY);
        worker = building;
        building.start();
    }

    /** The current index, or null while it is still being built. */
    @Nullable
    public static RecipeKnowledgeIndex get() {
        return index;
    }

    /**
     * Drop the index, e.g. on shutdown.
     *
     * <p>A build still running is stopped first, because this generation's classes came from a class
     * loader that is about to be closed: a worker left running would die mid-recipe with a
     * {@code NoClassDefFoundError}. The wait is bounded and is normally not a wait at all - the build
     * takes a couple of hundred milliseconds and a reload comes minutes later.
     */
    public static void clear() {
        stopWorker();
        index = null;
    }

    private static void stopWorker() {
        Thread running = worker;
        worker = null;
        if (running == null || !running.isAlive()) {
            return;
        }
        running.interrupt();
        try {
            running.join(JOIN_GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (running.isAlive()) {
            LOG.warn("The knowledge index build did not stop within {} ms; closing the runtime class "
                    + "loader under it", JOIN_GRACE_MILLIS);
        }
    }
}
