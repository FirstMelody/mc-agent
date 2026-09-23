package com.melody.mcagent.rt;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-tick hook for the gated smoke tests.
 *
 * <p>The tests used to register themselves on {@code NeoForge.EVENT_BUS}. They cannot: the bus is
 * static and lives in the mod's own loader, so a listener living in the runtime would pin the whole
 * runtime class loader and make every reload leak one. The entry point ticks them directly instead,
 * which keeps every reference to them inside the generation that owns them.
 */
public interface TestHook {

    void onTick();

    /**
     * Give a harness bot one decision, the way an operator does with {@code /mcagent think}.
     *
     * <p>Production now waits for a task or an event when a bot has no standing goal, so a harness bot
     * that is spawned bare never asks its scripted model at all - the tunnel test reported "still at
     * 70,-40,70 after 1600 ticks" with no request in between, and every other harness that expects
     * autonomous work would stall the same way. The COMMAND trigger is the documented bypass for
     * exactly this: an explicit request for a decision now.
     */
    static void nudge(ServerPlayer bot) {
        if (bot == null) {
            return;
        }
        var brains = Agent.brainManager();
        var brain = brains == null ? null : brains.get(bot.getUUID());
        if (brain != null) {
            brain.requestDecisionNow();
        }
    }
}
