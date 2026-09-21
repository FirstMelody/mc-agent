package com.melody.mcagent.rt.bot;

import com.melody.mcagent.AgentConfig;
import com.melody.mcagent.rt.Agent;
import com.melody.mcagent.rt.Config;
import com.melody.mcagent.rt.TestHook;
import com.melody.mcagent.rt.action.ActionPolicy;
import com.melody.mcagent.rt.brain.BrainManager;
import com.melody.mcagent.rt.knowledge.KnowledgeManager;
import com.melody.mcagent.rt.perception.ObservationBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end verification of the LLM-driven loop.
 *
 * <p>Enabled with MCAGENT_BRAIN_TEST=true. Unlike {@link BotSmokeTest}, which proves the player
 * mechanics, this proves the full stack: perception produces a real observation, the model is
 * called with the tool schema, and a chosen tool actually runs.
 */
public final class BrainSmokeTest implements TestHook {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/braintest");

    private final MinecraftServer server;
    private int ticks;
    private boolean started;
    private boolean finished;
    private boolean observationLogged;
    private boolean reported;
    private boolean killed;
    private net.minecraft.core.BlockPos goldPos;
    private net.minecraft.core.BlockPos treeBase;
    private net.minecraft.core.BlockPos tablePos;
    private com.melody.mcagent.rt.brain.AgentBrain treeTestBrain;
    private BotManager.BotHandle treeTestHandle;
    private int treeTestPhase;
    private int treeTestTicks;
    private int treeTestStartTicks = -1;
    /** Identity of the body the bot had before it was killed, to prove respawn replaces it. */
    private net.minecraft.server.level.ServerPlayer preDeathPlayer;

