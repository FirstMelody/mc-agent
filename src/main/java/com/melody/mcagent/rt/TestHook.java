package com.melody.mcagent.rt;

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
}
