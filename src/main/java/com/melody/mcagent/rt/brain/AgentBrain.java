package com.melody.mcagent.rt.brain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.melody.mcagent.rt.action.ActionPolicy;
import com.melody.mcagent.rt.action.Actions;
import com.melody.mcagent.rt.action.Containers;
import com.melody.mcagent.rt.action.Crafting;
import com.melody.mcagent.rt.bot.MovementDriver;
import com.melody.mcagent.rt.llm.LlmClient;
import com.melody.mcagent.rt.perception.ObservationBuilder;
import com.melody.mcagent.rt.perception.Perception;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The decision loop: observe, ask the model, act, repeat.
 *
 * <p>Each bot has one brain and makes <b>one decision at a time</b>. The loop is asynchronous: the
 * HTTP call runs on a shared executor, and only the resulting world interaction is hopped back onto
 * the server thread. That separation is essential — a slow LLM must never stall the tick loop.
 *
 * <p>The brain holds no policy of its own. It offers the model the tools that
 * {@link ActionPolicy} permits, and the policy is re-checked when each action executes, so a model
 * that asks for something it was not offered is refused rather than trusted.
 */
public final class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/brain");

    /**
     * Shared across all bots: LLM calls are I/O-bound, so a few threads cover many bots.
     *
     * <p>Held in a field and created lazily rather than as a {@code final} constant, because
     * shutting it down is not reversible. A server that stops and reopens a world in the same JVM
     * (world reload, singleplayer re-entry, or a test harness) would otherwise find its executor
     * permanently terminated and every LLM call would fail with a {@code RejectedExecutionException}.
     */
    @Nullable
    private static volatile ExecutorService executor;

    private static synchronized ExecutorService executor() {
        ExecutorService current = executor;
        if (current == null || current.isShutdown() || current.isTerminated()) {
            current = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "mcagent-llm");
                thread.setDaemon(true);
                return thread;
            });
            executor = current;
        }
        return current;
    }

    /** Hard ceiling on how many observe→act cycles to keep, regardless of token count. */
    private static final int MAX_HISTORY_TURNS = 12;
    /**
     * Number of leading messages that are pinned and never compacted away.
     *
     * <p>A field rather than a constant because the prefix grows: the identity prompt, the ability
     * list, and any standing goal an operator has set. Keeping these is not a size optimisation but
     * a correctness one — a bot that forgets its instructions changes behaviour.
     */
    private int pinnedCount;
    /** Recent turns whose tool results are never collapsed: the model is still acting on these. */
    private static final int RECENT_TURNS_KEPT_INTACT = 3;
    /** Never compact below this many turns; the bot would lose its thread of action. */
    private static final int MIN_TURNS_KEPT = 2;
    /** Tool results longer than this are collapsed once they fall out of the recent window. */
    private static final int COLLAPSE_THRESHOLD_CHARS = 600;
    /** How much of a collapsed result to keep, so the model still sees what the call returned. */
    private static final int COLLAPSE_HEAD_CHARS = 200;
    /** Standard approximation for token counting when the provider has not told us. */
    private static final int CHARS_PER_TOKEN = 4;
    /** Default context budget in tokens, overridden from config. */
    private static final int DEFAULT_TOKEN_BUDGET = 12000;
    /** Reserve so our own output plus a margin fits alongside the prompt. */
    private static final int OUTPUT_RESERVE_TOKENS = 1024;
    /**
     * Hard cap on tool calls executed from a single model response, so a loop cannot run away.
     *
     * <p>Raised from 4 once calls became ordered and queueable: a plan like "walk to the tree, say
     * something on the way, chop three logs, come back" is five steps, and forcing it into four
     * round trips defeated the point of allowing a plan at all.
     */
    private static final int MAX_TOOL_CALLS_PER_TURN = 12;
    /** One compact plan can carry much more work than providers reliably emit as sibling calls. */
    private static final int MAX_PLAN_STEPS = 24;
    /** Bound speculative work so a confused model cannot fill an unbounded queue. */
    private static final int MAX_QUEUED_ACTIONS = 32;
    /** Start asking for the next batch while this many or fewer steps remain. */
    private static final int PLAN_LOW_WATERMARK = 6;
    /** Give the first action a few ticks to start, then overlap the next LLM request with it. */
    private static final int PLAN_PREFETCH_DELAY_TICKS = 5;
    /** Tools that make sense as deterministic steps inside a server-side plan. */
    private static final List<String> PLANNABLE_TOOLS = List.of(
            "observe", "goto", "look_at", "say", "eat", "stop", "mine", "hold", "discard", "pickup",
            "sleep", "wake", "place", "use", "open_container", "withdraw", "deposit",
            "craft", "craftable_now", "attack", "chat_command", "find_item", "find_uses",
            "remember", "recall", "forget", "dig_tunnel", "escape_up", "return_to_spawn");
    /** One escape call stays small enough to fit beside other queued work. */
    private static final int MAX_ESCAPE_STEPS = 8;
    /** One local tunnel macro covers useful ground while remaining bounded and interruptible. */
    private static final int MAX_TUNNEL_LENGTH = 24;
    /** Radius used when scanning for visible blocks. Scanning is O(r^3), so keep it tight. */
    private static final int DEFAULT_OBSERVE_RADIUS = 24;
    /** Longest chat line the bot will produce from a prose reply, in characters. */
    private static final int MAX_SPOKEN_CHARS = 400;
    /**
     * How far ahead one {@code goto} will plan, in blocks.
     *
     * <p>A route is planned as a whole rather than walked greedily, so this is also the limit on how
     * far the model may aim in a single instruction. Beyond it the request is refused with the
     * distance, which is far more useful than a search that expands its whole budget and then
     * reports a discouraging "no route".
     */
    private static final int GOTO_PLAN_RANGE = 128;
    /** How long a breaking job will try to walk into reach of one block before giving up on it. */
    private static final int MINE_APPROACH_TIMEOUT_TICKS = 100;
    /** How long the fallback collection phase of a breaking job will chase one drop before giving up. */
    private static final int MINE_COLLECT_TIMEOUT_TICKS = 200;
    /**
     * How long the bot will stand next to a drop it cannot pick up before giving up on it.
     *
     * <p>Vanilla collects an item from about a block away, so standing on it normally resolves within
     * a tick or two. A full pack is the case where it never does.
     */
    private static final int MINE_COLLECT_STANDING_TICKS = 60;
    /**
     * Pause between decisions while a long action is running.
     *
     * <p>Deliberately not infinite. The model is asked again mid-action — told what the bot is
     * currently doing — so it can decide to interrupt or to line up the next step. Asking every tick
     * would be absurd, and asking only when the action ends throws away the chance to react to
     * something that changed in the meantime.
     */
    private static final int BUSY_COOLDOWN_TICKS = 40;
    /**
     * Longest the bot may go without completing a decision before one is forced.
     *
     * <p>A watchdog, not a policy. The bot once went completely silent for ten minutes - standing in
     * flowing water, holding a movement target it could never reach - because a counter that only
     * measured "did the bot move at all" was kept reset by the current, and the brain declined to
     * think while a walk was in progress. Two independent bugs, either of which alone was survivable.
     * This makes that whole class of failure self-healing: however the bot gets wedged, it starts
     * thinking again within about a minute.
     */
    private static final int DECISION_WATCHDOG_TICKS = 1200;
    /** Seconds spent in one block before the bot is told it may be stuck. */
    private static final int MOTIONLESS_TICKS_TO_REPORT = 100;
    /** Largest radius a single mining instruction may cover. */
    private static final int MAX_MINE_RADIUS = 16;
    /** A fight cannot own the bot forever if the target cannot be reached. */
    private static final int COMBAT_TIMEOUT_TICKS = 600;
    /** Stop chasing after this long without seeing the target. */
    private static final int COMBAT_LOST_SIGHT_TICKS = 100;
    /** Maximum distance for one combat pursuit. */
    private static final int COMBAT_PATH_RANGE = 48;

    /**
     * What the bot is doing right now, and what became of steps it started earlier.
     *
     * <p>Started actions report back immediately ("walking to ...", "started mining ..."), so this
     * carries what happened <em>after</em> that: arrival, completion, or a failure. It is rendered
     * into the next observation, which is the only place a result can go once its own tool call has
     * already been answered.
     */
    private final java.util.ArrayDeque<String> actionReports = new java.util.ArrayDeque<>();

    /**
     * The player this brain drives.
     *
     * <p>Not final: dying and respawning replaces the {@code ServerPlayer} object outright (vanilla
     * {@code PlayerList.respawn} constructs a new entity), so the brain has to be re-pointed at the
     * live one or it would keep acting on a discarded corpse.
     */
    private volatile ServerPlayer bot;

    /** Not final: a config change swaps the endpoint without disturbing this bot's conversation. */
    private volatile LlmClient client;
    private volatile ActionPolicy policy;
    private final List<LlmClient.Message> history = new ArrayList<>();

    private final AtomicBoolean thinking = new AtomicBoolean(false);
    private boolean initialised;

    /** The block-breaking job in progress, if any. */
    @Nullable
    private MineJob mineJob;

    /** A target the bot is pursuing and attacking until it dies or gets away. */
    @Nullable
    private CombatJob combatJob;

    /**
     * Steps of the current plan that are waiting for the bot to finish what it is doing.
     *
     * <p>This is what makes a multi-step instruction stick together. Without it, "walk to the tree
     * and then chop it" costs two round trips to the model with the bot standing still in between —
     * several seconds of a player watching a bot do nothing. With it, the chopping starts on the tick
     * the bot arrives.
     *
     * <p>The queue intentionally spans model turns. A queued provider tool call is answered
     * immediately with "queued", keeping the transcript valid; its eventual outcome is reported in
     * the next observation. That lets a lookahead decision extend the queue while older work runs.
     */
    private final java.util.ArrayDeque<QueuedCall> queue = new java.util.ArrayDeque<>();
    /** A queued goto currently owns movement; if it gives up, its dependent tail is invalid. */
    private boolean abortQueueIfMovementFails;
    /** Emergency tools preserve the remaining tail when they are deliberately nested in a plan. */
    private boolean executingPlanStep;

    /** One waiting step and whether a failure invalidates everything planned after it. */
    private static final class QueuedCall {
        final LlmClient.ToolCall call;
        final int index;
        final boolean abortPlanOnFailure;

        QueuedCall(LlmClient.ToolCall call, int index) {
            this(call, index, false);
        }

        QueuedCall(LlmClient.ToolCall call, int index, boolean abortPlanOnFailure) {
            this.call = call;
            this.index = index;
            this.abortPlanOnFailure = abortPlanOnFailure;
        }
    }

    /** One newly excavated stair tread and the blocks that must be removed before entering it. */
    private record EscapeStep(BlockPos feet, List<BlockPos> clear) {
    }

    /** A safe, monotonically rising staircase through the terrain. */
    private record EscapeRoute(Direction direction, List<EscapeStep> steps,
                               boolean reachesSurface, int cost) {
    }

    /** One safe tunnel position and the blocks which must be cleared before entering it. */
    private record TunnelStep(BlockPos feet, List<BlockPos> clear) {
    }

    /** One durable mine entrance and its latest reached working face. */
    private record MineRoute(String dimension, BlockPos entrance, BlockPos face, Direction direction) {
    }

    private static final String MINE_ROUTE_STATE = "mine_route_v1";

    /**
     * Results for the calls of the turn currently being resolved.
     *
     * <p>Held by index and flushed strictly from the front, because the provider requires every tool
     * call to be answered, and answering them out of order is rejected by stricter gateways. A slot
     * left null means that step has not run yet, and everything after it waits — which is exactly
     * the "these actions happen in this order" guarantee the model is promised.
     */
    @Nullable
    private List<LlmClient.ToolCall> turnCalls;
    @Nullable
    private String[] turnResults;
    private int nextResultToFlush;

    /** Ticks to wait before the next decision, so the bot does not spam the model. */
    private int cooldownTicks;

    /** Working budget for the transcript, in tokens. */
    private int tokenBudget = DEFAULT_TOKEN_BUDGET;
    /**
     * How far this bot looks when it observes.
     *
     * <p>A field rather than a constant because the config used to expose an {@code observeRadius}
     * that nothing ever read: an operator could change it, watch it be written to the file, and get
     * no effect at all. It is now actually plumbed through.
     */
    private volatile int observeRadius = DEFAULT_OBSERVE_RADIUS;
    /** Prompt tokens the provider reported for the most recent call (0 if it reported none). */
    private int lastPromptTokens;
    /** A standing objective that is pinned in the prompt and survives compaction. */
    @Nullable
    private String standingGoal;

    /**
     * When paused, this brain makes no decisions and performs no actions.
     *
     * <p>Deliberately distinct from removing the bot: a paused bot is still a real player in the
     * world — visible, physical, able to receive chat and damage — it simply stops acting. That is
     * what an operator wants when they need to freeze one bot mid-task to investigate something.
     */
    private volatile boolean paused;
    /** How many turns the provider actually reported usage for (0 = never). */
    private int reportedUsageTurns;
    /** Session totals reported by the provider; these survive config rebinds with this brain. */
    private long totalPromptTokens;
    private long totalCompletionTokens;
    private long totalTokens;
    private long totalCachedPromptTokens;
    private long totalUncachedPromptTokens;
    /** Calls for which the provider included cache-token details (0 means hit rate is unknown). */
    private int cacheReportedTurns;
    /** True when any fresh audible chat triggered the current decision. */
    private boolean respondPromptly;
    /** True only when fresh chat explicitly named this bot. */
    private boolean directlyAddressed;
    /** At most one public line may be emitted from a single decision. */
    private boolean spokenThisDecision;

    /**
     * How long the bot stays quiet when nobody has spoken to it, in ticks.
     *
     * <p>Two minutes. Genuine direct questions bypass this; autonomous progress narration does not.
     */
    private static final int UNPROMPTED_CHAT_COOLDOWN = 2400;

    /** Game time of the bot's last chat message, and the message itself. */
    private long lastSpokenTick = -UNPROMPTED_CHAT_COOLDOWN;
    @Nullable
    private String lastSpokenLine;

    /** Count of completed decision turns, for diagnostics and tests. */
    private final java.util.concurrent.atomic.AtomicInteger turnsCompleted =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Learned ratio of real tokens to our estimate; <=0 means not yet calibrated. */
    private double calibration = -1.0;
    /** Cached size of the tool schema block; -1 means not computed yet. */
    private int cachedToolSchemaTokens = -1;
    /** Durable notes this bot has written for itself; resolved lazily. */
    @Nullable
    private com.melody.mcagent.rt.memory.BotMemory memory;
    /** Whether the bot was walking last tick, so arrival can be reported. */
    private boolean wasMoving;
    /** A bed the bot is walking to in order to sleep in it. */
    @Nullable
    private BlockPos pendingSleep;
    /** Ticks since the last completed decision, for the liveness watchdog. */
    private int ticksSinceDecision;
    /** Ticks the bot has spent in the same block, so it can be told when it is stuck. */
    private int ticksMotionless;
    private BlockPos lastSeenAt = BlockPos.ZERO;
    /** Where the most recent observation was taken, for the reuse check. */
    private net.minecraft.core.BlockPos lastObservationPos = net.minecraft.core.BlockPos.ZERO;

    /** Marks an observation result, so we can tell which tool result came from {@code observe}. */
    private static final String OBSERVATION_HEADER = "=== YOUR STATE ===";

    /**
     * A block-breaking job: one block, or a connected region of the same kind of block.
     *
     * <p>Chopping a tree or mining a vein one block per model turn is absurd — a birch tree is seven
     * logs, so it cost seven round trips and the bot wandered off between them. A range turns that
     * into one instruction, which is what a player means by "chop that tree".
     *
     * <p>The region is discovered as it is broken rather than computed up front, because breaking a
     * block is what reveals the next one. A flood fill over one block type follows a trunk upward and
     * a vein outward on its own, without us knowing anything about either.
     *
     * <p>Each block's drops go straight into the pack as it falls
     * ({@link com.melody.mcagent.rt.action.Actions#collectBreakDrops}), because walking between the
     * items of a tree it had just felled was taking longer than felling it. When the breaking is
     * done the job still checks the ground for anything the pack refused: a player does not leave
     * their own logs lying on the grass, and a bot that does looks broken.
     */
    private static final class MineJob {
        /** Where the job was asked to start; the radius is measured from here. */
        final BlockPos origin;
        /** How far from the origin the job may spread. 0 means the single requested block. */
        final int radius;
        /** The block type being removed, or null when only the one block was asked for. */
        @Nullable
        final net.minecraft.world.level.block.Block type;
        /**
         * The tick the job started, or -1 for a job that only collects.
         *
         * <p>Collection uses it to tell this job's own leftovers from everything else on the ground:
         * an item older than the job was already lying there when it began. A job built by
         * {@code pickup} has no such thing as "its own" items, so it takes the -1 and skips the test.
         */
        final long startTick;

        final java.util.Set<BlockPos> known = new java.util.HashSet<>();
        final java.util.ArrayDeque<BlockPos> pending = new java.util.ArrayDeque<>();

        /** The block currently being broken, if any. */
        @Nullable
        BlockPos current;
        Direction face = Direction.UP;
        int ticksRemaining;
        /** Blocks actually broken so far. */
        int broken;
        /** Ticks spent failing to reach the next block, so a job cannot hang forever. */
        int approachTicks;
        /** Targets skipped because no reachable standing position could be found. */
        int unreachable;

        /** True once breaking is finished and the job is collecting what it dropped. */
        boolean collecting;
        /** Where the bot is currently walking to pick something up. */
        @Nullable
        Vec3 collectTarget;
        int collectTicks;
        int collected;

        MineJob(BlockPos origin, int radius, @Nullable net.minecraft.world.level.block.Block type,
                long startTick) {
            this.origin = origin;
            this.radius = radius;
            this.type = type;
            this.startTick = startTick;
            this.known.add(origin);
            this.pending.add(origin);
        }
    }

    /**
     * A real fight is a timed activity, not one isolated mouse click per LLM round trip.
     * The UUID is stable while the entity moves; only the last visible position is remembered, so
     * losing sight does not turn combat into an x-ray tracker through walls.
     */
    private static final class CombatJob {
        final UUID targetId;
        final String label;
        @Nullable BlockPos lastSeen;
        @Nullable BlockPos plannedFor;
        int ticks;
        int unseenTicks;
        int repathCooldown;
        int blockedPlans;
        int hits;

        CombatJob(Entity target) {
            this.targetId = target.getUUID();
            this.label = Perception.describe(target);
            this.lastSeen = target.blockPosition().immutable();
        }
    }

    public AgentBrain(ServerPlayer bot, LlmClient client, ActionPolicy policy) {
        this.bot = bot;
        this.client = client;
        this.policy = policy;
    }

    /** Set the working token budget for this bot's transcript. */
    public void setTokenBudget(int tokens) {
        this.tokenBudget = Math.max(1000, tokens);
    }

    /** Set how far this bot's perception reaches. Bounded to the range the scanner supports. */
    public void setObserveRadius(int radius) {
        this.observeRadius = Math.max(4, Math.min(48, radius));
    }

    /** How far this bot currently looks. */
    public int observeRadius() {
        return this.observeRadius;
    }

    /**
     * Start a mining job exactly as the {@code mine} tool would.
     *
     * <p>Exists so the mining mechanics can be verified without a language model in the loop. Whether
     * a radius job fells a whole tree and then collects the drops is a deterministic question, and
     * putting a model in the way only adds noise: the first attempt at testing this produced a run
     * where the model never got round to the tree at all, and the test reported "0 of 4 logs chopped"
     * for reasons that had nothing to do with mining.
     */
    public String mineAsTool(BlockPos pos, int radius) {
        return this.startMine(pos, Math.max(0, Math.min(MAX_MINE_RADIUS, radius)));
    }

    /** Start combat exactly as the tool would, for deterministic smoke tests. */
    public String attackAsTool(Entity target) {
        if (!this.policy.canAttack()) {
            return "failed: you are not allowed to attack";
        }
        return this.startCombat(target);
    }

    /** True while a mining or collection job is running. For tests and diagnostics. */
    public boolean isMining() {
        return this.mineJob != null;
    }

    /** True while the bot is pursuing or striking a combat target. */
    public boolean isFighting() {
        return this.combatJob != null;
    }

    /** What the bot is busy with right now, or "idle". */
    public String currentAction() {
        return this.describeCurrentAction();
    }

    /**
     * This bot's durable memory, loaded on first use.
     *
     * <p>Resolved lazily rather than in the constructor because a brain is built while a bot is
     * still joining, and memory needs the server's world path to exist.
     */
    private com.melody.mcagent.rt.memory.BotMemory memory() {
        com.melody.mcagent.rt.memory.BotMemory current = this.memory;
        if (current == null) {
            current = com.melody.mcagent.rt.memory.BotMemory.of(
                    this.bot.server, this.bot.getName().getString());
            this.memory = current;
        }
        return current;
    }

    /** The pinned identity message, which carries the bot's remembered notes. */
    private LlmClient.Message identityMessage() {
        String base = ObservationBuilder.systemPrompt(this.bot);
        String remembered = this.memory().render();
        return LlmClient.Message.system(remembered.isEmpty() ? base : base + "\n" + remembered);
    }

    /**
     * Rebuild the pinned identity message in place.
     *
     * <p>The memory block lives inside the pin at index 0 rather than in its own message, so it can
     * never be compacted away — and rewriting that one entry is enough to make a newly stored fact
     * visible on the very next request without disturbing the conversation.
     */
    private void refreshIdentity() {
        if (this.initialised && !this.history.isEmpty()
                && "system".equals(this.history.get(0).role())) {
            this.history.set(0, this.identityMessage());
        }
    }

    /**
     * Point this brain at a different client, keeping the conversation and standing goal.
     *
     * <p>Used when the LLM settings are reloaded. Rebuilding the brain instead would discard the
     * bot's context, which is usually the opposite of what an operator wants when they are fixing a
     * typo in an API key.
     *
     * <p>An in-flight request against the old client is allowed to finish; its result is still
     * valid, and cancelling it would just lose a turn.
     */
    public void rebind(LlmClient newClient) {
        this.client = newClient;
    }

    /** The client this brain is currently using. */
    public LlmClient client() {
        return this.client;
    }

    public ServerPlayer bot() {
        return this.bot;
    }

    /**
     * Point this brain at the player's new body after a respawn.
     *
     * <p>Respawn is not a mutation in vanilla: {@code PlayerList.respawn} builds a brand new
     * {@code ServerPlayer} and the old one is discarded. The brain's transcript, standing goal and
     * token budget all still apply, so only the entity reference changes — the bot keeps its
     * conversation across death, which is what a player would expect.
     */
    public void rebindPlayer(ServerPlayer newPlayer) {
        ServerPlayer oldBody = this.bot;
        boolean died = oldBody.isDeadOrDying() || oldBody.isRemoved()
                || oldBody.getHealth() <= 0.0F;
        BlockPos whereItHappened = oldBody.blockPosition();

        this.bot = newPlayer;
        // Anything aimed at the old body is meaningless now, and any queued step was chosen for a
        // situation that ended when the bot died.
        this.abandonPlan("the bot died before this step ran");
        this.mineJob = null;
        this.combatJob = null;
        this.wasMoving = false;
        this.pendingSleep = null;
        this.respondPromptly = false;
        this.directlyAddressed = false;
        this.spokenThisDecision = false;
        this.lastObservationPos = newPlayer.blockPosition();

        if (died) {
            // Say it out loud, in the transcript.
            //
            // Without this the bot simply finds itself somewhere else with an empty inventory and no
            // explanation. It is not that it forgets dying - it never knew: the history runs straight
            // on, and the only clue is a position that no longer matches what it was doing. Observed
            // in play as a bot that carried on issuing instructions for a plan it had died in the
            // middle of, and as a player asking why it did not seem to realise it had been killed.
            this.history.add(LlmClient.Message.user(
                    "YOU DIED at " + whereItHappened.toShortString() + " and have respawned at "
                    + newPlayer.blockPosition().toShortString() + ".\n"
                    + "You dropped everything you were carrying where you died, and whatever you were "
                    + "doing is over - none of it is still running.\n"
                    + (newPlayer.getRespawnPosition() != null
                            ? "You respawned at your bed, which is still your respawn point.\n"
                            : "You have no bed set, so you came back to world spawn. Sleeping in a bed "
                                    + "would fix that.\n")
                    + "Decide what to do now. Getting your things back means walking to "
                    + whereItHappened.toShortString() + "."));
            this.actionReports.clear();
            LOG.info("Bot {} was told it died at {} and respawned at {}",
                    newPlayer.getName().getString(), whereItHappened.toShortString(),
                    newPlayer.blockPosition().toShortString());
        }
    }

    /**
     * Give the bot an immediate objective, delivered as the next user message.
     *
     * <p>Used by tests, and by the {@code /mcagent goal} command, to direct a bot without waiting
     * for it to choose something to do.
     */
    public void primeGoal(String goal) {
        this.history.add(LlmClient.Message.user("YOUR TASK: " + goal));
        this.cooldownTicks = 0;
    }

    /**
     * Set a standing objective that survives compaction.
     *
     * <p>A goal delivered as an ordinary user message would eventually be compacted away, and a bot
     * that forgets what it was asked to do is worse than one that never started. Pinning it beside
     * the identity prompt keeps long-running tasks coherent, which is the same reasoning that keeps
     * the system prompt out of compaction.
     */
    public void setStandingGoal(@Nullable String goal) {
        this.standingGoal = goal;

        // Rebuild the pinned prefix: identity, abilities, then the goal if one is set.
        while (this.history.size() > 2 && this.pinnedCount > 2) {
            this.history.remove(this.pinnedCount - 1);
            this.pinnedCount--;
        }
        if (goal != null && !goal.isBlank()) {
            this.history.add(LlmClient.Message.system("YOUR STANDING OBJECTIVE: " + goal));
            this.pinnedCount = 3;
        } else {
            this.pinnedCount = 2;
        }
        this.cooldownTicks = 0;
    }

    @Nullable
    public String standingGoal() {
        return this.standingGoal;
    }

    public boolean isThinking() {
        return this.thinking.get();
    }

    public ActionPolicy policy() {
        return this.policy;
    }

    /** Replace the permission set in force for this bot, e.g. after a config reload. */
    public void setPolicy(ActionPolicy newPolicy) {
        this.policy = newPolicy;
    }

    /** Whether this brain is currently frozen. */
    public boolean isPaused() {
        return this.paused;
    }

    /** How many decision turns this brain has completed. Used for diagnostics and tests. */
    public int turnsCompleted() {
        return this.turnsCompleted.get();
    }

    /**
     * Freeze or unfreeze this brain.
     *
     * <p>Pausing also stops the bot where it stands and abandons any break in progress, so "paused"
     * means genuinely stopped rather than "still walking to a place it decided on earlier". The
     * standing goal is kept, so resuming continues the task instead of losing it.
     *
     * <p>An LLM request already in flight cannot be cancelled, so its result is discarded on
     * arrival (see the callback in {@link #startDecision()}).
     *
     * <p>Logged at INFO with the actor, because "who froze this bot, and when" is the first
     * question asked of a bot that has gone quiet — and until this was logged the answer was
     * nowhere. A bot paused for fifteen minutes produced no other trace at all: not thinking,
     * not mining, no error, no watchdog.
     *
     * @param actor who or what is asking, for the log: a command source name, or a test
     */
    public void setPaused(boolean paused, String actor) {
        boolean wasPaused = this.paused;
        this.paused = paused;
        if (paused) {
            this.abandonPlan("the bot was paused");
            this.abandonCurrentAction();
        } else {
            // Decide promptly on resume rather than waiting out a stale cooldown.
            this.cooldownTicks = 0;
        }

        String name = this.bot.getName().getString();
        if (wasPaused == paused) {
            LOG.info("Bot {} is already {} (asked by {})", name, paused ? "paused" : "running", actor);
        } else if (paused) {
            LOG.info("Bot {} PAUSED by {} - it makes no decisions and performs no actions until resumed",
                    name, actor);
        } else {
            LOG.info("Bot {} RESUMED by {}", name, actor);
        }
    }

    /** Stop moving and abandon any in-progress break. */
    private void abandonCurrentAction() {
        var manager = com.melody.mcagent.rt.Agent.botManager();
        if (manager != null) {
            var handle = manager.get(this.bot.getName().getString());
            if (handle != null) {
                handle.movement().clear();
            }
        }
        if (this.mineJob != null) {
            BlockPos current = this.mineJob.current;
            if (current != null) {
                try {
                    Actions.abortBreak(this.bot, current, this.mineJob.face);
                } catch (Throwable t) {
                    LOG.debug("Could not abort break while interrupting", t);
                }
            }
            if (this.mineJob.broken > 0) {
                this.actionReports.addLast("you stopped part-way through; " + this.mineJob.broken
                        + " block(s) had been broken");
            }
            this.mineJob = null;
        }
        if (this.combatJob != null) {
            if (this.combatJob.hits > 0) {
                this.actionReports.addLast("you stopped fighting " + this.combatJob.label
                        + " after landing " + this.combatJob.hits + " hit(s)");
            }
            this.combatJob = null;
        }
        this.wasMoving = false;
    }

    /**
     * Drop stale physical work before an emergency relocation or self-rescue.
     *
     * <p>This deliberately does not call {@link #abandonPlan(String)}: an emergency tool can be one
     * call inside the turn currently being resolved, and cancelling that turn would erase its own
     * tool result. A standalone emergency discards old queued actions. When the model deliberately
     * nests it in {@code plan}, the dependent tail is retained and resumes from the new situation.
     */
    private void prepareEmergencyAction() {
        this.abandonCurrentAction();
        // A standalone emergency call supersedes stale work. Inside plan(...) the model explicitly
        // made the later steps depend on this relocation/rescue, so keep that tail. In particular,
        // escape_up expands into ordinary mine/goto calls which must run before the retained tail.
        if (!this.executingPlanStep) {
            this.queue.clear();
        }
        this.abortQueueIfMovementFails = false;
        this.pendingSleep = null;
        this.wasMoving = false;
        this.cooldownTicks = 0;
    }

    /** Return immediately to the vanilla respawn location, keeping inventory and experience. */
    public String returnToSpawnNow() {
        this.prepareEmergencyAction();
        Actions.Result result = Actions.returnToRespawn(this.bot);
        if (!result.success()) {
            return "failed: " + result.message();
        }
        this.lastSeenAt = this.bot.blockPosition().immutable();
        this.lastObservationPos = this.lastSeenAt;
        this.ticksMotionless = 0;
        this.actionReports.addLast(result.message());
        LOG.info("Bot {} {}", this.bot.getName().getString(), result.message());
        return result.message();
    }

    /**
     * Dig a real staircase with jump clearance and walk up it one tread at a time.
     *
     * <p>The route is chosen from the four cardinal directions. Every landing must retain a solid,
     * dry, non-hazardous floor; fluids, falling blocks, block entities and unbreakable blocks reject
     * a direction. Breaking and walking are queued as ordinary actions, so tool speed, block drops,
     * physics and protection hooks remain real. One call climbs at most eight blocks; a deep mine
     * can call it again from the new landing.
     */
    public String escapeUp(String requestedItem) {
        if (!this.policy.canBreakBlocks()) {
            return "failed: you are not allowed to break blocks, so you cannot dig an escape stair";
        }
        this.prepareEmergencyAction();

        Actions.Result equip = Actions.equipIfRequested(this.bot, requestedItem);
        if (equip != null) {
            return "failed: " + equip.message();
        }
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return "failed: your current dimension cannot be excavated";
        }

        EscapeRoute route = this.findEscapeRoute(level, this.bot.blockPosition());
        if (route == null || route.steps().isEmpty()) {
            return "failed: no safe rising staircase can be dug from here. Fluids, a gap under the "
                    + "next tread, falling blocks, protected blocks or unbreakable terrain block "
                    + "every direction; use return_to_spawn.";
        }

        int sequence = 0;
        int blocks = 0;
        List<QueuedCall> excavation = new ArrayList<>();
        for (EscapeStep step : route.steps()) {
            for (BlockPos clear : step.clear()) {
                JsonObject mine = new JsonObject();
                mine.addProperty("x", clear.getX());
                mine.addProperty("y", clear.getY());
                mine.addProperty("z", clear.getZ());
                excavation.add(new QueuedCall(new LlmClient.ToolCall(
                        "escape_mine_" + (++sequence), "mine", mine), -1, true));
                blocks++;
            }

            JsonObject walk = new JsonObject();
            walk.addProperty("x", step.feet().getX());
            walk.addProperty("y", step.feet().getY());
            walk.addProperty("z", step.feet().getZ());
            excavation.add(new QueuedCall(new LlmClient.ToolCall(
                    "escape_walk_" + (++sequence), "goto", walk), -1, true));
        }

        if (this.executingPlanStep) {
            // A queued plan already has its later steps in deque order. Prepend this macro in
            // reverse so its real mining/walking completes before the plan continues.
            for (int i = excavation.size() - 1; i >= 0; i--) {
                this.queue.addFirst(excavation.get(i));
            }
        } else {
            excavation.forEach(this.queue::addLast);
        }

        String outcome = "digging a " + route.steps().size() + "-step staircase "
                + route.direction().getName() + " and walking up it; " + blocks
                + " block(s) will be mined with normal timing";
        if (route.reachesSurface()) {
            outcome += ". The final tread reaches the surface";
        } else {
            outcome += ". This is the safest partial climb available; call escape_up again after it finishes";
        }
        LOG.info("Bot {} planned emergency escape from {}: {}", this.bot.getName().getString(),
                this.bot.blockPosition().toShortString(), outcome);
        return outcome;
    }

    /**
     * Excavate and traverse a straight two-block-high tunnel without asking the model to invent
     * every intermediate coordinate.
     *
     * <p>{@code down} drops one block per horizontal block, producing a walkable descending
     * staircase. {@code level} keeps a constant Y for branch mining. Every block is still broken
     * through the ordinary timed mine action and every landing is reached with vanilla movement;
     * the macro only performs repetitive coordinate bookkeeping a player does instinctively.
     */
    public String digTunnel(String directionName, String modeName, int requestedLength,
                            String requestedItem) {
        return this.digTunnel(directionName, modeName, requestedLength, requestedItem, false);
    }

    private String digTunnel(String directionName, String modeName, int requestedLength,
                             String requestedItem, boolean newSite) {
        if (!this.policy.canBreakBlocks()) {
            return "failed: you are not allowed to break blocks";
        }
        Direction direction = parseHorizontalDirection(directionName);
        // Models occasionally put "down" in both fields. Mode already carries the vertical intent;
        // use the direction the player is facing instead of failing or asking for another LLM call.
        if (direction == null && "down".equalsIgnoreCase(directionName)
                && "down".equalsIgnoreCase(modeName)) {
            direction = this.bot.getDirection();
        }
        if (direction == null) {
            return "failed: direction must be north, south, east or west";
        }
        String mode = modeName.toLowerCase(java.util.Locale.ROOT);
        if (!"level".equals(mode) && !"down".equals(mode)) {
            return "failed: mode must be level or down";
        }
        int length = Math.max(1, Math.min(MAX_TUNNEL_LENGTH, requestedLength));

        Actions.Result equip = Actions.equipForMining(this.bot,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), requestedItem);
        if (equip != null) {
            return "failed: " + equip.message();
        }
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return "failed: your current dimension cannot be excavated";
        }

        BlockPos start = this.bot.blockPosition();
        MineRoute saved = this.loadMineRoute();
        String dimension = level.dimension().location().toString();
        if (!newSite && saved != null && saved.dimension().equals(dimension)
                && start.distSqr(saved.face()) > 64.0D) {
            List<QueuedCall> resume = new ArrayList<>();
            int sequence = 0;
            if (start.distSqr(saved.entrance()) > 9.0D) {
                resume.add(new QueuedCall(new LlmClient.ToolCall(
                        "plan_step_mine_resume_" + (++sequence), "goto",
                        positionArgs(saved.entrance())), -1, true));
            }
            resume.add(new QueuedCall(new LlmClient.ToolCall(
                    "plan_step_mine_resume_" + (++sequence), "goto",
                    positionArgs(saved.face())), -1, true));
            JsonObject continueDigging = new JsonObject();
            continueDigging.addProperty("direction", directionName);
            continueDigging.addProperty("mode", modeName);
            continueDigging.addProperty("length", length);
            if (requestedItem != null && !requestedItem.isBlank()) {
                continueDigging.addProperty("item", requestedItem);
            }
            resume.add(new QueuedCall(new LlmClient.ToolCall(
                    "plan_step_mine_resume_" + (++sequence), "dig_tunnel", continueDigging),
                    -1, true));
            this.prependOrAppendMacro(resume);
            return "returning through the established mine entrance at "
                    + saved.entrance().toShortString() + " to its working face at "
                    + saved.face().toShortString() + ", then continuing the existing tunnel; "
                    + "no new surface hole will be opened";
        }
        BlockPos entrance = saved == null || newSite || !saved.dimension().equals(dimension)
                ? start.immutable() : saved.entrance();
        if (saved == null || newSite || !saved.dimension().equals(dimension)) {
            this.saveMineRoute(new MineRoute(dimension, entrance, start.immutable(), direction));
        }
        List<TunnelStep> route = new ArrayList<>();
        int blocks = 0;
        String stoppedBy = null;
        for (int index = 1; index <= length; index++) {
            BlockPos feet = start.relative(direction, index);
            if ("down".equals(mode)) {
                feet = feet.below(index);
            }
            if (feet.getY() <= level.getMinBuildHeight()
                    || !level.getWorldBorder().isWithinBounds(feet)) {
                stoppedBy = "the world boundary";
                break;
            }

            BlockPos floor = feet.below();
            BlockState floorState = level.getBlockState(floor);
            if (floorState.getCollisionShape(level, floor).isEmpty()
                    || !floorState.getFluidState().isEmpty()
                    || floorState.getBlock() instanceof FallingBlock
                    || isEscapeHazard(floorState)) {
                stoppedBy = "an unsafe or missing floor at " + floor.toShortString();
                break;
            }

            // A level tunnel needs feet+head. Descending one full block while moving horizontally
            // also needs the sloped-ceiling block above the destination head: until gravity has
            // lowered the player, its 1.8-block body still intersects that third block. The exact
            // same collision is why upward escape stairs clear three blocks.
            List<BlockPos> clear = new ArrayList<>("down".equals(mode) ? 3 : 2);
            boolean safe = true;
            List<BlockPos> clearance = "down".equals(mode)
                    ? List.of(feet, feet.above(), feet.above(2))
                    : List.of(feet, feet.above());
            for (BlockPos pos : clearance) {
                if (com.melody.mcagent.rt.path.PathFinder.canPass(level, pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()
                        || state.getBlock() instanceof FallingBlock
                        || level.getBlockEntity(pos) != null
                        || Actions.ticksToBreak(this.bot, pos) == Integer.MAX_VALUE) {
                    safe = false;
                    stoppedBy = "fluid, falling, protected or unbreakable terrain at "
                            + pos.toShortString();
                    break;
                }
                clear.add(pos.immutable());
                blocks++;
            }
            if (!safe) {
                break;
            }
            route.add(new TunnelStep(feet.immutable(), List.copyOf(clear)));
        }

        if (route.isEmpty()) {
            return "failed: no safe tunnel step can be dug " + direction.getName()
                    + (stoppedBy == null ? "" : " because of " + stoppedBy);
        }

        List<QueuedCall> excavation = new ArrayList<>();
        int sequence = 0;
        for (TunnelStep step : route) {
            for (BlockPos clear : step.clear()) {
                JsonObject mine = new JsonObject();
                mine.addProperty("x", clear.getX());
                mine.addProperty("y", clear.getY());
                mine.addProperty("z", clear.getZ());
                excavation.add(new QueuedCall(new LlmClient.ToolCall(
                        "tunnel_mine_" + (++sequence), "mine", mine), -1, true));
            }
            JsonObject walk = new JsonObject();
            walk.addProperty("x", step.feet().getX());
            walk.addProperty("y", step.feet().getY());
            walk.addProperty("z", step.feet().getZ());
            excavation.add(new QueuedCall(new LlmClient.ToolCall(
                    "tunnel_walk_" + (++sequence), "goto", walk), -1, true));
        }
        BlockPos finalFace = route.get(route.size() - 1).feet();
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("dimension", dimension);
        checkpoint.addProperty("entrance_x", entrance.getX());
        checkpoint.addProperty("entrance_y", entrance.getY());
        checkpoint.addProperty("entrance_z", entrance.getZ());
        checkpoint.addProperty("face_x", finalFace.getX());
        checkpoint.addProperty("face_y", finalFace.getY());
        checkpoint.addProperty("face_z", finalFace.getZ());
        checkpoint.addProperty("direction", direction.getName());
        excavation.add(new QueuedCall(new LlmClient.ToolCall(
                "mine_checkpoint_" + (++sequence), "mine_checkpoint", checkpoint), -1, true));

        this.prependOrAppendMacro(excavation);

        String outcome = "digging and walking through a " + route.size() + "-block " + mode
                + " tunnel " + direction.getName() + "; " + blocks
                + " block(s) will be mined with normal timing";
        if (route.size() < length && stoppedBy != null) {
            outcome += ". Stopping safely before " + stoppedBy;
        }
        LOG.info("Bot {} planned local tunnel from {}: {}", this.bot.getName().getString(),
                start.toShortString(), outcome);
        return outcome;
    }

    private void prependOrAppendMacro(List<QueuedCall> calls) {
        if (this.executingPlanStep) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                this.queue.addFirst(calls.get(i));
            }
        } else {
            calls.forEach(this.queue::addLast);
        }
    }

    private static JsonObject positionArgs(BlockPos pos) {
        JsonObject args = new JsonObject();
        args.addProperty("x", pos.getX());
        args.addProperty("y", pos.getY());
        args.addProperty("z", pos.getZ());
        return args;
    }

    @Nullable
    private MineRoute loadMineRoute() {
        String encoded = this.memory().systemValue(MINE_ROUTE_STATE);
        if (encoded == null) {
            return null;
        }
        try {
            String[] part = encoded.split("\\|", -1);
            if (part.length != 8) {
                return null;
            }
            Direction direction = parseHorizontalDirection(part[7]);
            if (direction == null) {
                return null;
            }
            return new MineRoute(part[0],
                    new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]),
                            Integer.parseInt(part[3])),
                    new BlockPos(Integer.parseInt(part[4]), Integer.parseInt(part[5]),
                            Integer.parseInt(part[6])), direction);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void saveMineRoute(MineRoute route) {
        this.memory().putSystemValue(MINE_ROUTE_STATE,
                route.dimension() + "|" + route.entrance().getX() + "|" + route.entrance().getY()
                + "|" + route.entrance().getZ() + "|" + route.face().getX() + "|"
                + route.face().getY() + "|" + route.face().getZ() + "|"
                + route.direction().getName());
    }

    @Nullable
    private EscapeRoute findEscapeRoute(net.minecraft.server.level.ServerLevel level, BlockPos start) {
        EscapeRoute best = null;
        int bestScore = Integer.MAX_VALUE;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            List<EscapeStep> steps = new ArrayList<>();
            int cost = 0;
            boolean reachesSurface = false;

            for (int rise = 1; rise <= MAX_ESCAPE_STEPS; rise++) {
                BlockPos feet = start.relative(direction, rise).above(rise);
                BlockPos floor = feet.below();
                BlockState floorState = level.getBlockState(floor);
                if (floorState.getCollisionShape(level, floor).isEmpty()
                        || !floorState.getFluidState().isEmpty()
                        || isEscapeHazard(floorState)) {
                    break;
                }

                List<BlockPos> clear = new ArrayList<>(3);
                boolean safe = true;
                // A standing player occupies two blocks, but jumping up a full block briefly needs
                // the third. Clearing only feet+head made the first tread work and every later one
                // fail: the sloped ceiling over the lower tread caught the player's head mid-jump.
                for (BlockPos pos : List.of(feet, feet.above(), feet.above(2))) {
                    if (com.melody.mcagent.rt.path.PathFinder.canPass(level, pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty()
                            || state.getBlock() instanceof FallingBlock
                            || level.getBlockEntity(pos) != null) {
                        safe = false;
                        break;
                    }
                    int ticks = Actions.ticksToBreak(this.bot, pos);
                    if (ticks == Integer.MAX_VALUE) {
                        safe = false;
                        break;
                    }
                    clear.add(pos.immutable());
                    cost += Math.min(ticks, 20 * 60);
                }
                if (!safe) {
                    break;
                }

                steps.add(new EscapeStep(feet.immutable(), List.copyOf(clear)));
                int surfaceFeetY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
                if (feet.getY() >= surfaceFeetY) {
                    reachesSurface = true;
                    break;
                }
            }

            if (steps.isEmpty()) {
                continue;
            }
            // A route that exits wins over every partial route. Among complete routes prefer fewer
            // treads; among partial routes prefer the one that makes the most upward progress.
            int score = reachesSurface
                    ? steps.size() * 1_000 + cost
                    : 100_000 - steps.size() * 10_000 + cost;
            if (score < bestScore) {
                bestScore = score;
                best = new EscapeRoute(direction, List.copyOf(steps), reachesSurface, cost);
            }
        }
        return best;
    }

    private static boolean isEscapeHazard(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
                || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE);
    }

    /** Force one decision now, ignoring the cooldown. Used by the {@code think} command. */
    public void requestDecisionNow() {
        if (this.paused) {
            return;
        }
        this.cooldownTicks = 0;
        this.startDecision();
    }

    // --- the loop -------------------------------------------------------------------------------

    /**
     * Called once per server tick, on the server thread.
     *
     * <p>Handles the two things that must happen at tick precision — completing a timed block break
     * — and otherwise triggers the next decision when the cooldown expires.
     */
    public void tick() {
        if (this.bot.isRemoved()) {
            return;
        }

        if (this.paused) {
            return;
        }

        // Advance whatever long-running thing owns the bot, and notice when a journey ends.
        boolean busy = this.tickCombatJob();
        busy = this.tickMineJob() || busy;
        this.noticeMovementFinished();
        this.tickPendingSleep();
        busy = busy || this.isMoving();

        // Notice standing still. A bot that cannot move cannot tell anyone by moving, so it has to
        // be told: without this the model sees identical observations, no error from any tool, and
        // has no way to work out that it is wedged rather than waiting.
        BlockPos here = this.bot.blockPosition();
        if (here.equals(this.lastSeenAt)) {
            this.ticksMotionless++;
        } else {
            this.lastSeenAt = here.immutable();
            this.ticksMotionless = 0;
        }

        // Liveness watchdog. Counted here, reset when a turn completes.
        //
        // The counter sits below the pause guard on purpose — a paused bot must not be made to
        // think — and the consequence is that this line can never report a paused bot. That is
        // exactly how a bot frozen by a pause went unnoticed for fifteen minutes while this
        // watchdog was believed to make a silent freeze impossible, so the pause state is stated
        // in the message rather than left to be inferred from the guard's position.
        this.ticksSinceDecision++;
        if (this.ticksSinceDecision > DECISION_WATCHDOG_TICKS) {
            LOG.warn("Bot {} has not completed a decision in {} ticks (state: {}, paused: {}); forcing one",
                    this.bot.getName().getString(), this.ticksSinceDecision,
                    this.describeCurrentAction(), this.paused);
            this.ticksSinceDecision = 0;
            this.cooldownTicks = 0;
            // Drop whatever is in the way, so the forced decision is not immediately blocked again.
            this.abandonCurrentAction();
            this.abandonPlan("the bot was stuck and had to be restarted");
        }

        // Someone talking to the bot is answered promptly - the pause while busy is skipped - but it
        // does NOT force the bot to drop what it is doing. Whether an interruption is worth it is the
        // model's call, made with the interrupt tool and with WHAT YOU ARE DOING RIGHT NOW in front of
        // it. Aborting the action here instead was worse than it sounds: a player saying hello while
        // the bot was chopping a tree killed the job stone dead, mid-trunk. (Found by the tree test,
        // which kept reporting that its mining job "never started".)
        boolean addressed = com.melody.mcagent.rt.perception.ChatLog.shouldRespondPromptly(this.bot);
        if (addressed) {
            this.cooldownTicks = 0;
        }

        // A plan is in progress: run the next step the moment the previous one lets go, without
        // asking the model again. This is the difference between "walk there, then chop" arriving as
        // one instruction and arriving as two round trips with the bot idling in between.
        if (!busy && !this.queue.isEmpty()) {
            this.runQueued();
            busy = this.isLongActionRunning();
        }

        // Queue-aware lookahead. Do not spend another request while a healthy amount of work is
        // already buffered; once the queue falls below the low-water mark, shorten any old busy
        // cooldown so the next plan is generated while the current physical action is still moving.
        // With a 10-15 second endpoint this overlap is the difference between continuous work and
        // standing still for the full inference time after every small batch.
        if (!addressed && busy && this.queue.size() >= PLAN_LOW_WATERMARK) {
            if (this.cooldownTicks > 0) {
                this.cooldownTicks--;
            } else {
                this.cooldownTicks = PLAN_PREFETCH_DELAY_TICKS;
            }
            return;
        }
        if (!addressed && busy && this.queue.size() < PLAN_LOW_WATERMARK) {
            this.cooldownTicks = Math.min(this.cooldownTicks, PLAN_PREFETCH_DELAY_TICKS);
        }

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return;
        }

        if (this.thinking.get()) {
            return;
        }

        // While something long is running the model is still consulted, just less often - told what
        // the bot is doing, so it can decide whether to interrupt or to line up the next step. The
        // pause is applied where a turn *ends* (see the busy cooldown in handleCompletion) rather
        // than by refusing to think here, so a fresh instruction is never blocked outright.
        this.startDecision();
    }

    /**
     * Lie down once the bot has finished walking to the bed it was sent to.
     *
     * <p>A model that has to notice its own arrival and call sleep again wastes a turn and, in
     * practice, often does not bother.
     */
    private void tickPendingSleep() {
        BlockPos bed = this.pendingSleep;
        if (bed == null) {
            return;
        }
        if (this.bot.isSleeping()) {
            this.pendingSleep = null;
            return;
        }
        if (this.isMoving()) {
            return;
        }
        // Arrived (or gave up): try, once. Vanilla will explain the refusal if there is one.
        this.pendingSleep = null;
        Actions.Result outcome = Actions.sleep(this.bot, bed);
        this.actionReports.addLast(outcome.success()
                ? "you are asleep in the bed at " + bed.toShortString()
                        + "; it is now your respawn point"
                : "you could not sleep: " + outcome.message());
    }

    /** True if the bot was walking last tick and has now arrived or given up. */
    private void noticeMovementFinished() {
        boolean moving = this.isMoving();
        if (this.wasMoving && !moving && !this.mineJobCollecting()) {
            var manager = com.melody.mcagent.rt.Agent.botManager();
            var handle = manager == null ? null : manager.get(this.bot.getName().getString());
            boolean arrived = handle != null && handle.movement().hasArrived();
            if (!arrived && this.abortQueueIfMovementFails) {
                int discarded = this.queue.size();
                this.queue.clear();
                this.cooldownTicks = 0;
                this.actionReports.addLast("walking failed at "
                        + this.bot.blockPosition().toShortString() + "; discarded " + discarded
                        + " dependent step(s) and requested a fresh decision");
            } else {
                this.actionReports.addLast("you finished walking and are now at "
                        + this.bot.blockPosition().toShortString());
            }
            this.abortQueueIfMovementFails = false;
        }
        this.wasMoving = moving;
    }

    private boolean mineJobCollecting() {
        return this.mineJob != null && this.mineJob.collecting;
    }

    /** Halt any journey in progress. */
    private void stopMoving() {
        var manager = com.melody.mcagent.rt.Agent.botManager();
        if (manager == null) {
            return;
        }
        var handle = manager.get(this.bot.getName().getString());
        if (handle != null) {
            handle.movement().clear();
        }
    }

    private boolean isMoving() {
        var manager = com.melody.mcagent.rt.Agent.botManager();
        if (manager == null) {
            return false;
        }
        var handle = manager.get(this.bot.getName().getString());
        return handle != null && handle.movement().hasTarget();
    }

    /** Kick off one observe → decide → act cycle without blocking the server thread. */
    public void startDecision() {
        if (this.paused) {
            return;
        }
        if (!this.thinking.compareAndSet(false, true)) {
            return;
        }

        // Snapshot the world state on the server thread; the model only ever sees this snapshot.
        String observation;
        try {
            // Every audible chat message gets a decision on the next tick. Chat-triggered turns
            // include every carried stack rather than the ordinary bounded inventory summary, plus
            // the same fresh world, online-player and current-action state as any other turn.
            // Invoking the model is mandatory; replying remains its choice via the say tool.
            this.respondPromptly = com.melody.mcagent.rt.perception.ChatLog
                    .shouldRespondPromptly(this.bot);
            this.directlyAddressed = !com.melody.mcagent.rt.perception.ChatLog
                    .unheardDirected(this.bot).isEmpty();
            this.spokenThisDecision = false;
            String ongoing = this.describeOngoing();
            if (this.respondPromptly && ongoing.isBlank()) {
                ongoing = "\n=== WHAT YOU ARE DOING RIGHT NOW ===\n  idle; no actions are queued\n";
            }
            observation = this.observeFresh(this.respondPromptly)
                    + "\n" + ObservationBuilder.describeOnlinePlayers(this.bot)
                    + ongoing;

            // Fresh chat is time-sensitive even if it is background conversation the model decides
            // not to answer. The assignment above also prevents stale state leaking into later turns.
            if (this.respondPromptly) {
                this.cooldownTicks = 0;
            }
            // Mark what the model has just been shown, so the next observation flags only genuinely
            // new messages instead of repeating the same lines every turn.
            com.melody.mcagent.rt.perception.ChatLog.markRead(this.bot);
        } catch (Throwable t) {
            LOG.error("Failed to build observation for {}", this.bot.getName().getString(), t);
            this.thinking.set(false);
            this.cooldownTicks = 40;
            return;
        }

        if (!this.initialised) {
            this.history.add(this.identityMessage());
            this.history.add(LlmClient.Message.system("Abilities: " + this.policy.describe()));
            this.pinnedCount = 2;
            this.initialised = true;
        }
        this.history.add(LlmClient.Message.user(observation));
        this.compact();

        List<LlmClient.ToolSpec> tools = buildTools();
        List<LlmClient.Message> snapshot = List.copyOf(this.history);

        CompletableFuture
                .supplyAsync(() -> this.client.complete(snapshot, tools), executor())
                .thenAccept(completion -> {
                    // Back onto the server thread: everything below touches the world.
                    this.bot.server.execute(() -> {
                        // The bot may have been removed, or the server stopped, while the request
                        // was in flight. Acting on a removed entity would touch dead state, so drop
                        // the result and release the flag so the brain is not stuck "thinking".
                        if (this.bot.isRemoved() || this.bot.hasDisconnected()) {
                            this.thinking.set(false);
                            return;
                        }
                        // The operator may have paused the bot while the request was in flight.
                        // The reply cannot be cancelled, but acting on it would violate the pause.
                        if (this.paused) {
                            this.thinking.set(false);
                            return;
                        }
                        this.handleCompletion(completion);
                    });
                })
                .exceptionally(error -> {
                    LOG.error("Brain task failed for {}", this.bot.getName().getString(), error);
                    this.thinking.set(false);
                    this.cooldownTicks = 100;
                    return null;
                });
    }

    private void handleCompletion(LlmClient.Completion completion) {
        try {
            if (completion.failed()) {
                LOG.warn("Bot {} got an LLM error: {}", this.bot.getName().getString(), completion.error());
                // Back off so a broken endpoint does not hammer the API.
                this.cooldownTicks = 200;
                return;
            }

            // Record what the provider actually charged us for this turn: ground truth for how big the
            // transcript really is, as opposed to our character-based estimate.
            if (completion.usage() != null && completion.usage().isKnown()) {
                LlmClient.Usage usage = completion.usage();
                this.lastPromptTokens = usage.promptTokens();
                this.reportedUsageTurns++;
                this.totalPromptTokens += usage.promptTokens();
                this.totalCompletionTokens += usage.completionTokens();
                this.totalTokens += usage.totalTokens();
                if (usage.isCacheKnown()) {
                    this.cacheReportedTurns++;
                    this.totalCachedPromptTokens += usage.cachedPromptTokens();
                    this.totalUncachedPromptTokens += usage.uncachedPromptTokens();
                }
                this.calibrateEstimate(usage.promptTokens());
                if (LOG.isInfoEnabled()) {
                    String cache = usage.isCacheKnown()
                            ? String.format(java.util.Locale.ROOT, " cache=%d/%d (%.1f%%)",
                                    usage.cachedPromptTokens(),
                                    usage.cachedPromptTokens() + usage.uncachedPromptTokens(),
                                    usage.cachedPromptTokens() * 100.0D
                                            / Math.max(1L, (long) usage.cachedPromptTokens()
                                                    + usage.uncachedPromptTokens()))
                            : " cache=n/a";
                    LOG.info("Bot {} turn usage: prompt={} completion={} total={}{} (estimate was {})",
                            this.bot.getName().getString(), usage.promptTokens(),
                            usage.completionTokens(), usage.totalTokens(), cache,
                            this.calibratedTokens());
                }
            }

            // Reasoning text is kept in history so the model has continuity, but it is not spoken
            // aloud: talking is an explicit action.
            //
            // `reasoningContent` is stored separately and echoed back on the next request. Some
            // providers (DeepSeek-style thinking models behind OpenCode Go, notably) require the
            // assistant's reasoning to be present on tool-calling turns; omitting it degrades
            // multi-turn behaviour. Providers that do not use the field ignore it.
            this.history.add(LlmClient.Message.assistant(
                    completion.content(), completion.toolCalls(), completion.reasoningContent()));

            if (!completion.hasToolCalls()) {
                // Prose is private thought, including on chat-triggered turns. A player message must
                // invoke the model immediately, but it must not force a public answer; speaking is
                // an explicit say tool call so the model can deliberately stay silent.
                if (completion.truncated()) {
                    LOG.warn("Bot {} was cut off at the output token cap after {} completion token(s) "
                            + "- it never got as far as an action. Raise maxTokens.",
                            this.bot.getName().getString(),
                            completion.usage() == null ? 0 : completion.usage().completionTokens());
                } else {
                    if (LOG.isDebugEnabled() && completion.content() != null) {
                        LOG.debug("Bot {} thought without acting: {}",
                                this.bot.getName().getString(), firstLine(completion.content()));
                    }
                }
                // Give it a moment before asking again.
                this.cooldownTicks = this.respondPromptly ? 8 : 60;
                this.respondPromptly = false;
                this.directlyAddressed = false;
                return;
            }

            if (completion.truncated()) {
                // Tool calls that did not fit are gone, so the turn is partly lost. Logged because
                // the symptom is otherwise baffling: the bot "thinks" for a full turn and does
                // nothing visible, over and over.
                LOG.warn("Bot {} hit the output token cap mid-turn: only {} of its planned actions "
                        + "arrived. Raise maxTokens if this repeats.",
                        this.bot.getName().getString(), completion.toolCalls().size());
            }

            this.runTurn(completion.toolCalls());

            this.turnsCompleted.incrementAndGet();
            this.ticksSinceDecision = 0;

            // A short pause lets movement and world changes become observable before the next look.
            // After being spoken to, come back sooner: a conversation that stalls for seconds
            // between lines does not read as a conversation.
            //
            // If the bot is still busy, wait longer before asking again. It is not blocked - the
            // model can still interrupt or line up the next step - but a fifteen-second mining job
            // should not cost fifteen model calls.
            this.cooldownTicks = this.respondPromptly ? 8
                    : (this.isLongActionRunning()
                            ? (this.queue.size() < PLAN_LOW_WATERMARK
                                    ? PLAN_PREFETCH_DELAY_TICKS : BUSY_COOLDOWN_TICKS)
                            : 20);
            this.respondPromptly = false;
            this.directlyAddressed = false;
        } catch (Throwable t) {
            LOG.error("Error handling LLM completion for {}", this.bot.getName().getString(), t);
            this.cooldownTicks = 100;
        } finally {
            this.thinking.set(false);
        }
    }

    /**
     * Run one turn's tool calls, in the order the model gave them.
     *
     * <p>The rule is simple and the model is told it: calls run top to bottom, and a call that cannot
     * share the bot's attention waits until the one before it is finished. Concurrency is therefore
     * opt-in per tool rather than a scheduling guess — see {@link #isConcurrent}.
     */
    private void runTurn(List<LlmClient.ToolCall> calls) {
        this.turnCalls = calls;
        this.turnResults = new String[calls.size()];
        this.nextResultToFlush = 0;

        int executed = 0;
        for (int i = 0; i < calls.size(); i++) {
            LlmClient.ToolCall call = calls.get(i);

            if (executed >= MAX_TOOL_CALLS_PER_TURN) {
                // Tell the model it was throttled, so it can pace itself rather than silently
                // believing an action happened.
                this.recordResult(i, "failed: too many actions requested at once; ask for the rest "
                        + "next turn. Each action was listed in order and this one was dropped.");
                continue;
            }
            executed++;

            boolean busy = !this.queue.isEmpty() || this.isLongActionRunning();
            if (!busy || isConcurrent(call.name())) {
                String result = this.execute(call);
                if (LOG.isInfoEnabled()) {
                    LOG.info("Bot {} called {}({}) -> {}", this.bot.getName().getString(),
                            call.name(), summarise(call.arguments()), firstLine(result));
                }
                this.recordResult(i, result);
            } else {
                // Something long is already running and this step cannot overlap it, so it becomes
                // part of the plan rather than being refused.
                //
                // It is answered NOW, not when it eventually runs. A tool call left unanswered makes
                // every later request malformed - the provider rejects the whole turn - and holding
                // the answer back only worked while the bot refused to think during an action. The
                // moment decisions were allowed mid-action that assumption died, and the bot started
                // getting "an assistant message with 'tool_calls' must be followed by tool messages".
                // What the step eventually *did* is reported in the next observation instead.
                this.queue.addLast(new QueuedCall(call, i));
                String pending = this.describeCurrentAction();
                this.recordResult(i, "queued: this will run as soon as you finish " + pending);
                if (LOG.isInfoEnabled()) {
                    LOG.info("Bot {} queued {}({}) until the current action finishes",
                            this.bot.getName().getString(), call.name(), summarise(call.arguments()));
                }
            }
        }

        this.flushResults();
    }

    /**
     * Run the next queued step, and keep going while the steps are instant.
     *
     * <p>Called once a tick from {@link #tick()}. A run of instant steps drains in a single tick; the
     * moment one of them starts something long, the loop stops and the rest wait for it.
     */
    private void runQueued() {
        while (!this.queue.isEmpty() && !this.isLongActionRunning()) {
            QueuedCall next = this.queue.pollFirst();
            String result = next.call.id().startsWith("plan_step_")
                    ? this.executePlanStep(next.call)
                    : this.execute(next.call);
            if (LOG.isInfoEnabled()) {
                LOG.info("Bot {} (plan) called {}({}) -> {}", this.bot.getName().getString(),
                        next.call.name(), summarise(next.call.arguments()), firstLine(result));
            }
            // The tool call was already answered with "queued: ...", so the outcome goes into the
            // running report the next observation carries. Writing it into the turn's result slot
            // would be a no-op at best and a duplicate tool result at worst.
            this.actionReports.addLast(next.call.name() + " -> " + firstLine(result));
            if (next.abortPlanOnFailure && "goto".equals(next.call.name()) && this.isMoving()) {
                this.abortQueueIfMovementFails = true;
            }
            if (next.abortPlanOnFailure && isFailure(result)) {
                int discarded = this.queue.size();
                this.queue.clear();
                this.actionReports.addLast("the plan stopped because " + next.call.name()
                        + " failed; discarded " + discarded
                        + " dependent step(s) and requested a fresh decision");
                this.cooldownTicks = 0;
                break;
            }
        }
    }

    /**
     * Validate and schedule a compact server-side action plan from one model tool call.
     *
     * <p>Unlike sibling tool calls, these steps have strict sequencing: once an action starts a
     * walk/mine/fight, every later step waits, including chat and looking. That makes the plan useful
     * for dependencies such as "go there, open, withdraw, return" rather than merely a bag of calls
     * which happen to have been emitted together.
     */
    private String schedulePlan(JsonObject args) {
        if (args == null || !args.has("steps") || !args.get("steps").isJsonArray()) {
            return "failed: plan requires a steps array";
        }
        JsonArray steps = args.getAsJsonArray("steps");
        if (steps.size() < 2) {
            return "failed: a plan needs at least 2 steps; call a single action directly";
        }
        if (steps.size() > MAX_PLAN_STEPS) {
            return "failed: a plan may contain at most " + MAX_PLAN_STEPS + " steps";
        }
        if (this.queue.size() + steps.size() > MAX_QUEUED_ACTIONS) {
            return "failed: the action queue already has " + this.queue.size()
                    + " step(s); wait for it to drain before adding " + steps.size() + " more";
        }

        List<QueuedCall> parsed = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            JsonElement raw = steps.get(i);
            if (!raw.isJsonObject()) {
                return "failed: plan step " + (i + 1) + " is not an object";
            }
            JsonObject step = raw.getAsJsonObject();
            String tool = string(step, "tool", "");
            if (!PLANNABLE_TOOLS.contains(tool)) {
                return "failed: plan step " + (i + 1) + " uses unsupported action '" + tool + "'";
            }
            if (!this.planToolAvailable(tool)) {
                return "failed: plan step " + (i + 1) + " uses action '" + tool
                        + "', which is disabled by this bot's policy";
            }
            JsonObject stepArgs = new JsonObject();
            if (step.has("arguments") && step.get("arguments").isJsonObject()) {
                stepArgs = step.getAsJsonObject("arguments");
            }
            boolean continueOnFailure = step.has("continue_on_failure")
                    && step.get("continue_on_failure").isJsonPrimitive()
                    && step.get("continue_on_failure").getAsBoolean();
            parsed.add(new QueuedCall(
                    new LlmClient.ToolCall("plan_step_" + (i + 1), tool, stepArgs),
                    -1, !continueOnFailure));
        }

        int started = 0;
        List<String> immediate = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            QueuedCall step = parsed.get(i);
            // Strict ordering: after any long action, even otherwise-concurrent tools wait. If an
            // older plan is already queued, this whole batch extends it rather than jumping ahead.
            if (!this.queue.isEmpty() || this.isLongActionRunning()) {
                for (int j = i; j < parsed.size(); j++) {
                    this.queue.addLast(parsed.get(j));
                }
                break;
            }

            String result = this.executePlanStep(step.call);
            started++;
            immediate.add((i + 1) + ":" + step.call.name() + " -> " + firstLine(result));
            if (step.abortPlanOnFailure && "goto".equals(step.call.name()) && this.isMoving()) {
                this.abortQueueIfMovementFails = true;
            }
            if (step.abortPlanOnFailure && isFailure(result)) {
                return "plan stopped at step " + (i + 1) + ": " + firstLine(result)
                        + "; no later steps were run";
            }
        }

        int queued = this.queue.size();
        String result = "accepted " + parsed.size() + " sequential step(s); "
                + started + " ran immediately and " + queued + " total step(s) are waiting";
        if (!immediate.isEmpty()) {
            result += ". Started: " + String.join("; ", immediate);
        }
        return result;
    }

    /** Policy gate for nested plan actions; execution checks the same policy again. */
    private boolean planToolAvailable(String tool) {
        return switch (tool) {
            case "mine", "dig_tunnel", "escape_up" -> this.policy.canBreakBlocks();
            case "place", "use" -> this.policy.canPlaceBlocks();
            case "open_container", "withdraw", "deposit" -> this.policy.canUseContainers();
            case "attack" -> this.policy.canAttack();
            default -> true;
        };
    }

    /** Mark nested execution so emergency macros keep, and run before, the plan's dependent tail. */
    private String executePlanStep(LlmClient.ToolCall call) {
        boolean previous = this.executingPlanStep;
        this.executingPlanStep = true;
        try {
            return this.execute(call);
        } finally {
            this.executingPlanStep = previous;
        }
    }

    private static boolean isFailure(String result) {
        return result != null && result.startsWith("failed:");
    }

    /** Store a result for one call of the current turn; it becomes visible once earlier ones have. */
    private void recordResult(int index, String text) {
        if (this.turnResults == null || index < 0 || index >= this.turnResults.length) {
            return;
        }
        this.turnResults[index] = text;
    }

    /**
     * Append results to the transcript, in order, as far as they are contiguous.
     *
     * <p>Flushing from the front is what keeps the transcript valid: a provider rejects a request
     * whose assistant message has tool calls that were never answered, so a step that is still
     * pending holds back the ones after it rather than being skipped over.
     */
    private void flushResults() {
        if (this.turnResults == null || this.turnCalls == null) {
            return;
        }
        while (this.nextResultToFlush < this.turnResults.length
                && this.turnResults[this.nextResultToFlush] != null) {
            this.history.add(LlmClient.Message.toolResult(
                    this.turnCalls.get(this.nextResultToFlush).id(),
                    this.turnResults[this.nextResultToFlush]));
            this.nextResultToFlush++;
        }
        if (this.nextResultToFlush >= this.turnResults.length) {
            this.turnCalls = null;
            this.turnResults = null;
            this.nextResultToFlush = 0;
        }
    }

    /**
     * Abandon anything still pending and close out the turn.
     *
     * <p>Needed whenever the bot's situation changes underneath a plan — being spoken to, paused,
     * dying. The remaining steps are answered honestly rather than left dangling, because an
     * unanswered tool call would make every later request malformed.
     */
    private void abandonPlan(String reason) {
        if (this.turnResults == null) {
            this.queue.clear();
            this.abortQueueIfMovementFails = false;
            return;
        }
        for (int i = this.nextResultToFlush; i < this.turnResults.length; i++) {
            if (this.turnResults[i] == null) {
                this.turnResults[i] = "cancelled: " + reason;
            }
        }
        this.queue.clear();
        this.abortQueueIfMovementFails = false;
        this.flushResults();
        this.turnCalls = null;
        this.turnResults = null;
        this.nextResultToFlush = 0;
    }

    /**
     * Reduce the transcript to fit the budget, without ever breaking its structure.
     *
     * <p>Trimming by raw index is unsafe: a turn is an atomic group consisting of the user
     * observation, the assistant message that requested tools, and one tool-result message per
     * call. Removing one element without its siblings produces a transcript that strict
     * OpenAI-compatible APIs reject with a 400 — the bot would simply stop working after enough
     * turns. (Measured: the previous index-based approach corrupted ~48% of long histories.)
     *
     * <p>The layered approach follows the established practice for agent context management — see
     * the strategy ladder in Microsoft's agent-framework compaction docs — applying the gentlest
     * strategy that brings us back inside budget:
     *
     * <ol>
     *   <li><b>Collapse old tool results</b> (gentle, lossy only in detail already acted upon).
     *       Observations dominate the transcript, so this usually suffices.</li>
     *   <li><b>Drop whole oldest turns</b> (aggressive) if collapsing was not enough.</li>
     * </ol>
     *
     * <p>System messages are never touched: they carry the bot's identity and permissions, so
     * losing them would change behaviour rather than merely shorten context.
     */
    private void compact() {
        int budget = this.tokenBudget;

        // Cheap structural ceiling first: an enormous number of tiny messages is pathological even
        // if the token count looks acceptable.
        enforceTurnCeiling();

        if (calibratedTokens() <= budget) {
            return;
        }

        // Stage 1: collapse verbose tool results of older turns, keeping the recent turns intact.
        int collapsed = collapseOldToolResults();
        if (collapsed > 0) {
            LOG.info("Bot {} compacted: collapsed {} old tool result(s)",
                    this.bot.getName().getString(), collapsed);
        }
        if (calibratedTokens() <= budget) {
            return;
        }

        // Stage 2: drop the oldest whole turns, still never splitting a group.
        int dropped = 0;
        while (calibratedTokens() > budget && this.history.size() > this.pinnedCount + MIN_TURNS_KEPT * 2) {
            int cutEnd = nextTurnEnd(1);
            if (cutEnd <= this.pinnedCount) {
                break;
            }
            this.history.subList(this.pinnedCount, cutEnd).clear();
            dropped++;
        }
        if (dropped > 0) {
            LOG.info("Bot {} compacted: dropped {} oldest turn(s)",
                    this.bot.getName().getString(), dropped);
        }
    }

    /** Hard ceiling on turn count, independent of the token estimate. */
    private void enforceTurnCeiling() {
        int maxMessages = this.pinnedCount + MAX_HISTORY_TURNS * 2;
        while (this.history.size() > maxMessages) {
            int cutEnd = nextTurnEnd(1);
            if (cutEnd <= this.pinnedCount) {
                break;
            }
            this.history.subList(this.pinnedCount, cutEnd).clear();
        }
    }

    /**
     * Estimate the transcript size in tokens.
     *
     * <p>Characters divided by four is the standard heuristic for English text and is what several
     * agent frameworks use by default. It is deliberately conservative for non-English content,
     * where real tokenizers emit more tokens per character — we would rather compact slightly early
     * than overflow the window.
     */
    private int estimatedTokens() {
        int chars = 0;
        for (LlmClient.Message message : this.history) {
            if (message.content() != null) {
                chars += message.content().length();
            }
            if (message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    chars += call.name().length() + call.arguments().toString().length();
                }
            }
            // Per-message framing overhead.
            chars += 8;
        }

        int textTokens = chars / CHARS_PER_TOKEN + 1;

        // The tool schemas are re-sent on every request and are NOT in the history, so leaving them
        // out made the estimate badly wrong: measured against a real endpoint, the true prompt was
        // 2.4-4.9x the text-only estimate, because ~14 tool definitions cost roughly 1800 tokens on
        // their own. At the old numbers a 12k budget behaved like ~29k real tokens and the bot would
        // have overflowed the window before compaction ever triggered.
        return textTokens + this.toolSchemaTokens();
    }

    /**
     * Tokens consumed by the tool definitions attached to every request.
     *
     * <p>Measured once per tool-set and cached, because the schemas are static for the lifetime of a
     * brain and serialising them on every tick would be wasteful.
     */
    private int toolSchemaTokens() {
        if (this.cachedToolSchemaTokens < 0) {
            int chars = 0;
            for (LlmClient.ToolSpec tool : buildTools()) {
                chars += tool.name().length() + tool.description().length() + tool.parameters().toString().length();
            }
            this.cachedToolSchemaTokens = chars / CHARS_PER_TOKEN + 1;
        }
        return this.cachedToolSchemaTokens;
    }

    /**
     * Correct the character-based estimate using what the provider actually reported.
     *
     * <p>Providers count tokens with a real tokenizer, so once we have been told the true prompt
     * size we can derive how far our estimate drifts and compensate. This keeps the budget honest
     * without embedding a model-specific tokenizer.
     */
    private void calibrateEstimate(int actualPromptTokens) {
        int estimate = estimatedTokens();
        if (actualPromptTokens <= 0 || estimate <= 0) {
            return;
        }
        // Exponential moving average: one odd turn should not swing the ratio.
        double observed = (double) actualPromptTokens / estimate;
        this.calibration = this.calibration <= 0.0 ? observed : this.calibration * 0.7 + observed * 0.3;
    }

    /** Apply the learned correction to a raw estimate. */
    private int calibratedTokens() {
        int raw = estimatedTokens();
        return this.calibration > 0.0 ? (int) (raw * this.calibration) : raw;
    }

    /**
     * Replace the body of long tool results in older turns with a marker.
     *
     * <p>This is the highest-value compaction step here because observation dumps are the bulk of
     * the transcript. The message itself is kept, so the assistant/tool pairing stays valid. What
     * the model loses is detail it has already acted on, which is the right thing to forget.
     *
     * @return how many results were collapsed
     */
    private int collapseOldToolResults() {
        int turnsSeen = 0;
        int collapsed = 0;

        for (int i = this.history.size() - 1; i >= this.pinnedCount; i--) {
            LlmClient.Message message = this.history.get(i);

            if ("user".equals(message.role()) && ++turnsSeen > RECENT_TURNS_KEPT_INTACT) {
                // Everything from here back is old enough to compress.
                for (int j = i; j >= this.pinnedCount; j--) {
                    LlmClient.Message old = this.history.get(j);
                    if ("tool".equals(old.role())
                            && old.content() != null
                            && old.content().length() > COLLAPSE_THRESHOLD_CHARS) {
                        this.history.set(j, LlmClient.Message.toolResult(
                                old.toolCallId(),
                                old.content().substring(0, COLLAPSE_HEAD_CHARS)
                                        + "\n...[" + (old.content().length() - COLLAPSE_HEAD_CHARS)
                                        + " chars omitted to save context]"));
                        collapsed++;
                    }
                }
                break;
            }
        }
        return collapsed;
    }

    /**
     * Find the end of the {@code skip}th turn, counting from the start.
     *
     * <p>A turn ends where the next user message begins, so the result always lands on a group
     * boundary and a turn is never split.
     */
    private int nextTurnEnd(int skip) {
        int seen = 0;
        for (int i = this.pinnedCount; i < this.history.size(); i++) {
            if (i > this.pinnedCount && "user".equals(this.history.get(i).role())) {
                if (++seen == skip) {
                    return i;
                }
            }
        }
        return this.history.size();
    }

    /** Tokens estimated for the transcript right now (for diagnostics). */
    public int contextTokens() {
        return calibratedTokens();
    }

    /** The most recent provider-reported prompt size, or 0 if it never reported. */
    public int lastReportedPromptTokens() {
        return this.lastPromptTokens;
    }

    // --- tool definitions -----------------------------------------------------------------------

    private List<LlmClient.ToolSpec> buildTools() {
        List<LlmClient.ToolSpec> tools = new ArrayList<>();

        tools.add(new LlmClient.ToolSpec("plan",
                "Preferred for every task with two or more known actions. Submit one compact, "
                + "strictly sequential plan instead of many sibling tool calls. Up to "
                + MAX_PLAN_STEPS + " steps are buffered and keep running while the next LLM call "
                + "is still in flight. A failed step cancels later dependent steps by default; set "
                + "continue_on_failure only when that particular later work is independent. Do not "
                + "pad a plan with observe/look_at/stop ceremonies or guess unknown coordinates.",
                planSchema()));

        tools.add(new LlmClient.ToolSpec("observe",
                "Look around and get a fresh description of what you can see, your state and your inventory.",
                LlmClient.schema(LlmClient.params())));

        tools.add(new LlmClient.ToolSpec("goto",
                "Walk to a position in the world, pathfinding around obstacles. Blocks until you arrive or give up.",
                LlmClient.schema(LlmClient.params(
                        "x", "number: target X",
                        "z", "number: target Z",
                        "y", "number: target Y (optional)"), List.of("x", "z"))));

        tools.add(new LlmClient.ToolSpec("look_at",
                "Turn your head to face a position.",
                LlmClient.schema(LlmClient.params(
                        "x", "number: X",
                        "y", "number: Y",
                        "z", "number: Z"), List.of("x", "y", "z"))));

        tools.add(new LlmClient.ToolSpec("say",
                "Speak in chat. Other players will see this message.",
                LlmClient.schema(LlmClient.params("message", "string: what to say"), List.of("message"))));

        tools.add(new LlmClient.ToolSpec("eat",
                "Eat food you are carrying to restore hunger. Eating takes a moment but does not stop "
                + "you walking, so you can call this together with goto.",
                LlmClient.schema(LlmClient.params(
                        "item", "string: optional item id to eat; omit to eat whatever food you have"))));

        tools.add(new LlmClient.ToolSpec("stop",
                "Stop moving and stand still.",
                LlmClient.schema(LlmClient.params())));

        tools.add(new LlmClient.ToolSpec("return_to_spawn",
                "Emergency escape: instantly teleport to your vanilla respawn point without dying "
                + "or dropping inventory. If your bed or anchor is missing, this uses world spawn. "
                + "Use when physically trapped and digging out is unsafe or impossible; do not use "
                + "as routine travel.",
                LlmClient.schema(LlmClient.params())));

        if (this.policy.canBreakBlocks()) {
            tools.add(new LlmClient.ToolSpec("mine",
                    "Break a block, taking the correct amount of time for your tool. Give a radius to "
                    + "fell a whole tree or clear a vein in one go: the job keeps breaking connected "
                    + "blocks of the SAME kind within that many blocks of the one you named, then "
                    + "walks over and picks up the drops. Use radius 0 or omit it for a single block.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z",
                            "radius", "number: optional, how far the job may spread, e.g. 6 for a tree",
                            "item", "string: optional tool to dig with, e.g. iron_pickaxe or axe; "
                                    + "you do not need to have it in your hand"),
                            List.of("x", "y", "z"))));

            tools.add(new LlmClient.ToolSpec("dig_tunnel",
                    "Preferred way to mine underground or descend. Locally digs and walks through "
                    + "a safe two-block-high tunnel without guessing per-block coordinates. mode=down "
                    + "makes a descending staircase; mode=level makes a branch/strip-mine tunnel. "
                    + "The first call establishes one persistent entrance. Calls made after unloading "
                    + "at storage automatically return through that entrance to the saved working face, "
                    + "instead of opening another hole. Set new_site=true only when a player explicitly "
                    + "wants a different mine. One call handles up to 24 blocks with real tool timing "
                    + "and drops. It stops before fluids, falling blocks, gaps, block entities or "
                    + "unbreakable terrain.",
                    LlmClient.schema(LlmClient.params(
                            "direction", "string: north, south, east or west",
                            "mode", "string: down or level",
                            "length", "number: tunnel length from 1 to 24",
                            "item", "string: optional fallback tool; ordinary stone automatically uses stone_pickaxe",
                            "new_site", "boolean: optional, default false; deliberately abandon the old mine route"),
                            List.of("direction", "mode", "length"))));

            tools.add(new LlmClient.ToolSpec("escape_up",
                    "Escape an underground pit by choosing a safe cardinal direction, digging a "
                    + "staircase with real jump clearance and normal mining time, then walking up each tread. "
                    + "One call climbs up to 8 blocks and avoids block entities, falling blocks, "
                    + "fluids and unbreakable terrain. Prefer this after goto says there is no route; "
                    + "use return_to_spawn if it reports no safe staircase.",
                    LlmClient.schema(LlmClient.params(
                            "item", "string: optional tool from your pack, e.g. iron_pickaxe"))));
        }

        tools.add(new LlmClient.ToolSpec("hold",
                "Take something out of your pack and hold it. Most actions take an item name directly "
                + "and do this for you, so you rarely need this on its own.",
                LlmClient.schema(LlmClient.params(
                        "item", "string: the item to hold, e.g. iron_pickaxe"), List.of("item"))));

        tools.add(new LlmClient.ToolSpec("discard",
                "Permanently remove low-value items from your pack to free space. Use this when the "
                + "inventory is full; prefer redundant low-tier tools and surplus cobblestone/dirt. "
                + "This cannot be undone.",
                LlmClient.schema(LlmClient.params(
                        "item", "string: item id or name to discard",
                        "count", "number: how many to discard"), List.of("item", "count"))));

        tools.add(new LlmClient.ToolSpec("pickup",
                "Walk over to items lying on the ground near you and collect them.",
                LlmClient.schema(LlmClient.params(
                        "radius", "number: optional search radius in blocks, default 8"))));

        tools.add(new LlmClient.ToolSpec("sleep",
                "Go to the nearest bed and sleep in it. Sleeping is how you set your respawn point, "
                + "so do this at night, or before doing anything dangerous: otherwise dying sends you "
                + "all the way back to world spawn. Only works at night or in a thunderstorm.",
                LlmClient.schema(LlmClient.params())));

        tools.add(new LlmClient.ToolSpec("wake",
                "Get out of bed.",
                LlmClient.schema(LlmClient.params())));

        tools.add(new LlmClient.ToolSpec("interrupt",
                "Stop whatever you are currently doing (walking, mining). Use this when you are "
                + "part-way through something and have decided to do something else instead.",
                LlmClient.schema(LlmClient.params())));

        if (this.policy.canPlaceBlocks()) {
            tools.add(new LlmClient.ToolSpec("place",
                    "Place the block you are holding against a block face.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z",
                            "face", "string: up, down, north, south, east or west"), List.of("x", "y", "z"))));

            tools.add(new LlmClient.ToolSpec("use",
                    "Right-click a block: open a chest, use a machine, press a button.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z"), List.of("x", "y", "z"))));
        }

        if (this.policy.canUseContainers()) {
            tools.add(new LlmClient.ToolSpec("open_container",
                    "Open a container so you can see what is inside. You must do this before you can know its contents.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z"), List.of("x", "y", "z"))));

            tools.add(new LlmClient.ToolSpec("withdraw",
                    "Take items out of a container you have opened.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z",
                            "item", "string: item id, e.g. minecraft:iron_ingot",
                            "count", "number: how many"), List.of("x", "y", "z", "item", "count"))));

            tools.add(new LlmClient.ToolSpec("deposit",
                    "Put items from your inventory into a container you have opened.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X", "y", "number: Y", "z", "number: Z",
                            "item", "string: item id",
                            "count", "number: how many"), List.of("x", "y", "z", "item", "count"))));
        }

        tools.add(new LlmClient.ToolSpec("craft",
                "Craft an item from ingredients in your inventory. Requires the ingredients to be "
                + "carried; it does not need a crafting table in the world, but the recipe must be "
                + "a crafting-grid recipe.",
                LlmClient.schema(LlmClient.params(
                        "item", "string: item id or name to craft, e.g. minecraft:stick",
                        "count", "number: how many times to craft it"), List.of("item"))));

        tools.add(new LlmClient.ToolSpec("craftable_now",
                "List what you could craft right now with what you are carrying.",
                LlmClient.schema(LlmClient.params())));

        if (this.policy.canAttack()) {
            tools.add(new LlmClient.ToolSpec("attack",
                    "Fight the nearest visible entity matching a type or player name, e.g. zombie. "
                    + "You will pursue it and keep attacking at normal weapon-cooldown speed until "
                    + "it dies, escapes, or 30 seconds pass. 'nearest' means the nearest HOSTILE "
                    + "creature and will never accidentally select a player. This is a long action "
                    + "that can be stopped with interrupt.",
                    LlmClient.schema(LlmClient.params(
                            "target", "string: entity type/player name, or 'nearest' for nearest hostile",
                            "item", "string: optional weapon to use, e.g. iron_sword"), List.of("target"))));
        }

        tools.add(new LlmClient.ToolSpec("chat_command",
                "Run a slash command you are allowed to use, e.g. /home or /spawn. Do not include the leading slash.",
                LlmClient.schema(LlmClient.params("command", "string: the command"), List.of("command"))));

        tools.add(new LlmClient.ToolSpec("find_item",
                "Look up what an item is and how to craft it. Use this to learn recipes.",
                LlmClient.schema(LlmClient.params("item", "string: item name or id"), List.of("item"))));

        tools.add(new LlmClient.ToolSpec("find_uses",
                "Look up what an item is used for (which recipes consume it).",
                LlmClient.schema(LlmClient.params("item", "string: item name or id"), List.of("item"))));

        // --- long-term memory -------------------------------------------------------------------
        // These notes outlive the conversation, so they are for things worth knowing next session:
        // what a machine does, where things are kept, who owns which base, what a player asked for.
        tools.add(new LlmClient.ToolSpec("remember",
                "Save a note that you will still have tomorrow, even after a restart. Use it for "
                + "durable facts: what a machine or block is for, where a chest is and what is in it, "
                + "where your home is, what a player asked you to do. Not for things you can see right "
                + "now - only for what you would otherwise have to work out again.",
                LlmClient.schema(LlmClient.params(
                        "key", "string: a short label, e.g. 'home' or 'iron chest'",
                        "value", "string: what to remember about it"), List.of("key", "value"))));

        tools.add(new LlmClient.ToolSpec("recall",
                "Look up your saved notes. Omit the query to list everything you remember.",
                LlmClient.schema(LlmClient.params(
                        "query", "string: optional words to search your notes for"))));

        tools.add(new LlmClient.ToolSpec("forget",
                "Delete a saved note that is no longer true.",
                LlmClient.schema(LlmClient.params("key", "string: the label to delete"), List.of("key"))));

        return tools;
    }

    /** JSON schema for the compact multi-action plan tool. */
    private static JsonObject planSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);

        JsonObject properties = new JsonObject();
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("description", "2-24 actions to execute in strict order");
        steps.addProperty("minItems", 2);
        steps.addProperty("maxItems", MAX_PLAN_STEPS);

        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.addProperty("additionalProperties", false);
        JsonObject itemProperties = new JsonObject();

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "string");
        JsonArray names = new JsonArray();
        PLANNABLE_TOOLS.forEach(names::add);
        tool.add("enum", names);
        tool.addProperty("description", "the action tool to run");
        itemProperties.add("tool", tool);

        JsonObject arguments = new JsonObject();
        arguments.addProperty("type", "object");
        arguments.addProperty("description", "arguments exactly as that action tool expects");
        arguments.addProperty("additionalProperties", true);
        itemProperties.add("arguments", arguments);

        JsonObject continueOnFailure = new JsonObject();
        continueOnFailure.addProperty("type", "boolean");
        continueOnFailure.addProperty("description",
                "normally false; true only when later steps remain valid if this one fails");
        itemProperties.add("continue_on_failure", continueOnFailure);
        item.add("properties", itemProperties);

        JsonArray itemRequired = new JsonArray();
        itemRequired.add("tool");
        itemRequired.add("arguments");
        item.add("required", itemRequired);
        steps.add("items", item);
        properties.add("steps", steps);
        root.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("steps");
        root.add("required", required);
        return root;
    }

    /**
     * Tools that may run <em>while</em> the bot is already busy walking or mining.
     *
     * <p>The distinction is what makes a multi-step plan possible. A model that asks for
     * {@code goto} and {@code say} together wants to talk on the way, not to stand still, talk, and
     * then set off; a model that asks for {@code goto} and {@code mine} wants the mining to begin the
     * instant it arrives. Both are the same request — an ordered list — and the difference is
     * entirely in which of the two calls can share the journey.
     *
     * <p>Everything not listed here has to wait its turn: it either moves the bot, changes what it
     * is holding, or acts on the world at a position it must first be standing at.
     */
    private static boolean isConcurrent(String tool) {
        return switch (tool) {
            // "interrupt" is here out of necessity, not convenience: it exists to stop the action
            // that is running, so queueing it behind that action makes it useless. Observed in
            // production as a bot that reported "still stuck in the mining task, interrupt is just
            // queued too" - it had diagnosed its own problem correctly and been ignored.
            case "plan", "say", "look_at", "eat", "remember", "forget", "recall",
                 "find_item", "find_uses", "craftable_now", "interrupt", "return_to_spawn",
                 "escape_up" -> true;
            default -> false;
        };
    }

    /** True while something long-running owns the bot and the next queued step must wait. */
    private boolean isLongActionRunning() {
        return this.mineJob != null || this.combatJob != null || this.isMoving();
    }

    /** Begin a real combat exchange rather than performing one isolated swing. */
    private String startCombat(Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive() || target.isRemoved()) {
            return "failed: that target is not alive";
        }
        this.stopMoving();
        this.combatJob = new CombatJob(target);
        return "fighting " + Perception.describe(target) + " at "
                + target.blockPosition().toShortString()
                + "; you will pursue it and keep attacking until it dies or gets away";
    }

    /** Advance a pursuit/fight using ordinary player movement and attack cooldowns. */
    private boolean tickCombatJob() {
        CombatJob job = this.combatJob;
        if (job == null) {
            return false;
        }
        if (this.bot.isRemoved() || this.bot.isDeadOrDying()) {
            this.combatJob = null;
            return false;
        }

        Entity found = this.bot.serverLevel().getEntity(job.targetId);
        if (!(found instanceof LivingEntity target) || found.isRemoved() || !target.isAlive()) {
            this.finishCombat(job, "defeated " + job.label);
            return false;
        }
        if (found.level() != this.bot.level()) {
            this.finishCombat(job, job.label + " left this dimension");
            return false;
        }
        if (++job.ticks > COMBAT_TIMEOUT_TICKS) {
            this.finishCombat(job, "stopped fighting " + job.label + " after 30 seconds");
            return false;
        }

        boolean visible = Perception.canSee(this.bot, target);
        if (visible) {
            job.lastSeen = target.blockPosition().immutable();
            job.unseenTicks = 0;
        } else {
            job.unseenTicks++;
            if (job.unseenTicks > COMBAT_LOST_SIGHT_TICKS) {
                this.finishCombat(job, "lost sight of " + job.label + " for 5 seconds and gave up");
                return false;
            }
        }

        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        double distance = this.bot.getEyePosition().distanceTo(targetCenter);
        if (visible && distance <= Actions.REACH) {
            // Do not run through the target while waiting for the next fully charged swing.
            this.stopMoving();
            MovementDriver.lookAt(this.bot, targetCenter);
            if (this.bot.getAttackStrengthScale(0.5F) >= 0.9F) {
                Actions.Result result = Actions.attack(this.bot, target);
                if (result.success()) {
                    job.hits++;
                }
            }
            return true;
        }

        // Chase only the last place actually seen. The live entity object is never used as a route
        // target while hidden, otherwise the bot would track mobs through solid walls.
        BlockPos chase = job.lastSeen;
        if (chase == null) {
            this.finishCombat(job, "could no longer locate " + job.label);
            return false;
        }
        var manager = com.melody.mcagent.rt.Agent.botManager();
        var handle = manager == null ? null : manager.get(this.bot.getName().getString());
        if (handle == null) {
            this.finishCombat(job, "could not pursue " + job.label + " because movement is unavailable");
            return false;
        }

        boolean targetMoved = job.plannedFor == null || job.plannedFor.distSqr(chase) > 4.0D;
        if (job.repathCooldown-- <= 0
                && (!handle.movement().hasTarget() || (visible && targetMoved))) {
            MovementDriver.Plan plan = handle.movement().setPathTarget(chase, COMBAT_PATH_RANGE);
            job.repathCooldown = 10;
            if (plan == MovementDriver.Plan.FOUND) {
                job.plannedFor = chase.immutable();
                job.blockedPlans = 0;
            } else if (++job.blockedPlans >= 5) {
                this.finishCombat(job, "could not find a route to " + job.label);
                return false;
            }
        }
        return true;
    }

    /** Close a combat job and make the outcome visible in the next observation. */
    private void finishCombat(CombatJob job, String outcome) {
        this.combatJob = null;
        this.stopMoving();
        this.actionReports.addLast(outcome + " after landing " + job.hits + " hit(s)");
        LOG.info("Bot {} finished combat with {}: {} ({} hit(s))",
                this.bot.getName().getString(), job.label, outcome, job.hits);
    }

    /**
     * Advance the block-breaking job by one tick.
     *
     * @return true while the job still owns the bot
     */
    private boolean tickMineJob() {
        MineJob job = this.mineJob;
        if (job == null) {
            return false;
        }
        if (this.bot.isRemoved() || this.bot.isDeadOrDying()) {
            this.mineJob = null;
            return false;
        }

        if (job.collecting) {
            return this.tickCollecting(job);
        }

        // Finish the break in progress.
        if (job.current != null) {
            if (--job.ticksRemaining > 0) {
                return true;
            }
            Actions.finishBreak(this.bot, job.current, job.face);
            job.broken++;
            // Take this block's drops with it, as it falls. Walking to each one is what used to make
            // a "chop the tree" job take minutes; see Actions.collectBreakDrops.
            job.collected += Actions.collectBreakDrops(this.bot, job.current);
            this.expandMineFrontier(job, job.current);
            job.current = null;
            job.approachTicks = 0;
        }

        // Start the next one.
        while (job.current == null && !job.pending.isEmpty()) {
            BlockPos next = job.pending.pollFirst();
            if (this.bot.level().getBlockState(next).isAir()) {
                continue;
            }
            if (!Actions.canReach(this.bot, next)) {
                // Out of arm's reach - a tree's upper trunk, or a vein around a corner. Walk to it
                // rather than skipping it, because skipping is how a "chop the tree" job leaves the
                // top half of the tree standing.
                if (!this.approachForMining(next)) {
                    if (++job.approachTicks > MINE_APPROACH_TIMEOUT_TICKS) {
                        job.approachTicks = 0;
                        job.unreachable++;
                        continue; // genuinely unreachable; leave it and move on
                    }
                    job.pending.addFirst(next);
                    return true;
                }
                job.pending.addFirst(next);
                return true;
            }

            int ticks = Actions.ticksToBreak(this.bot, next);
            if (ticks == Integer.MAX_VALUE) {
                continue; // not breakable with what we are holding
            }
            Actions.Result started = Actions.startBreak(this.bot, next, Actions.faceToward(this.bot, next));
            if (!started.success()) {
                continue;
            }
            job.current = next;
            job.face = Actions.faceToward(this.bot, next);
            job.ticksRemaining = Math.min(ticks, 20 * 60);
            return true;
        }

        if (job.current != null) {
            return true;
        }

        // Nothing left to break. Whatever the pack refused is still on the ground, so look - but the
        // normal case is that there is nothing to look for.
        this.stopMoving();
        job.collecting = true;
        job.collectTarget = null;
        job.collectTicks = 0;
        if (LOG.isInfoEnabled()) {
            LOG.info("Bot {} finished breaking {} block(s); {} item(s) went straight into the pack",
                    this.bot.getName().getString(), job.broken, job.collected);
        }
        return true;
    }

    /**
     * Walk towards a block that is out of reach.
     *
     * @return true if a route was started
     */
    private boolean approachForMining(BlockPos target) {
        var manager = com.melody.mcagent.rt.Agent.botManager();
        var handle = manager == null ? null : manager.get(this.bot.getName().getString());
        if (handle == null) {
            return false;
        }
        if (handle.movement().hasTarget()) {
            return true; // already on our way
        }
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        // Stand beside or below it: a trunk is chopped from the ground it grows out of.
        BlockPos stand = com.melody.mcagent.rt.path.PathFinder.resolveGoal(
                level, this.bot.blockPosition(), target.below());
        if (stand == null) {
            stand = com.melody.mcagent.rt.path.PathFinder.resolveGoal(
                    level, this.bot.blockPosition(), target);
        }
        return stand != null && handle.movement().setPathTarget(stand, 24)
                == com.melody.mcagent.rt.bot.MovementDriver.Plan.FOUND;
    }

    /** Add the neighbours of a freshly broken block to the job's frontier. */
    private void expandMineFrontier(MineJob job, BlockPos broken) {
        if (job.type == null) {
            return;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = broken.relative(dir);
            if (!job.known.add(neighbour)) {
                continue;
            }
            // Stay within the requested range of where the job started, so a tree cannot lead the
            // bot across the forest.
            if (neighbour.distSqr(job.origin) > (double) job.radius * job.radius) {
                continue;
            }
            if (this.bot.level().getBlockState(neighbour).is(job.type)) {
                job.pending.addLast(neighbour);
            }
        }
    }

    /**
     * Walk to whatever the job left on the ground.
     *
     * <p>A fallback, not the normal path: the drops of each break are moved straight into the pack as
     * the block falls ({@link Actions#collectBreakDrops}), so this phase usually finds nothing and
     * ends on its first tick. What it still exists for is the case that cannot be handled that way -
     * a full inventory that refused part of a stack, or a drop a block spawned out of arm's reach.
     *
     * <p>Vanilla collects items simply by the player being near them, so there is nothing to do here
     * except get the bot there — and only for the job's own leftovers, never for whatever else
     * happens to be lying around. Walking to a stranger's dropped diamond is not collecting, it is
     * taking someone else's things.
     */
    private boolean tickCollecting(MineJob job) {
        // Anything still on the ground near where the job happened.
        double range = Math.max(6.0D, job.radius + 4.0D);
        List<net.minecraft.world.entity.item.ItemEntity> nearby = new ArrayList<>(
                this.bot.level().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        this.bot.getBoundingBox().inflate(range)));
        // Only what this job could have dropped. A block drop is created at age 0, so an item that is
        // older than the job itself was already on the ground when the job started - another player's
        // drop, or something the bot threw away - and walking across the world to fetch it is not what
        // the job was asked to do. (The same reasoning as the age filter in
        // Actions.collectBreakDrops, applied to the whole job rather than to one break.)
        if (job.startTick >= 0) {
            long jobAge = this.bot.level().getGameTime() - job.startTick;
            nearby.removeIf(drop -> drop.getAge() > jobAge + 2);
        }
        if (nearby.isEmpty()) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Bot {} has nothing left to collect ({} item(s) went into the pack as they "
                        + "dropped); job done", this.bot.getName().getString(), job.collected);
            }
            job.collecting = false;
            this.finishMineJob(job);
            return false;
        }


        // A full pack cannot collect these regardless of how long or how accurately we stand on
        // them. End immediately instead of burning 60 ticks after every mined block. Valuable ore
        // already got one deterministic chance to displace safe junk in collectBreakDrops().
        boolean anythingFits = nearby.stream()
                .anyMatch(drop -> Actions.canFitInventory(this.bot, drop.getItem()));
        if (!anythingFits) {
            LOG.info("Bot {} cannot fit {} dropped item stack(s); leaving them immediately instead "
                    + "of waiting beside them", this.bot.getName().getString(), nearby.size());
            job.collecting = false;
            this.finishMineJob(job);
            return false;
        }

        // Vanilla picks items up when the player walks within about a block, so aim at the nearest.
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var drop : nearby) {
            double distance = this.bot.distanceToSqr(drop);
            if (distance < best) {
                best = distance;
                nearest = drop;
            }
        }
        if (nearest == null) {
            this.finishMineJob(job);
            return false;
        }

        if (best <= 2.25D) {
            // Already on top of it; vanilla will collect it this tick or next.
            //
            // Unless the pack is full, in which case "this tick or next" never comes and this branch
            // would return true forever: the bot stands next to a drop it cannot pick up and the job
            // never ends. Now that a full pack is the expected reason for a leftover, that has to be
            // survivable - so the count keeps running and the job gives up after a few seconds.
            job.collectTicks++;
            if (job.collectTicks > MINE_COLLECT_STANDING_TICKS) {
                LOG.info("Bot {} stood next to {} dropped item(s) for {} ticks without picking them "
                        + "up (pack full?); leaving them and moving on",
                        this.bot.getName().getString(), nearby.size(), MINE_COLLECT_STANDING_TICKS);
                job.collecting = false;
                this.finishMineJob(job);
                return false;
            }
            return true;
        }

        job.collectTicks++;
        if (job.collectTicks > MINE_COLLECT_TIMEOUT_TICKS) {
            // Cannot get to it (in a hole, behind a wall). Give up rather than spin.
            LOG.info("Bot {} could not reach a dropped item within {} ticks; moving on",
                    this.bot.getName().getString(), MINE_COLLECT_TIMEOUT_TICKS);
            job.collecting = false;
            this.finishMineJob(job);
            return false;
        }

        var manager = com.melody.mcagent.rt.Agent.botManager();
        var handle = manager == null ? null : manager.get(this.bot.getName().getString());
        if (handle != null) {
            Vec3 where = nearest.position();
            if (job.collectTarget == null
                    || job.collectTarget.distanceToSqr(where) > 1.0D
                    || !handle.movement().hasTarget()) {
                job.collectTarget = where;
                handle.movement().setPathTarget(nearest.blockPosition(), 24);
            }
        }
        return true;
    }

    /** Close out a breaking job and report what it produced. */
    private void finishMineJob(MineJob job) {
        this.mineJob = null;
        this.stopMoving();
        List<net.minecraft.world.entity.item.ItemEntity> left =
                this.bot.level().getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        this.bot.getBoundingBox().inflate(3.0D));
        String report = "broke " + job.broken + " block(s)";
        if (job.collected > 0) {
            report += ", " + job.collected + " item(s) went into your pack as they dropped";
        }
        if (!left.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (var drop : left) {
                if (items.length() > 0) {
                    items.append(", ");
                }
                items.append(drop.getItem().getCount()).append("x ")
                     .append(drop.getItem().getHoverName().getString());
            }
            report += "; still on the ground nearby: " + items;
        }
        this.actionReports.addLast(report);
        if (job.broken == 0 && job.unreachable > 0 && !this.queue.isEmpty()) {
            int discarded = this.queue.size();
            this.queue.clear();
            this.abortQueueIfMovementFails = false;
            this.cooldownTicks = 0;
            this.actionReports.addLast("mining could not get within reach of "
                    + job.origin.toShortString() + "; discarded " + discarded
                    + " dependent step(s) instead of waiting on more unreachable coordinates");
        }
    }

    // --- tool execution -------------------------------------------------------------------------

    /** Execute one tool call on the server thread and describe the outcome back to the model. */
    private String execute(LlmClient.ToolCall call) {
        String name = call.name();
        JsonObject args = call.arguments();

        // Tool execution always happens on the server thread, but the bot can be gone or paused
        // by the time a reply arrives.
        if (this.bot.isRemoved() || this.bot.hasDisconnected()) {
            return "failed: this bot is no longer in the world";
        }
        if (this.paused) {
            return "failed: this bot is paused and cannot act";
        }

        try {
            switch (name) {
                case "plan":
                    return this.schedulePlan(args);

                case "observe": {
                    // Reuse a recent observation only when it can still be trusted. Two conditions
                    // must both hold, and the second is the subtle one:
                    //   1. the bot has not moved far since, and
                    //   2. the earlier observation is still present in the transcript.
                    // If compaction has since discarded it, the model genuinely cannot see those
                    // blocks any more, so serving a cached copy would be handing it knowledge that
                    // is no longer in its context - and worse, the model would believe it had just
                    // looked when it had not.
                    int index = this.findRecentObservationInContext();
                    if (index >= 0 && this.bot.blockPosition().distSqr(this.lastObservationPos) <= 4.0D) {
                        return this.history.get(index).content()
                                + "\n(unchanged since you last looked and you have not moved; "
                                + "world changes by others would not show here)";
                    }
                    return this.observeFresh();
                }

                case "goto": {
                    double x = clampCoordinate(arg(args, "x", this.bot.getX()));
                    double z = clampCoordinate(arg(args, "z", this.bot.getZ()));
                    double y = clampCoordinate(arg(args, "y", this.bot.getY()));
                    var handle = com.melody.mcagent.rt.Agent.botManager() == null
                            ? null
                            : com.melody.mcagent.rt.Agent.botManager().get(this.bot.getName().getString());
                    if (handle == null) {
                        return "failed: this bot is not registered";
                    }
                    BlockPos target = BlockPos.containing(x, y, z);
                    double range = Math.sqrt(this.bot.blockPosition().distSqr(target));
                    var plan = handle.movement().setPathTarget(target, GOTO_PLAN_RANGE);
                    return switch (plan) {
                        case FOUND -> this.isMoving()
                                ? String.format(
                                        "walking to (%.0f, %.0f, %.0f) - call observe after you arrive",
                                        x, y, z)
                                : String.format(
                                        "already at or beside (%.0f, %.0f, %.0f); the resolved route "
                                        + "contains no movement, so your position did not change",
                                        x, y, z);
                        case TOO_FAR -> String.format(
                                "failed: (%.0f, %.0f, %.0f) is %.0f blocks away and I plan at most %d "
                                + "blocks at a time. Ask again for a waypoint part of the way there.",
                                x, y, z, range, GOTO_PLAN_RANGE);
                        case BLOCKED -> String.format(
                                "failed: no route to (%.0f, %.0f, %.0f) - something solid is in the way, "
                                + "or you are walled in. Look around, then aim at a spot you can see and "
                                + "actually walk to instead of repeating this one.",
                                x, y, z);
                    };
                }

                case "look_at":
                    return result(Actions.lookAt(this.bot,
                            new Vec3(clampCoordinate(arg(args, "x", 0)),
                                    clampCoordinate(arg(args, "y", 0)),
                                    clampCoordinate(arg(args, "z", 0)))));

                case "say":
                    return this.sayAsTool(string(args, "message", ""));

                case "eat":
                    return this.eat(string(args, "item", ""));

                case "remember": {
                    String outcome = this.memory().remember(
                            string(args, "key", ""), string(args, "value", ""),
                            this.bot.level().getGameTime());
                    // Rewrite the pinned identity message so the note is in front of the model on the
                    // very next request, not only after a restart.
                    this.refreshIdentity();
                    return outcome;
                }

                case "recall": {
                    var found = this.memory().search(string(args, "query", ""));
                    if (found.isEmpty()) {
                        return this.memory().isEmpty()
                                ? "You have not written any notes yet. Use the remember tool to keep "
                                  + "something for later."
                                : "Nothing in your notes matches that.";
                    }
                    StringBuilder recalled = new StringBuilder("You remember:\n");
                    for (var entry : found) {
                        recalled.append("  - ").append(entry.getKey()).append(": ")
                               .append(entry.getValue()).append('\n');
                    }
                    return recalled.toString();
                }

                case "forget": {
                    String outcome = this.memory().forget(string(args, "key", ""));
                    this.refreshIdentity();
                    return outcome;
                }

                case "stop": {
                    var handle = com.melody.mcagent.rt.Agent.botManager() == null
                            ? null
                            : com.melody.mcagent.rt.Agent.botManager().get(this.bot.getName().getString());
                    if (handle != null) {
                        handle.movement().clear();
                    }
                    return "stopped";
                }

                case "return_to_spawn":
                    return this.returnToSpawnNow();

                case "escape_up":
                    return this.escapeUp(string(args, "item", ""));

                case "dig_tunnel":
                    return this.digTunnel(
                            string(args, "direction", ""), string(args, "mode", ""),
                            (int) arg(args, "length", 8), string(args, "item", ""),
                            args.has("new_site") && args.get("new_site").isJsonPrimitive()
                                    && args.get("new_site").getAsBoolean());

                case "mine_checkpoint": {
                    Direction direction = parseHorizontalDirection(string(args, "direction", ""));
                    if (direction == null) {
                        return "failed: invalid mine checkpoint direction";
                    }
                    MineRoute route = new MineRoute(string(args, "dimension", ""),
                            new BlockPos((int) arg(args, "entrance_x", 0),
                                    (int) arg(args, "entrance_y", 0),
                                    (int) arg(args, "entrance_z", 0)),
                            new BlockPos((int) arg(args, "face_x", 0),
                                    (int) arg(args, "face_y", 0),
                                    (int) arg(args, "face_z", 0)), direction);
                    this.saveMineRoute(route);
                    return "updated established mine working face to " + route.face().toShortString();
                }

                case "mine": {
                    if (!this.policy.canBreakBlocks()) {
                        return "failed: you are not allowed to break blocks";
                    }
                    BlockPos pos = blockPos(args);
                    int radius = (int) Math.max(0, Math.min(MAX_MINE_RADIUS, arg(args, "radius", 0)));
                    // The server applies tool economy even if the model names a wasteful pickaxe:
                    // stone for ordinary terrain, iron for ores, diamond tier where required.
                    Actions.Result equip = Actions.equipForMining(this.bot,
                            this.bot.level().getBlockState(pos), string(args, "item", ""));
                    if (equip != null) {
                        return "failed: " + equip.message();
                    }
                    return this.startMine(pos, radius);
                }

                case "hold": {
                    String wanted = string(args, "item", "");
                    if (wanted.isBlank()) {
                        return "failed: name the item you want to hold";
                    }
                    return result(Actions.holdItem(this.bot, wanted));
                }

                case "discard": {
                    String wanted = string(args, "item", "");
                    int count = (int) Math.max(1, Math.min(2304, arg(args, "count", 1)));
                    return result(Actions.discardInventoryItem(this.bot, wanted, count));
                }

                case "pickup": {
                    int radius = (int) Math.max(1, Math.min(32, arg(args, "radius", 8)));
                    return this.pickUpNearby(radius);
                }

                case "sleep": {
                    if (this.bot.isSleeping()) {
                        return "you are already asleep";
                    }
                    BlockPos bed = Actions.findBed(this.bot, 16);
                    if (bed == null) {
                        return "failed: there is no bed within 16 blocks. Beds are inside houses - "
                                + "look around, or place one if you are carrying it.";
                    }
                    if (!Actions.canReach(this.bot, bed)) {
                        var handle = com.melody.mcagent.rt.Agent.botManager() == null ? null
                                : com.melody.mcagent.rt.Agent.botManager()
                                        .get(this.bot.getName().getString());
                        if (handle == null) {
                            return "failed: this bot is not registered";
                        }
                        var plan = handle.movement().setPathTarget(bed, 48);
                        if (plan != com.melody.mcagent.rt.bot.MovementDriver.Plan.FOUND) {
                            return "failed: no route to the bed at " + bed.toShortString();
                        }
                        this.pendingSleep = bed;
                        return "walking to the bed at " + bed.toShortString()
                                + " and will lie down when you get there";
                    }
                    return result(Actions.sleep(this.bot, bed));
                }

                case "wake":
                    return result(Actions.wakeUp(this.bot));

                case "interrupt": {
                    String what = this.describeCurrentAction();
                    boolean wasBusy = this.isLongActionRunning();
                    int cancelled = this.queue.size();
                    this.abandonCurrentAction();
                    this.abandonPlan("the bot stopped to do something else");
                    this.cooldownTicks = 0;

                    // Even with nothing running there is usually something to clear: a plan whose
                    // steps are stuck behind a walk that is going nowhere. Reporting "there was
                    // nothing to stop" while the bot is plainly wedged is both untrue and useless.
                    StringBuilder reply = new StringBuilder();
                    reply.append(wasBusy ? "stopped " + what : "nothing long-running was in progress");
                    if (cancelled > 0) {
                        reply.append("; cancelled ").append(cancelled).append(" queued step(s)");
                    }
                    reply.append(". You are at ").append(this.bot.blockPosition().toShortString());
                    if (this.ticksMotionless > MOTIONLESS_TICKS_TO_REPORT) {
                        reply.append(", and you have not moved for ")
                             .append(this.ticksMotionless / 20).append(" seconds - if you were trying "
                                     + "to walk, you are physically stuck. Try a different direction, "
                                     + "or break the block you are wedged against.");
                    }
                    reply.append(". Decide what to do now.");
                    return reply.toString();
                }

                case "place": {
                    if (!this.policy.canPlaceBlocks()) {
                        return "failed: you are not allowed to place blocks";
                    }
                    Actions.Result equip = Actions.equipIfRequested(this.bot, string(args, "item", ""));
                    if (equip != null) {
                        return "failed: " + equip.message();
                    }
                    if (this.bot.getMainHandItem().isEmpty()) {
                        return "failed: you are not holding anything to place";
                    }
                    Direction face = parseFace(string(args, "face", "up"));
                    return result(Actions.useOnBlock(this.bot, blockPos(args), face));
                }

                case "use":
                    return result(Actions.useOnBlock(this.bot, blockPos(args), Actions.faceToward(this.bot, blockPos(args))));

                case "open_container": {
                    if (!this.policy.canUseContainers()) {
                        return "failed: you are not allowed to use containers";
                    }
                    BlockPos pos = blockPos(args);
                    Actions.Result opened = Containers.open(this.bot, pos);
                    if (!opened.success()) {
                        return "failed: " + opened.message();
                    }
                    String contents = ObservationBuilder.describeContainer(this.bot, pos);
                    this.rememberContainer(pos, contents);
                    return opened.message() + "\n" + contents;
                }

                case "withdraw": {
                    if (!this.policy.canUseContainers()) {
                        return "failed: you are not allowed to use containers";
                    }
                    return result(Containers.withdraw(this.bot, blockPos(args),
                            string(args, "item", ""), (int) arg(args, "count", 1)));
                }

                case "deposit": {
                    if (!this.policy.canUseContainers()) {
                        return "failed: you are not allowed to use containers";
                    }
                    return result(Containers.deposit(this.bot, blockPos(args),
                            string(args, "item", ""), (int) arg(args, "count", 1)));
                }

                case "craft": {
                    String item = string(args, "item", "");
                    int count = (int) arg(args, "count", 1);
                    return result(Crafting.craft(this.bot, item, count));
                }

                case "craftable_now": {
                    var list = Crafting.craftableNow(this.bot, 15);
                    if (list.isEmpty()) {
                        return "You cannot craft anything with what you are currently carrying.";
                    }
                    return "You could craft: " + String.join(", ", list);
                }

                case "attack": {
                    if (!this.policy.canAttack()) {
                        return "failed: you are not allowed to attack";
                    }
                    Actions.Result equip = Actions.equipIfRequested(this.bot, string(args, "item", ""));
                    if (equip != null) {
                        return "failed: " + equip.message();
                    }
                    String targetName = string(args, "target", "nearest").toLowerCase(java.util.Locale.ROOT);
                    List<Perception.SeenEntity> visible = Perception.visibleEntities(this.bot);
                    Perception.SeenEntity chosen = null;
                    if (targetName.equals("nearest")) {
                        // "Nearest" must never mean "the nearest player". The old implementation
                        // chose the first living entity in a distance-sorted list, so a friendly
                        // player standing beside the bot was a more likely victim than the zombie
                        // behind them. Generic self-defence targets hostiles only.
                        for (Perception.SeenEntity seen : visible) {
                            if (seen.entity() instanceof net.minecraft.world.entity.monster.Monster) {
                                chosen = seen;
                                break;
                            }
                        }
                    } else {
                        for (Perception.SeenEntity seen : visible) {
                            Entity candidate = seen.entity();
                            if (!(candidate instanceof LivingEntity) || !candidate.isAlive()) {
                                continue;
                            }
                            String type = candidate.getType().toShortString()
                                    .toLowerCase(java.util.Locale.ROOT);
                            String display = candidate.getName().getString()
                                    .toLowerCase(java.util.Locale.ROOT);
                            if (type.contains(targetName) || display.contains(targetName)) {
                                chosen = seen;
                                break;
                            }
                        }
                    }
                    if (chosen == null) {
                        return targetName.equals("nearest")
                                ? "failed: no visible hostile creature to fight"
                                : "failed: no visible living entity matching '" + targetName + "'";
                    }
                    return this.startCombat(chosen.entity());
                }

                case "chat_command":
                    return result(Actions.runCommand(this.bot, string(args, "command", ""), this.policy));

                case "find_item":
                case "find_uses": {
                    String query = string(args, "item", "");
                    var knowledge = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
                    if (knowledge == null) {
                        return "the item/recipe index is not ready yet; try again shortly";
                    }
                    return name.equals("find_item")
                            ? knowledge.describeItemAndRecipes(query, 6)
                            : knowledge.describeUses(query, 6);
                }

                default:
                    return "failed: unknown tool '" + name + "'";
            }
        } catch (Throwable t) {
            LOG.error("Tool '{}' threw for bot {}", name, this.bot.getName().getString(), t);
            return "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * The {@code say} tool: the one place where a message either goes out or is refused.
     *
     * <p>Public for the same reason as {@link #mineAsTool}: this is a policy, and a policy that can
     * only be observed through a model in the loop cannot be verified without the model's own
     * behaviour getting in the way.
     *
     * <p>Only a bot talking to itself is ever refused, and only in two ways. Being addressed is
     * never throttled - a bot that ignores a player was the complaint that started this work - but
     * even then, repeating the previous line word for word adds nothing to a conversation. Everything
     * else waits out {@link #UNPROMPTED_CHAT_COOLDOWN}, because the model narrates its plans given
     * the slightest encouragement and players were reading seven of those lines in a row.
     */
    public String sayAsTool(String message) {
        String line = tidySpoken(message);
        if (line.isEmpty()) {
            return "failed: cannot send an empty message";
        }
        if (line.equalsIgnoreCase(this.lastSpokenLine)) {
            return "not sent: that is word for word what you said last time. Say something new, or "
                    + "stay quiet.";
        }

        if (this.spokenThisDecision) {
            return "not sent: you already spoke once in this decision. Do not split one reply into "
                    + "multiple chat lines.";
        }

        boolean addressed = this.directlyAddressed;
        long now = this.bot.level().getGameTime();
        long sinceSpoken = now - this.lastSpokenTick;
        if (!addressed && looksLikeProgressNarration(line)) {
            return "not sent: this is routine progress narration. Keep working silently; only report "
                    + "completion, a decision the player must make, or a blocker you cannot solve.";
        }
        if (!addressed && sinceSpoken < UNPROMPTED_CHAT_COOLDOWN) {
            return "not sent: you spoke " + (sinceSpoken / 20)
                    + "s ago and nobody has spoken to you since. Most turns should be silent - only "
                    + "speak when you have something worth saying.";
        }

        Actions.Result sent = Actions.chat(this.bot, line);
        if (!sent.success()) {
            return "failed: " + sent.message();
        }
        this.recordSpoken(line, now);
        this.spokenThisDecision = true;
        LOG.info("Bot {} said: {}", this.bot.getName().getString(), line);
        return sent.message();
    }

    /**
     * Trim, drop an echoed role label, and cap the length of anything the bot is about to say.
     *
     * <p>This text goes straight into server chat, where a wall of prose is both unreadable and a sign
     * the model forgot it is playing a game rather than writing a report. Model output occasionally
     * also carries a stray label such as {@code "assistant:"}, which would look like a bug if
     * broadcast verbatim.
     */
    private String tidySpoken(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String line = text.trim();
        for (String label : new String[] { "assistant:", "Assistant:", "say:", "Say:" }) {
            if (line.startsWith(label)) {
                line = line.substring(label.length()).trim();
            }
        }
        if (line.length() > MAX_SPOKEN_CHARS) {
            line = line.substring(0, MAX_SPOKEN_CHARS).trim() + "...";
        }
        return line;
    }

    /** Remember what was said and when, which is what the two refusals above are judged against. */
    private void recordSpoken(String line, long now) {
        this.lastSpokenTick = now;
        this.lastSpokenLine = line;
    }

    /** Recognise the repetitive travel/mining status lines models tend to broadcast autonomously. */
    private static boolean looksLikeProgressNarration(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        for (String phrase : new String[] {
                "这就去", "我这就", "正往", "正在往", "在路上", "现在去", "马上去",
                "还没挖到", "目前0个", "来了来了", "等我", "刚卡", "游回来", "出发去",
                "onmyway", "headingto", "goingto", "stilllooking", "haven'tfound",
                "notfoundyet", "currentlywalking", "justgotstuck"
        }) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Say something out loud, on the bot's behalf.
     *
     * <p>Used for a reply the model wrote as prose instead of as an action. Prose is only spoken when
     * the bot was addressed (see {@link #handleCompletion}), so this path is exempt from the
     * unprompted cooldown by construction - but what it says still counts as having spoken, so an
     * unprompted {@code say} immediately afterwards is refused.
     */
    private void speak(@Nullable String text) {
        String line = tidySpoken(text);
        if (line.isEmpty()) {
            return;
        }
        if (line.equalsIgnoreCase(this.lastSpokenLine)) {
            return; // The model repeated itself; saying it twice helps nobody.
        }
        try {
            Actions.Result spoken = Actions.chat(this.bot, line);
            LOG.info("Bot {} said (from reply text): {}", this.bot.getName().getString(), line);
            if (spoken.success()) {
                this.recordSpoken(line, this.bot.level().getGameTime());
            } else {
                LOG.warn("Bot {} could not speak: {}", this.bot.getName().getString(), spoken.message());
            }
        } catch (Throwable t) {
            LOG.warn("Bot {} failed to speak", this.bot.getName().getString(), t);
        }
    }

    /** Speak as the bot. */
    private String eat(String requestedItem) {
        var inventory = this.bot.getInventory();

        if (this.bot.getFoodData().getFoodLevel() >= 20 && requestedItem.isBlank()) {
            return "You are not hungry, so there is nothing to eat.";
        }

        int slot = -1;
        String wanted = requestedItem.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (wanted.isEmpty() || id.equals(wanted)
                    || id.endsWith(":" + wanted)
                    || stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return wanted.isEmpty()
                    ? "failed: you are not carrying any food"
                    : "failed: you are not carrying any '" + requestedItem + "'";
        }

        // Eating is an item use, so the food has to be in the hand that is doing the using. Vanilla
        // then consumes it on its own after the food's use duration; there is nothing to tick here,
        // which is why eating can overlap with walking.
        int hotbarSlot = slot < 9 ? slot : inventory.selected;
        if (slot >= 9) {
            // Move it into the current hotbar slot so the swap is visible and reversible.
            var existing = inventory.getItem(hotbarSlot);
            inventory.setItem(hotbarSlot, inventory.getItem(slot));
            inventory.setItem(slot, existing);
        }
        inventory.selected = hotbarSlot;

        var food = this.bot.getMainHandItem();
        if (food.isEmpty() || !food.has(net.minecraft.core.component.DataComponents.FOOD)) {
            return "failed: could not move the food into your hand";
        }
        var foodData = food.get(net.minecraft.core.component.DataComponents.FOOD);
        this.bot.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        int ticks = foodData != null ? (int) (foodData.eatSeconds() * 20.0F) : 32;
        return "eating " + food.getHoverName().getString() + " (about " + ticks
                + " ticks) - you can keep walking while you eat";
    }

    /**
     * Record what a container holds, without being asked.
     *
     * <p>This is one of only two things the bot remembers on its own, and it earns the exception:
     * container contents are invisible until opened, so without a note a bot re-opens the same chest
     * every session to answer a question it already answered last time. A player remembers which
     * chest their tools are in; a bot should too.
     *
     * <p>The key is derived from the position, so opening the same chest again updates the note
     * instead of filling memory with duplicates as its contents change.
     */
    private void rememberContainer(BlockPos pos, String contents) {
        String summary = contents.replaceAll("\\s+", " ").trim();
        if (summary.isEmpty()) {
            return;
        }
        this.memory().remember("container at " + pos.toShortString(), summary,
                this.bot.level().getGameTime());
        this.refreshIdentity();
    }

    /**
     * Start breaking a block, or a connected region of the same kind of block.
     *
     * @param radius 0 for just that block, otherwise how far from it the job may spread
     */
    private String startMine(BlockPos pos, int radius) {
        var state = this.bot.level().getBlockState(pos);
        if (state.isAir()) {
            return "failed: there is no block at " + pos.toShortString()
                    + " - it is open air. Your block list shows only what you can actually see; "
                    + "observe again and use a coordinate from it rather than guessing.";
        }
        int ticks = Actions.ticksToBreak(this.bot, pos);
        if (ticks == Integer.MAX_VALUE) {
            return "failed: that block cannot be broken with what you are holding";
        }

        if (!Actions.canReach(this.bot, pos)) {
            // Start the job anyway and let it walk into range; a model aiming at a tree from ten
            // blocks away is behaving reasonably.
            this.mineJob = new MineJob(pos, radius, radius > 0 ? state.getBlock() : null,
                    this.bot.level().getGameTime());
            this.approachForMining(pos);
            return "walking into reach of " + pos.toShortString()
                    + " before breaking it (" + describeTarget(state) + ")";
        }

        Actions.Result started = Actions.startBreak(this.bot, pos, Actions.faceToward(this.bot, pos));
        if (!started.success()) {
            return "failed: " + started.message();
        }
        // Completion is driven from tick() so the break takes real mining time.
        this.mineJob = new MineJob(pos, radius, radius > 0 ? state.getBlock() : null,
                this.bot.level().getGameTime());
        this.mineJob.current = pos;
        this.mineJob.face = Actions.faceToward(this.bot, pos);
        this.mineJob.ticksRemaining = Math.min(ticks, 20 * 60);

        if (radius <= 0) {
            return "started mining " + pos.toShortString() + " (about " + ticks + " ticks)";
        }
        return "started mining " + describeTarget(state) + " at " + pos.toShortString()
                + ", and will keep going through every connected " + describeTarget(state)
                + " within " + radius + " blocks of it - about " + ticks
                + " ticks for this one. Everything it drops goes straight into your pack.";
    }

    /** Walk to any dropped items within range and collect them. */
    private String pickUpNearby(int radius) {
        var drops = this.bot.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                this.bot.getBoundingBox().inflate(radius));
        if (drops.isEmpty()) {
            return "there is nothing on the ground within " + radius + " blocks";
        }
        StringBuilder items = new StringBuilder();
        for (var drop : drops) {
            if (items.length() > 0) {
                items.append(", ");
            }
            items.append(drop.getItem().getCount()).append("x ")
                 .append(drop.getItem().getHoverName().getString());
        }
        // Reuse the breaking job's collection phase: walking to items is the same problem, and
        // vanilla does the actual pickup as soon as the bot is near enough. -1 for the start tick:
        // this job exists precisely to fetch items that were already there, so it must not apply the
        // "only what this job produced" filter the mining jobs use.
        this.mineJob = new MineJob(this.bot.blockPosition(), Math.max(4, radius), null, -1L);
        this.mineJob.collecting = true;
        return "going to pick up " + drops.size() + " drop(s) within " + radius
                + " blocks: " + items;
    }

    private static String describeTarget(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock().getName().getString();
    }

    /** A short description of what the bot is currently busy with, for the prompt and logs. */
    private String describeCurrentAction() {
        CombatJob combat = this.combatJob;
        if (combat != null) {
            return "fighting " + combat.label + " (" + combat.hits + " hit(s) landed, "
                    + Math.max(0, (COMBAT_TIMEOUT_TICKS - combat.ticks) / 20)
                    + " seconds before giving up)";
        }
        MineJob job = this.mineJob;
        if (job != null) {
            if (job.collecting) {
                return "collecting what a mining job left on the ground";
            }
            if (job.current != null) {
                // Name the block. "mining 889, 63, 323" tells the model nothing about what it is
                // doing; a bot asked what it was mining could only answer with coordinates.
                var state = this.bot.level().getBlockState(job.current);
                String what = state.isAir() ? "a block"
                        : state.getBlock().getName().getString();
                return "mining " + what + " at " + job.current.toShortString()
                        + (job.radius > 0
                                ? " (part of a " + job.radius + "-block job, "
                                        + job.broken + " broken so far)"
                                : "")
                        + ", about " + job.ticksRemaining + " ticks left";
            }
            return "working through a mining job (" + job.broken + " broken so far)";
        }
        if (this.isMoving()) {
            var manager = com.melody.mcagent.rt.Agent.botManager();
            var handle = manager == null ? null : manager.get(this.bot.getName().getString());
            Vec3 target = handle == null ? null : handle.movement().getTarget();
            return target == null ? "walking"
                    : String.format("walking to (%.0f, %.0f, %.0f)", target.x, target.y, target.z);
        }
        return "idle";
    }

    /**
     * Find a still-in-context observation result from a previous turn.
     *
     * <p>Returns the index of the most recent tool result that was produced by {@code observe} and
     * that is still in the transcript, or -1. Tool results carry no name, so the result text itself
     * is the only marker — hence the header check.
     */
    private int findRecentObservationInContext() {
        for (int i = this.history.size() - 1; i >= this.pinnedCount; i--) {
            LlmClient.Message message = this.history.get(i);
            if (!"tool".equals(message.role()) || message.content() == null) {
                continue;
            }
            if (message.content().startsWith(OBSERVATION_HEADER)) {
                return i;
            }
        }
        return -1;
    }

    /** Take a fresh observation, remembering where it was taken. */
    private String observeFresh() {
        return this.observeFresh(false);
    }

    private String observeFresh(boolean completeInventory) {
        String observation = ObservationBuilder.describe(
                this.bot, this.observeRadius, completeInventory);
        this.lastObservationPos = this.bot.blockPosition();
        return observation;
    }

    /**
     * The section that tells the model what the bot is already doing, and what became of the steps it
     * started earlier.
     *
     * <p>This is what makes a decision taken <em>during</em> an action meaningful. Without it the
     * model would be asked "what next?" with no way to know that the bot is halfway through felling a
     * tree, and would reasonably assume its previous instruction had finished — the classic cause of
     * a bot cheerfully issuing a new order that silently queues behind the old one.
     *
     * <p>Reports are drained once shown. They describe events, not state; repeating them every turn
     * would clutter the prompt forever.
     */
    private String describeOngoing() {
        StringBuilder sb = new StringBuilder();
        boolean busy = this.isLongActionRunning();

        if (this.ticksMotionless > MOTIONLESS_TICKS_TO_REPORT) {
            sb.append("\n=== YOU ARE NOT MOVING ===\n");
            sb.append("  You have been in ").append(this.bot.blockPosition().toShortString())
              .append(" for ").append(this.ticksMotionless / 20).append(" seconds without moving.\n");
            sb.append("  Nothing you ask for will work until you are free: if you meant to walk, you\n");
            sb.append("  are stuck against something. Try a different direction, or break the block\n");
            sb.append("  in front of you, or call interrupt to clear what you were doing.\n");
        }

        if (busy) {
            sb.append("\n=== WHAT YOU ARE DOING RIGHT NOW ===\n");
            sb.append("  ").append(this.describeCurrentAction()).append('\n');
            sb.append("  Your hands are busy, so anything needing them (mining, placing, opening,\n");
            sb.append("  crafting, walking, fighting) waits until this finishes. Talking, looking, eating and\n");
            sb.append("  remembering happen immediately. Call interrupt if you want to stop it.\n");
        }

        if (!this.queue.isEmpty()) {
            if (!busy) {
                sb.append("\n=== WHAT YOU ARE DOING RIGHT NOW ===\n");
            }
            sb.append("  Waiting to run, in this order:\n");
            for (QueuedCall queued : this.queue) {
                sb.append("    - ").append(queued.call.name())
                  .append('(').append(summarise(queued.call.arguments())).append(")\n");
            }
        }

        if (!this.actionReports.isEmpty()) {
            sb.append("\n=== WHAT HAPPENED SINCE YOU LAST LOOKED ===\n");
            while (!this.actionReports.isEmpty()) {
                sb.append("  - ").append(this.actionReports.pollFirst()).append('\n');
            }
        }
        return sb.toString();
    }

    /** A short, log-safe rendering of tool arguments. */
    private static String summarise(JsonObject args) {
        if (args == null || args.size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.entrySet().forEach(entry -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        });
        String text = sb.toString();
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /** The first line of a tool result, for concise logging. */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline >= 0 ? text.substring(0, newline) : text;
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private static String result(Actions.Result result) {
        return result.success() ? result.message() : "failed: " + result.message();
    }

    private static double arg(JsonObject args, String key, double fallback) {
        try {
            return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String string(JsonObject args, String key, String fallback) {
        try {
            return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Read a block position from model-supplied arguments.
     *
     * <p>Models occasionally emit {@code NaN}, {@code Infinity}, or absurd magnitudes. Those would
     * floor to nonsense and could address a chunk far outside the world, so out-of-range values are
     * clamped to something valid rather than trusted.
     */
    private static BlockPos blockPos(JsonObject args) {
        return BlockPos.containing(
                clampCoordinate(arg(args, "x", 0)),
                clampCoordinate(arg(args, "y", 0)),
                clampCoordinate(arg(args, "z", 0)));
    }

    /** Clamp a possibly non-finite or absurd coordinate into the valid world range. */
    private static double clampCoordinate(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return net.minecraft.util.Mth.clamp(value, -30_000_000.0D, 30_000_000.0D);
    }

    private static Direction parseFace(String face) {
        return switch (face.toLowerCase(java.util.Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.UP;
        };
    }

    @Nullable
    private static Direction parseHorizontalDirection(String direction) {
        return switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    /**
     * Shut the shared executor down, on server stop or on reload.
     *
     * <p>An in-flight model call holds this class loader on its stack, and on a reload that loader
     * is about to be closed: a request allowed to run to its own timeout would finish against
     * classes that no longer exist. Interrupt it instead of waiting the request out.
     */
    public static synchronized void shutdown() {
        ExecutorService current = executor;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                current.shutdownNow();
                current.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Clear the field so a later server start rebuilds it (see executor()).
        executor = null;
    }

    /** Exposed for diagnostics: a summary of the conversation so far. */
    public Map<String, Object> debugState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot", this.bot.getName().getString());
        out.put("thinking", this.thinking.get());
        out.put("historyMessages", this.history.size());
        out.put("cooldownTicks", this.cooldownTicks);
        out.put("mining", this.mineJob != null);
        out.put("busy", this.describeCurrentAction());
        out.put("contextTokens", this.estimatedTokens());
        out.put("reportedPromptTokens", this.lastPromptTokens);
        out.put("usageTurns", this.reportedUsageTurns);
        out.put("usagePromptTokens", this.totalPromptTokens);
        out.put("usageCompletionTokens", this.totalCompletionTokens);
        out.put("usageTotalTokens", this.totalTokens);
        out.put("cacheReportedTurns", this.cacheReportedTurns);
        out.put("cachedPromptTokens", this.totalCachedPromptTokens);
        out.put("uncachedPromptTokens", this.totalUncachedPromptTokens);
        out.put("tokenBudget", this.tokenBudget);
        out.put("standingGoal", this.standingGoal == null ? "(none)" : this.standingGoal);
        out.put("paused", this.paused);
        return out;
    }
}
