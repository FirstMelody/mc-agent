package com.melody.mcagent.rt.command;

import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.bot.BotManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** End-to-end proof that /mcagent inventory opens and edits the bot's live pack. */
public final class InventoryCommandSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/inventorycmdtest");
    private final MinecraftServer server;
    private boolean done;

    private InventoryCommandSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_INVENTORY_TEST"));
    }

    public static TestHook arm(MinecraftServer server) {
        return enabled() ? new InventoryCommandSmokeTest(server) : null;
    }

    @Override
    public void onTick() {
        if (this.done) {
            return;
        }
        this.done = true;
        ServerLevel level = this.server.overworld();
        Vec3 at = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        BotManager.BotHandle target = Agent.botManager().spawn("PackTarget", level, at, true);
        BotManager.BotHandle viewer = Agent.botManager().spawn("PackViewer", level, at.add(100, 0, 0), true);
        boolean pass = false;
        try {
            if (target == null || viewer == null) {
                throw new IllegalStateException("could not spawn command test players");
            }
            target.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            int result = this.server.getCommands().getDispatcher().execute(
                    "mcagent inventory PackTarget",
                    viewer.player().createCommandSourceStack().withPermission(4));
            boolean opened = viewer.player().containerMenu instanceof ChestMenu;
            if (opened) {
                ChestMenu menu = (ChestMenu) viewer.player().containerMenu;
                boolean readsLive = menu.getContainer().getItem(0).is(Items.DIAMOND);
                menu.getContainer().setItem(1, new ItemStack(Items.EMERALD, 2));
                boolean writesLive = target.player().getInventory().getItem(1).is(Items.EMERALD);
                pass = result > 0 && menu.getRowCount() == 4 && readsLive && writesLive;
                LOG.info("INVENTORYCMDTEST result={} opened={} rows={} readsLive={} writesLive={}",
                        result, opened, menu.getRowCount(), readsLive, writesLive);
            }
        } catch (Throwable t) {
            LOG.error("INVENTORYCMDTEST failed", t);
        } finally {
            if (viewer != null) {
                viewer.player().closeContainer();
            }
            Agent.botManager().remove("PackViewer");
            Agent.botManager().remove("PackTarget");
            LOG.info("INVENTORYCMDTEST VERDICT: {}", pass ? "PASS" : "FAIL");
            this.server.halt(false);
        }
    }
}