    public BrainSmokeTest(MinecraftServer server) {
        this.server = server;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("MCAGENT_BRAIN_TEST"));
    }

    /** Armed by the runtime entry point; see {@link TestHook} for why not by the event bus. */
    public static TestHook arm(MinecraftServer server) {
        if (!enabled()) {
            return null;
        }
        LOG.info("BRAINTEST: armed");
        return new BrainSmokeTest(server);
    }

    @Override
    public void onTick() {
        if (this.finished) {
            return;
        }

        if (!this.started) {
            this.started = true;
            this.begin();
            return;
        }

        this.ticks++;

        // Report the observation the model is actually being shown, once, so a human can judge
        // whether the perception layer is producing something sensible and legible.
        if (!this.observationLogged && this.ticks == 20) {
            this.observationLogged = true;
            var handle = Agent.botManager() == null ? null : Agent.botManager().get("BrainBot");
            if (handle != null) {
                String observation = ObservationBuilder.describe(handle.player(), 8);
                LOG.info("BRAINTEST ---- OBSERVATION SHOWN TO THE MODEL ----\n{}", observation);
                LOG.info("BRAINTEST ---- END OBSERVATION ----");
                var brains = Agent.brainManager();
                LOG.info("BRAINTEST brains configured={}", brains != null && brains.isConfigured());
                if (brains != null) {
                    LOG.info("BRAINTEST brain state:\n{}", brains.describe());
                }
            }
        }

        this.tickTreeTest();

        // Give the model several decision cycles, then report and verify the death path.
        if (!this.reported && this.ticks >= 2000) {
            this.reported = true;
            this.report();
        }

        // DEATHTEST runs after the task, because dying drops the bot's inventory and would destroy
        // the very items the crafting half of this test depends on.
        if (this.reported && !this.killed && this.ticks >= 2040) {
            this.killBot();
        }
        if (this.killed && this.ticks >= 2160) {
            this.verifyRespawn();
            this.finish();
        }
    }

    private void begin() {
        ServerLevel level = this.server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();

        // Put a chest and some items in the world so there is something to perceive and act on.
        BlockPos chestPos = spawn.offset(2, 0, 0);
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());

        if (level.getBlockEntity(chestPos) instanceof net.minecraft.world.Container container) {
            container.setItem(0, new net.minecraft.world.item.ItemStack(Blocks.IRON_BLOCK, 7));
            container.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
            container.setChanged();
        }


        // A landmark the bot should be able to describe, and later mine.
        BlockPos goldPos = spawn.offset(3, 0, 3);
        this.goldPos = goldPos;
        level.setBlockAndUpdate(goldPos, Blocks.GOLD_BLOCK.defaultBlockState());

        // A small "tree": four logs stacked. Chopping this exercises two things a single-block mine
        // cannot - the radius job that keeps going through connected blocks of the same kind, and the
        // pickup phase that walks over the drops afterwards.
        this.treeBase = spawn.offset(-3, 0, -3);
        for (int i = 0; i < 4; i++) {
            level.setBlockAndUpdate(this.treeBase.above(i),
                    net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        }
        LOG.info("BRAINTEST: tree of 4 oak logs based at {}", this.treeBase);

        // A crafting table remains a useful visible landmark, although crafting deliberately grants
        // the bot a 3x3 grid anywhere to avoid an extra LLM round trip just for table ceremony.
        this.tablePos = spawn.offset(0, 0, 2);
        level.setBlockAndUpdate(this.tablePos,
                net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        LOG.info("BRAINTEST: crafting table at {}", this.tablePos);

        LOG.info("BRAINTEST: chest with 7 iron blocks + 3 diamonds at {}, gold block at {}",
                chestPos, goldPos);

        Vec3 botPos = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        // --- CONFIGTEST: exercise the runtime LLM configuration path ---
        // 1. Confirm we start unconfigured (the config files were written empty on purpose).
        var brains0 = Agent.brainManager();
        LOG.info("CONFIGTEST initial: configured={} reason={}",
                brains0 != null && brains0.isConfigured(),
                Config.settings().problem());

        // 2. Set endpoint/key/model at runtime, exactly as the command does, and confirm the
        //    running system adopts them without a restart.
        String key = System.getenv("MCAGENT_TEST_KEY");
        if (key != null && !key.isBlank()) {
            AgentConfig.LLM.baseUrl.set("http://10.0.6.6:7863/v1");
            AgentConfig.LLM.apiKey.set(key);
            AgentConfig.LLM.model.set("deepseek-v4.1-flash");
            AgentConfig.LLM.baseUrl.save();
            AgentConfig.LLM.apiKey.save();
            AgentConfig.LLM.model.save();
            Config.apply();
            LOG.info("CONFIGTEST after set: configured={} desc={}",
                    Agent.brainManager().isConfigured(),
                    Config.settings().describeMasked());

            // 3. Prove the endpoint really answers (this is what /mcagent llm test does).
            LOG.info("CONFIGTEST connection test: {}", Agent.brainManager().testConnection());
        } else {
            LOG.info("CONFIGTEST skipped: MCAGENT_TEST_KEY not set");
        }

        var handle = Agent.botManager().spawn("BrainBot", level, botPos, false);
        if (handle == null) {
            LOG.error("BRAINTEST: FAIL - could not spawn bot");
            this.finish();
            return;
        }

        boolean attached = Agent.attachBrain(handle.player());
        LOG.info("BRAINTEST: bot spawned, brain attached={}", attached);

        // Give the bot raw materials so we can verify crafting from its own inventory. Nine iron
        // ingots is exactly one iron block, which is checkable without ambiguity.
        var inventory = handle.player().getInventory();
        inventory.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 9));
        inventory.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 8));
        LOG.info("BRAINTEST: gave the bot 9 iron ingots + 8 oak planks");

        // Give the bot a concrete, checkable goal. Without one, a model may simply wander, which
        // proves the loop runs but not that it can achieve anything. This goal requires the bot to
        // walk to the chest, open it, and take something out of it - exercising pathfinding, the
        // container realism gate, and inventory transfer in one task.
        // --- CHATTEST: prove the bot can hear and answer -------------------------------------
        // Driven through the real event, not by poking ChatLog directly. The earlier version of this
        // test called ChatLog.record with the bot itself as the speaker, which the earshot filter
        // immediately rejects - so it verified nothing while looking like it passed.
        //
        // A second, brainless bot stands in for the human: ServerChatEvent needs a real
        // ServerPlayer as the sender, and posting it on the event bus is exactly what
        // ServerGamePacketListenerImpl does for a genuine client message.
        var speakerHandle = Agent.botManager().spawn(
                "Speaker", level, botPos.add(3.0, 0.0, 0.0), false);
        if (speakerHandle == null) {
            LOG.error("CHATTEST: FAIL - could not spawn the stand-in speaker");
        } else {
            String question = "BrainBot, can you hear me? Please reply in Chinese.";
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.ServerChatEvent(
                            speakerHandle.player(), question,
                            net.minecraft.network.chat.Component.literal(question)));

            var bot = handle.player();
            LOG.info("CHATTEST unheard after real chat event: {}",
                    com.melody.mcagent.rt.perception.ChatLog.unheard(bot).size());
            LOG.info("CHATTEST shouldRespondPromptly: {}",
                    com.melody.mcagent.rt.perception.ChatLog.shouldRespondPromptly(bot));
            LOG.info("CHATTEST unheardDirected: {}",
                    com.melody.mcagent.rt.perception.ChatLog.unheardDirected(bot).size());
            String banner = com.melody.mcagent.rt.perception.ChatLog.directAddressBanner(bot);
            LOG.info("CHATTEST banner present: {}", banner != null);
            if (banner != null) {
                LOG.info("CHATTEST banner:\n{}", banner);
            }
            LOG.info("CHATTEST observation section:\n{}",
                    com.melody.mcagent.rt.perception.ChatLog.describe(bot));

            // Remove the stand-in so it cannot be mistaken for a second agent later in the run.
            Agent.botManager().remove(speakerHandle);
        }

        // --- MEMTEST: durable notes, and that they really survive a reload ---------------------
        var memory = com.melody.mcagent.rt.memory.BotMemory.of(this.server, "BrainBot");
        LOG.info("MEMTEST initial size: {}", memory.size());
        LOG.info("MEMTEST remember -> {}",
                memory.remember("home", "my base is the chest at 2,-60,0", 0L));
        LOG.info("MEMTEST remember -> {}",
                memory.remember("gold", "gold block for the test is at 3,-60,3", 0L));
        LOG.info("MEMTEST file: {} (exists={})",
                memory.file(), java.nio.file.Files.isRegularFile(memory.file()));
        LOG.info("MEMTEST rendered block:\n{}", memory.render());

        // The real question is not whether it is in RAM but whether it comes back after a restart,
        // so drop the cache and load it again from disk exactly as a new session would.
        //
        // Compared against what was in memory rather than against a hard-coded count: notes from a
        // previous run of this test are still on disk, and they *should* still be there. An earlier
        // version of this check asserted "exactly 2" and failed for that reason - the number was
        // wrong, not the persistence.
        int sizeBeforeReload = memory.size();
        String homeBeforeReload = memory.search("home").isEmpty() ? null
                : memory.search("home").get(0).getValue();
        com.melody.mcagent.rt.memory.BotMemory.clearCache();
        var reloaded = com.melody.mcagent.rt.memory.BotMemory.of(this.server, "BrainBot");
        LOG.info("MEMTEST after reload size: {} (was {})", reloaded.size(), sizeBeforeReload);
        LOG.info("MEMTEST after reload recall('home'): {}", reloaded.search("home"));
        boolean persisted = reloaded.size() == sizeBeforeReload
                && !reloaded.search("home").isEmpty()
                && reloaded.search("home").get(0).getValue().equals(homeBeforeReload);
        LOG.info("MEMTEST survives reload: {}", persisted);
        if (!persisted) {
            LOG.error("MEMTEST: FAIL - notes did not survive a reload from disk");
        }

        // And that a stale note can be dropped.
        LOG.info("MEMTEST forget -> {}", reloaded.forget("gold"));
        LOG.info("MEMTEST size after forget: {}", reloaded.size());

        var brain = Agent.brainManager() == null ? null : Agent.brainManager().get(handle.player().getUUID());

        // --- TREETEST: radius mining and pickup, driven directly ------------------------------
        // Deliberately not left to the model: this is deterministic, and a model in the loop only
        // adds ways for the test to fail for unrelated reasons.
        if (brain != null) {
            var bot = handle.player();
            bot.teleportTo(this.treeBase.getX() + 1.5, this.treeBase.getY(),
                    this.treeBase.getZ() + 0.5);
            LOG.info("TREETEST starting radius mine at {} (4 logs stacked)", this.treeBase);
            LOG.info("TREETEST -> {}", brain.mineAsTool(this.treeBase, 6));
        }
        this.treeTestBrain = brain;
        this.treeTestHandle = handle;
        this.treeTestPhase = 1;
        this.treeTestTicks = 0;

        if (brain != null) {
            brain.primeGoal(String.format(
                    "Do these four things. "
                    + "1) Craft a minecraft:iron_block from the iron ingots you are carrying. "
                    + "2) Mine the minecraft:gold_block at x=%d, y=%d, z=%d - walk to it first, then break it. "
                    + "3) There is a chest at x=%d, y=%d, z=%d. Walk to it, open it, and take the "
                    + "diamonds out of it. "
                    + "4) Chop down the whole oak tree based at x=%d, y=%d, z=%d - the trunk is "
                    + "four logs stacked, so use ONE mine call with a radius. Collect the logs "
                    + "afterwards. "
                    + "5) Say in chat a one-line summary of what you did. "
                    + "Use one plan tool call for every step whose coordinates are already known, "
                    + "instead of waiting for each result. Also remember anything "
                    + "worth knowing next session, and pick up anything you drop.",
                    goldPos.getX(), goldPos.getY(), goldPos.getZ(),
                    chestPos.getX(), chestPos.getY(), chestPos.getZ(),
                    treeBase.getX(), treeBase.getY(), treeBase.getZ()));
            LOG.info("BRAINTEST: goal primed -> open the chest and take the diamonds");
        }

        var knowledge = KnowledgeManager.get();
        LOG.info("BRAINTEST: knowledge index present={}", knowledge != null);
        if (knowledge != null) {
            LOG.info("BRAINTEST: {}", knowledge.summary());
            LOG.info("BRAINTEST: sample lookup 'iron_block' ->\n{}",
                    knowledge.describeItemAndRecipes("iron_block", 3));
        }
    }

    /**
     * Let the radius-mining job run, then check the tree fell and the logs were collected.
     *
     * <p>Waits for the job to report itself idle rather than for a fixed number of ticks, so the
     * test measures the job rather than the clock.
     */
    private void tickTreeTest() {
        if (this.treeTestPhase == 0 || this.treeTestBrain == null) {
            return;
        }
        this.treeTestTicks++;
        if (this.treeTestPhase == 1) {
            if (this.treeTestBrain.isMining() && this.treeTestTicks > 5) {
                this.treeTestPhase = 2;
                this.treeTestStartTicks = this.treeTestTicks;
                LOG.info("TREETEST job started: {}", this.treeTestBrain.currentAction());
            } else if (this.treeTestTicks > 200) {
                LOG.error("TREETEST: FAIL - the mining job never started");
                this.treeTestPhase = 0;
            }
            return;
        }
        // Phase 2: wait for it to finish.
        if (!this.treeTestBrain.isMining()) {
            int standing = 0;
            for (int i = 0; i < 4; i++) {
                if (this.treeTestHandle.player().level()
                        .getBlockState(this.treeBase.above(i)).is(Blocks.OAK_LOG)) {
                    standing++;
                }
            }
            int logs = 0;
            var inventory = this.treeTestHandle.player().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (stack.is(net.minecraft.world.item.Items.OAK_LOG)) {
                    logs += stack.getCount();
                }
            }
            boolean pass = standing == 0 && logs >= 4;
            LOG.info("TREETEST ================ RESULT ================");
            LOG.info("TREETEST logs still standing: {} of 4", standing);
            LOG.info("TREETEST oak logs in inventory: {} (from 4 chopped)", logs);
            LOG.info("TREETEST ticks taken       : {}", this.treeTestTicks - this.treeTestStartTicks);
            LOG.info("TREETEST {}", pass ? "PASS" : "FAIL");
            LOG.info("TREETEST ======================================");
            this.treeTestPhase = 0;
        } else if (this.treeTestTicks - this.treeTestStartTicks > 1200) {
            LOG.error("TREETEST: FAIL - job did not finish in 1200 ticks (still {})",
                    this.treeTestBrain.currentAction());
            this.treeTestPhase = 0;
        }
    }

    private void report() {
        var handle = Agent.botManager() == null ? null : Agent.botManager().get("BrainBot");
        if (handle == null) {
            LOG.error("BRAINTEST: FAIL - bot vanished");
            this.finish();
            return;
        }

        var inventory = handle.player().getInventory();
        StringBuilder inv = new StringBuilder();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                inv.append(stack.getCount()).append("x ")
                   .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                   .append(" ");
            }
        }

        LOG.info("BRAINTEST ================ RESULT ================");
        LOG.info("BRAINTEST bot position : {}", handle.player().position());
        int logs = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.is(net.minecraft.world.item.Items.OAK_LOG)) {
                logs += stack.getCount();
            }
        }
        int standing = 0;
        for (int i = 0; i < 4; i++) {
            if (handle.player().level().getBlockState(this.treeBase.above(i)).is(Blocks.OAK_LOG)) {
                standing++;
            }
        }
        LOG.info("BRAINTEST tree logs standing: {} of 4 (0 = whole tree chopped)", standing);
        LOG.info("BRAINTEST oak logs carried : {} (picked up after chopping)", logs);
        LOG.info("BRAINTEST gold block at {} is now {}",
                this.goldPos, handle.player().level().getBlockState(this.goldPos));
        LOG.info("BRAINTEST inventory    : {}", inv.isEmpty() ? "(empty)" : inv.toString());
        var brains = Agent.brainManager();
        if (brains != null) {
            LOG.info("BRAINTEST brain state  :\n{}", brains.describe());
        }
        LOG.info("BRAINTEST ======================================");
    }

    /**
     * Kill the bot outright, the way a creeper or a fall would.
     *
     * <p>This is the case that has no vanilla equivalent for a synthetic player: death is normally a
     * conversation between the client and the server, and the client is what asks to respawn. With
     * no client, nothing ever asks, so the bot used to lie dead until {@code tickDeath()} deleted it
     * twenty ticks later.
     */
    private void killBot() {
        this.killed = true;
        var handle = Agent.botManager() == null ? null : Agent.botManager().get("BrainBot");
        if (handle == null) {
            LOG.error("DEATHTEST: FAIL - bot already gone before it could be killed");
            return;
        }

        this.preDeathPlayer = handle.player();
        LOG.info("DEATHTEST killing BotBrain body #{} at {} (health {})",
                this.preDeathPlayer.getId(), handle.player().blockPosition(), handle.player().getHealth());
        handle.player().kill();
        LOG.info("DEATHTEST after kill: dead={} removed={} deathTime={} brainAttached={}",
                handle.player().isDeadOrDying(), handle.player().isRemoved(), handle.player().deathTime,
                Agent.brainManager() != null
                        && Agent.brainManager().get(handle.player().getUUID()) != null);
    }

    /** Confirm the bot came back as a live player with its brain still attached. */
    private void verifyRespawn() {
        var handle = Agent.botManager() == null ? null : Agent.botManager().get("BrainBot");
        if (handle == null) {
            LOG.error("DEATHTEST: FAIL - the bot was dropped instead of respawned");
            return;
        }

        var bot = handle.player();
        var brain = Agent.brainManager() == null
                ? null : Agent.brainManager().get(bot.getUUID());
        boolean newBody = bot != this.preDeathPlayer;

        LOG.info("DEATHTEST ================ RESULT ================");
        LOG.info("DEATHTEST alive          : {}", !bot.isDeadOrDying() && !bot.isRemoved());
        LOG.info("DEATHTEST health         : {}/{}", bot.getHealth(), bot.getMaxHealth());
        LOG.info("DEATHTEST position       : {}", bot.blockPosition());
        LOG.info("DEATHTEST new body object: {} (old {} @hash {}, now {} @hash {})",
                newBody, this.preDeathPlayer.getName().getString(),
                System.identityHashCode(this.preDeathPlayer), bot.getName().getString(),
                System.identityHashCode(bot));
        LOG.info("DEATHTEST old body gone  : removed={} still-in-level={}",
                this.preDeathPlayer.isRemoved(),
                this.server.getPlayerList().getPlayer(bot.getUUID()) == this.preDeathPlayer);
        LOG.info("DEATHTEST still a bot    : {} in player list, movement driver present={}",
                this.server.getPlayerList().getPlayer(bot.getUUID()) == bot,
                handle.movement() != null);
        LOG.info("DEATHTEST brain attached : {}", brain != null);
        if (brain != null) {
            LOG.info("DEATHTEST brain drives   : {} (same object as the live player: {})",
                    brain.bot().getName().getString(), brain.bot() == bot);
        }
        boolean pass = !bot.isDeadOrDying() && !bot.isRemoved() && newBody
                && this.server.getPlayerList().getPlayer(bot.getUUID()) == bot
                && brain != null && brain.bot() == bot;
        LOG.info("DEATHTEST {}", pass ? "PASS" : "FAIL");
        LOG.info("DEATHTEST ======================================");
    }

    private void finish() {
        this.finished = true;
        try {
            if (Agent.botManager() != null) {
                Agent.botManager().remove("BrainBot");
            }
        } catch (Throwable t) {
            LOG.warn("cleanup issue", t);
        }
        LOG.info("BRAINTEST: done, stopping server");
        this.server.halt(false);
    }
}
