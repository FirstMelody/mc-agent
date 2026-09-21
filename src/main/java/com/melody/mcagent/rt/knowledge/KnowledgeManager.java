package com.melody.mcagent.rt.knowledge;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current {@link RecipeKnowledgeIndex}.
 *
 * <p>Exists so the index has one well-defined lifetime. A datapack reload replaces the server's
 * entire recipe manager, so the index must be rebuilt rather than mutated; keeping the swap in one
 * place makes that hard to get wrong.
 */
public final class KnowledgeManager {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/knowledge");

    @Nullable
    private static volatile RecipeKnowledgeIndex index;

    private KnowledgeManager() {
    }

    /**
     * Build a fresh index, replacing any previous one.
     *
     * <p>Called at server start, where tags are guaranteed bound. Never throws: a failure leaves
     * the previous index (or none) in place and is logged, because recipe knowledge is a
     * nice-to-have and must not take the server down.
     */
    public static void rebuild(MinecraftServer server) {
        try {
            index = RecipeKnowledgeIndex.build(server);
        } catch (Throwable t) {
            LOG.error("Failed to build the item/recipe index", t);
        }
    }

    /** The current index, or null if it has not been built. */
    @Nullable
    public static RecipeKnowledgeIndex get() {
        return index;
    }

    /** Drop the index, e.g. on shutdown. */
    public static void clear() {
        index = null;
    }
}
