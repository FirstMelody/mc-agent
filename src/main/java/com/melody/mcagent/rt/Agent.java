package com.melody.mcagent.rt;

import com.melody.mcagent.rt.brain.BrainManager;
import com.melody.mcagent.rt.bot.BotManager;
import com.melody.mcagent.rt.knowledge.KnowledgeManager;
import com.melody.mcagent.rt.knowledge.RecipeKnowledgeIndex;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The runtime's own state: which bots exist and which of them are thinking.
 *
 * <p>This used to live on {@code McAgentMod}. It cannot: bot and brain managers belong to the code
 * that gets replaced, and a core-side static field pointing at one would keep the whole runtime
 * class loader alive after a reload.
 *
 * <p>All of it is per-generation. A reload installs new managers here and the previous ones die
 * with their loader.
 */
public final class Agent {

    @Nullable
    private static volatile BotManager botManager;
    @Nullable
    private static volatile BrainManager brainManager;

    private Agent() {
    }

    /** Adopt a freshly started generation's managers. */
    static void install(BotManager bots, BrainManager brains) {
        botManager = bots;
        brainManager = brains;
    }

    /**
     * Drop the managers.
     *
     * <p>Called after the bots have been removed, so nothing is left pointing at live entities from
     * a generation whose loader is about to be closed.
     */
    static void clear() {
        botManager = null;
        brainManager = null;
    }

    @Nullable
    public static BotManager botManager() {
        return botManager;
    }

    @Nullable
    public static BrainManager brainManager() {
        return brainManager;
    }

    /** The item/recipe knowledge index, or null before the server has started. */
    @Nullable
    public static RecipeKnowledgeIndex knowledge() {
        return KnowledgeManager.get();
    }

    /** Convenience for commands that want to spawn a bot with a brain already attached. */
    public static boolean attachBrain(ServerPlayer bot) {
        BrainManager manager = brainManager;
        return manager != null && manager.attach(bot);
    }
}
