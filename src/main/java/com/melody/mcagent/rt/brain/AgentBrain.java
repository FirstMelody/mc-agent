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
import com.melody.mcagent.rt.action.Farming;
import com.melody.mcagent.rt.action.Stations;
import com.melody.mcagent.rt.bot.MovementDriver;
import com.melody.mcagent.rt.llm.LlmClient;
import com.melody.mcagent.rt.llm.JevClient;
import com.melody.mcagent.rt.path.MiningAccessPlanner;
import com.melody.mcagent.rt.perception.Crops;
import com.melody.mcagent.rt.perception.FarmSite;
import com.melody.mcagent.rt.perception.ObservationBuilder;
import com.melody.mcagent.rt.perception.Perception;
import com.melody.mcagent.rt.perception.PlayerStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
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
    /** Prefetch only while the final physical step runs and nothing remains queued. */
    private static final int PLAN_LOW_WATERMARK = 1;
    /**
     * Give physical work time to change the world before prefetching another plan.
     *
     * <p>Five ticks caused a fast endpoint to issue a new decision several times during every
     * single block break, filling logs and queues with observations of effectively identical state.
     * Two seconds still overlaps a normal remote LLM request with long work without turning a
     * mining controller into an API-call loop.
     */
    private static final int PLAN_PREFETCH_DELAY_TICKS = 40;
    /** Repeated actionless idle turns retry less often before waiting for an external change. */
    private static final int[] NO_ACTION_RETRY_TICKS = {60, 200, 600, 1200, 3600, 6000};
    /**
     * How many consecutive decisions may achieve nothing before the bot starts waiting.
     *
     * <p>One is normal - the model tried something the world said no to, and it deserves a fresh look
     * at the new state. Two in a row means it is re-deriving work that already failed, which is what a
     * planning loop looks like from the provider's invoice.
     */
    private static final int NO_PROGRESS_BACKOFF_STREAK = 2;
    /**
     * Rungs of that ladder after which an objective the bot cannot move is dropped and reported.
     *
     * <p>Waiting longer is the right answer to a hiccup and the wrong answer to a task that has no
     * solution: production spent 231 model calls and 3.06M prompt tokens in twenty-five minutes
     * retrying a repair that needed a diamond the bot did not have. After waits of 60 and 200 ticks
     * (thirteen seconds) across half a dozen turns with nothing whatsoever to show, the bot cancels
     * the work and tells the player what blocked it. New input, or anything it can see changing,
     * still brings it back - and being told beats being billed.
     */
    private static final int NO_PROGRESS_GIVE_UP_RUNG = 2;
    /** How often the field's ripeness is considered at all, and how long between typed questions. */
    private static final int FARM_RIPE_CHECK_INTERVAL_TICKS = 200;
    private static final int FARM_RIPE_PING_COOLDOWN_TICKS = 2400;
    /** With no crop in sight, this long since the last harvest means "they should be ripe by now". */
    private static final int FARM_RIPE_ESTIMATE_TICKS = 7200;
    /** Fraction of the visible field that counts as "roughly all ripe". */
    private static final double FARM_RIPE_FRACTION = 0.6D;
    /** A refused harvest is not a race: the crop is still there, so come back rather than spin. */
    private static final int FARM_HARVEST_BACKOFF_TICKS = 200;
    /** Tools that make sense as deterministic steps inside a server-side plan. */
    private static final List<String> PLANNABLE_TOOLS = List.of(
            "observe", "goto", "look_at", "say", "eat", "stop", "mine", "hold", "discard", "pickup",
            "sleep", "wake", "place", "use", "open_container", "withdraw", "deposit",
            "craft", "craftable_now", "attack", "chat_command", "find_item", "find_uses",
            "remember", "recall", "forget", "find_resource", "mine_resource", "dig_tunnel",
            "escape_up", "return_to_spawn", "repair", "enchant", "farm", "build_farm",
            // The backpack tools belong here and were simply missing. Production, 11:48: the model
            // answered "wear the helmet in my backpack, then place the anvil and repair the pickaxe"
            // with plan([backpack_take(diamond_helmet), hold(diamond_helmet)]) - the natural two-step
            // sequence - and got "plan step 1 uses unsupported action 'backpack_take'" every time, so
            // the hat stayed in the pack and the whole plan was refused before anything ran. Taking
            // something out and putting something in are instant local moves, which is exactly what a
            // plan step is for; the read-only "backpack" is already treated as a look by isConcurrent.
            "backpack", "backpack_take", "backpack_put", "backpack_wear", "backpack_sort",
            "backpack_upgrade");
    /** One escape call stays small enough to fit beside other queued work. */
    private static final int MAX_ESCAPE_STEPS = 8;
    /**
     * Tools the model is offered but a plan may not contain, on purpose.
     *
     * <p>{@code plan} cannot nest, {@code interrupt} exists to stop the action a plan is running,
     * {@code complete_goal} ends the task the plan belongs to, and {@code start_mining} hands the bot
     * to the runtime-owned skill. Everything else the model can call must be either plannable or
     * listed here - see {@link #warnAboutUnplannableTools}.
     */
    private static final List<String> PLANNING_EXCLUSIONS =
            List.of("plan", "interrupt", "complete_goal", "abandon_goal", "start_mining");
    /** Blocks queued per branch-mining run: long enough to make progress, short enough to interrupt. */
    private static final int MAX_BRANCH_RUN_BLOCKS = 4;
    /** Default main-corridor blocks between branches. The scan reaches a few blocks either side. */
    private static final int BRANCH_DEFAULT_SPACING = 6;
    /** Default length of one branch, and how many branches make a trip. */
    private static final int BRANCH_DEFAULT_LENGTH = 16;
    private static final int BRANCH_DEFAULT_MAX = 8;
    /** How often the occlusion-tolerant scan looks for ore around the face, and how far. */
    private static final int BRANCH_ORE_SCAN_TICKS = 20;
    private static final int BRANCH_ORE_RADIUS = 12;
    /** Vein radius used when the pattern diverts to an ore it can see through rock. */
    private static final int BRANCH_ORE_VEIN_RADIUS = 6;
    /** Remaining uses below which the tool counts as worn and Jev is asked about it. */
    private static final int BRANCH_TOOL_DURABILITY_FLOOR = 20;
    /** Most open spaces one trip will pave over before turning the corridor instead. */
    private static final int BRANCH_MAX_BRIDGES = 8;
    /** How close a monster has to be to interrupt the trip, and how far the bot can see one. */
    private static final double BRANCH_THREAT_RADIUS = 8.0D;
    /** After an answer, do not ask about the same kind of interruption again for a while. */
    private static final int BRANCH_INTERRUPT_QUIET_TICKS = 600;
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
    /** Grace after STOP_DESTROY_BLOCK for vanilla/mod hooks to publish the block change. */
    private static final int MINE_BREAK_VERIFY_TICKS = 10;
    /** X-ray perception is shallow; access excavation is deliberately bounded to match it. */
    private static final int MINE_ACCESS_RANGE = 10;
    /** The base/bed neighbourhood in which surface terrain must remain intact. */
    private static final int HOME_TERRAIN_RADIUS = 96;
    /** Protect the visible surface and the first few supporting layers from ad-hoc excavation. */
    private static final int HOME_SURFACE_DEPTH = 4;

    /**
     * Whether the runtime-owned persistent mining skill is offered at all.
     *
     * <p>Only ever switched off by {@code MCAGENT_MINING_GOAL_TEST}'s positive control, and by the
     * same mechanism the structure guard uses ({@code MCAGENT_STRUCTURE_GUARD}) — an env variable,
     * so no server config can turn a production bot's mining skill off by accident. With the skill
     * gone the model has no single-call trip and falls back to per-block plans, which is exactly the
     * behaviour the skill exists to replace; the control run exists to show the test can tell those
     * two apart.
     */
    public static boolean miningSkillEnabled() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_MINING_GOAL"));
    }

    /**
     * Whether a watchdog-forced turn keeps the chat it was shown for the next turn.
     *
     * <p>Only ever switched off by {@code MCAGENT_CHAT_INVOKE_TEST}'s positive control, the same way
     * the structure guard and the mining skill are gated. A production player hit the opposite
     * behaviour: the endpoint hung, so the bot never got a conversational turn, the watchdog forced a
     * recovery turn, and that turn marked both of the player's questions read. The questions were
     * shown to a model that was being told it was stuck, and then destroyed - the player saw a bot
     * that ignored them twice, which is indistinguishable from a dead one.
     */
    public static boolean keepChatThroughForcedTurn() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_CHAT_KEEP"));
    }
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
     * Confidence a mining recovery needs before the bot physically acts on it.
     *
     * <p>Calibrated the same way as the speech gate: eight realistic failure states were asked of the
     * real model. Its confidence for defensible answers landed at 0.50-0.85 (median ~0.64), a flatter
     * band than chat's 0.89-0.99, because choosing a recovery is genuinely harder than deciding
     * whether to talk.
     *
     * <p>The cost asymmetry is also the other way round here, which is what sets the floor: abstaining
     * costs a whole planning turn (~12k tokens), while acting costs one bounded, whitelisted,
     * logged action - a single retry per target, a marker, a walk back along known breadcrumbs. So the
     * floor sits at 0.6 rather than chat's 0.85, and the one answer that was clearly wrong (retrying a
     * block whose break was rejected) is excluded deterministically instead of by confidence.
     */
    private static final double MIN_ACTIVE_JEV_CONFIDENCE = 0.6D;
    /** A skipped ore candidate may be reconsidered after five minutes or when its block changes. */
    private static final long JEV_SKIP_TARGET_TICKS = 6000L;
    // The speech gate deliberately has no confidence floor on the silence direction. It used to: a
    // STAY_SILENT below 0.85 was discarded and the turn fell through to the planning model, which is
    // how production got its chatter back - the gate answered STAY_SILENT at 0.14 and 0.02 on
    // messages nobody was waiting on, both answers were thrown away, and a 12k-token turn produced
    // "一组太多了…" and "好，正在往下挖，挖到就给你". A floor made sense when the gate was the only
    // thing standing between a player and silence; it is not. Every question put to this bot is
    // routed to the planning model before the gate is even asked (see trySpeechGate), so a message
    // that reaches the gate is one the player is not waiting on, and the model's own choice is the
    // best answer available. The message stays in the chat log either way, so the next turn still
    // sees it and can act on it.
    /** Pause after the gate chose silence, so the bot gets on with its work instead of re-deciding. */
    private static final int GATE_SILENCE_COOLDOWN_TICKS = 40;
    /**
     * Confidence a CONTINUE needs before it may skip a planning turn.
     *
     * <p>Same calibration discipline as the speech gate: the floor is set by measuring real answers,
     * not by taste. On the gate's nine-case battery the two outcomes separated cleanly - cases that
     * should be left alone landed at 0.89-0.99 and cases that genuinely needed the planning model at
     * 0.47-0.77 - and the gate's floor of 0.85 sits in that gap. A routing answer is the same kind of
     * question asked about work instead of talk, so it inherits the same floor rather than a new
     * guess.
     *
     * <p>The asymmetry is why it is not lower. Skipping the planning turn while work is genuinely in
     * flight is cheap and reversible (the next idle tick asks again), but skipping it when the bot has
     * nothing to carry on with is a bot that stands still - so a low-confidence CONTINUE always
     * escalates, and the empty-queue case never reaches the adviser at all.
     */
    private static final double MIN_ACTIVE_ROUTE_CONFIDENCE = 0.85D;
    /**
     * Pause after routing said CONTINUE, so the bot works instead of asking again immediately.
     *
     * <p>Deliberately the same cadence as the existing busy prefetch ({@code PLAN_PREFETCH_DELAY_TICKS}):
     * the routing layer changes what a decision costs, not how often the bot re-examines what it is
     * doing. Without a pause the latch would be released and re-taken every tick.
     */
    private static final int ROUTE_CONTINUE_COOLDOWN_TICKS = 40;
    /** A confident CONTINUE covers the same physical job for at most one minute. */
    private static final int ROUTE_LEASE_TICKS = 1200;
    /** Ceiling on the state string a routing decision is asked with: it is not an observation. */
    private static final int ROUTING_STATE_MAX_CHARS = 600;
    /** How far back "the player sent this same line again" is counted, in ticks (two minutes). */
    private static final int CHAT_REPEAT_WINDOW_TICKS = 2400;

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
    /** Optional System One adviser. Shadow/active behaviour is selected in its runtime config. */
    @Nullable
    private volatile JevClient jevClient;
    /** One automatic access retry per exact target prevents an adviser-driven retry loop. */
    private final java.util.Set<BlockPos> jevRetriedTargets = new java.util.HashSet<>();
    /**
     * How many times each exact target has failed, so a recovery decision can tell a first failure
     * from a third one. Bounded: the oldest entries are dropped rather than growing with the world.
     */
    private final java.util.Map<BlockPos, Integer> mineFailureCounts = new java.util.LinkedHashMap<>();
    /**
     * Exact targets Jev told the miner to skip, scoped by dimension and bounded by time.
     *
     * <p>The old SKIP_TARGET handler only wrote a sentence to the next prompt. The next
     * {@code find_resource}/{@code mine_resource} scan therefore selected the same nearest ore and
     * immediately failed again. Keeping the block type lets a changed world invalidate the marker;
     * the TTL prevents a temporary access failure from blacklisting a coordinate forever.
     */
    private final java.util.Map<String, SkippedMiningTarget> jevSkippedTargets =
            new java.util.LinkedHashMap<>();

    private record SkippedMiningTarget(net.minecraft.world.level.block.Block block, long expiresAt) {
    }
    /**
     * Blocks this bot removed itself in the last {@link #SELF_CLEARED_TICKS}, newest last.
     *
     * <p>Two clearance systems can name the same cell: the tunnel macro and the mining access route
     * queue the blocks they mean to clear, and a {@link MineJob} clears its own approach blocker on
     * the fly while removing an occluded target. When the second one ran, the block was air, the
     * step returned {@code failed: there is no block at ...}, and because every macro step aborts the
     * plan on failure the whole tunnel died - production measured a 12-block tunnel abandoned after
     * two blocks, every 40 seconds, each one costing a full planning turn. A queued step that names a
     * block this bot just removed is not a failure: its intended end state already holds.
     *
     * <p>Only blocks the bot itself broke are in here, so a coordinate the model invented still fails
     * honestly. Bounded and pruned by age, not by count: a plan step can be queued long before it
     * runs, and the window has to outlive that.
     */
    private final java.util.Map<BlockPos, Long> selfCleared = new java.util.LinkedHashMap<>();
    /** How long a block this bot broke still counts as "already done". 1200 ticks = 60 s. */
    private static final long SELF_CLEARED_TICKS = 1200L;
    /**
     * Off only for the harness's positive control ({@code MCAGENT_SELFCLEARED=off}), which has to see
     * the plan die on an already-cleared step to prove the test can detect the bug at all.
     */
    private static final boolean SELF_CLEARED_ENABLED =
            !"off".equalsIgnoreCase(System.getenv("MCAGENT_SELFCLEARED"));
    /** True while a speech-gate answer is in flight, so one message is gated once. */
    private volatile boolean speechGatePending;
    /**
     * When the in-flight mining-recovery request was fired, or -1.
     *
     * <p>A recovery answer arrives about a second after the failure, and the bot starts planning the
     * moment the job ends - a turn that takes ten to fifteen seconds. Without holding the ordinary
     * decision for that second, every recovery arrives while the bot is "thinking" and is discarded as
     * stale, which is exactly what production showed: {@code not_applied=newer_work mineJob=false
     * combat=false moving=false queue=0 thinking=true}. The cheap layer could never act at all.
     */
    private volatile long jevRecoveryRequestedAt = -1L;
    /**
     * How long an ordinary decision waits for an in-flight recovery, in ticks (ten seconds).
     *
     * <p>Derived from the client's own request timeout (8 s in production) plus a margin, not guessed.
     * A three-second bound looked reasonable and was wrong: production kept dropping answers with
     * {@code not_applied=newer_work … thinking=true} - seven of them with confidence above the floor -
     * because a cold start on the gateway can take six seconds, by which time the hold had expired and
     * the bot was already planning. Waiting longer is cheap here: the guard only asks when there is
     * work in flight, so the bot keeps executing its queue while it waits, and a planning turn it may
     * replace costs ten to fifteen seconds and ~12k tokens.
     */
    private static final int RECOVERY_HOLD_TICKS = 200;
    /** How long the runtime waits before carrying the same standing goal again. */
    private static final int IDLE_CONTINUATION_COOLDOWN_TICKS = 1800;
    /** How often the cheap-layer counters are written to the log, so a reduction is measurable. */
    private static final int STATS_WINDOW_TICKS = 6000;
    /**
     * Which input the decision now in flight started from.
     *
     * <p>Set at every call site that starts a decision and consumed - read, then reset to
     * {@link Trigger#IDLE} - at the top of {@link #startDecision()}. A marker that outlived its
     * decision would mislabel an unrelated one, and the label decides whether the cheap layer may
     * answer at all.
     */
    private volatile Trigger pendingTrigger = Trigger.IDLE;
    /**
     * The trigger of the decision currently being built.
     *
     * <p>{@link #startDecision()} consumes {@link #pendingTrigger} immediately, so a later step of the
     * same turn cannot ask what started it. That question is worth answering: a turn the stuck
     * watchdog forced is a recovery turn, not a conversational one, and it must not be the turn that
     * consumes a player's message.
     */
    private Trigger currentTrigger = Trigger.IDLE;
    /** What the routing layer did, per trigger. Mutated on the server thread only. */
    private final Map<Trigger, RouteCounts> routingCounts =
            new java.util.EnumMap<>(Trigger.class);
    /**
     * Game time of the last decision turn that completed, or -1 before the first one.
     *
     * <p>Distinct from {@code ticksSinceDecision}, which the watchdog also resets while the bot is
     * busy: a routing decision has to be told how long the bot has genuinely been without a fresh
     * plan, not how long since anything at all happened.
     */
    private long lastCompletedTurnTick = -1L;
    /**
     * Whether the runtime may start a trip for the standing goal when the bot goes idle.
     *
     * <p>Gated so a wrong guess cannot become a loop: a trip that ends immediately (a full pack, no
     * reachable ore) would otherwise be restarted on the next tick forever.
     */
    private long lastIdleContinuationTick = Long.MIN_VALUE / 2;
    /**
     * What the last persistent mining trip actually collected.
     *
     * <p>The idle continuation refuses to start another trip when the previous one came back with
     * nothing. Production showed exactly why: three trips in a row dug four to twelve tunnel chunks,
     * spent three minutes each and collected zero ore - the runtime was repeating a search that had
     * already been shown not to work here. Escalating to the planning model instead lets it try a
     * different place, which is the one thing the runtime cannot decide on its own.
     */
    private int lastMiningGoalGained = -1;
    /**
     * Earliest tick at which an idle zero-yield standing goal may buy another planning turn.
     *
     * <p>A zero-yield mining trip used to fall straight into a several-second planning loop: every
     * turn rediscovered the same standing goal, started equivalent work, and came back empty. The
     * first re-plan is still immediate; later attempts are rate-limited until new input arrives.
     */
    private long zeroYieldNextPlannerTick = Long.MIN_VALUE / 2;
    /** Which bounded backoff to use after the next zero-yield planning attempt. */
    private int zeroYieldBackoffIndex;
    private static final int[] ZERO_YIELD_BACKOFF_TICKS = {1200, 3600, 6000};
    /** Key and expiry of the physical work for which Jev already said CONTINUE. */
    @Nullable
    private String routeLeaseKey;
    private long routeLeaseExpiresAt = -1L;
    /**
     * Rolling window counters, logged every {@link #STATS_WINDOW_TICKS}.
     *
     * <p>"Did the cheap layer reduce LLM calls?" was unanswerable from the log: a call that did not
     * happen leaves no line, so the only visible number was the absolute call rate, which moves with
     * how much the bot is doing. These are the numbers that answer it - real calls, decisions the
     * routing layer intercepted, idle decisions that still became calls, and idle decisions the
     * runtime carried itself.
     */
    private int statsPlannerRequests;
    private int statsPlannerSucceeded;
    private int statsPlannerFailed;
    private int statsPlannerNoAction;
    private int noActionStreak;
    private long noActionWakeFingerprint;
    /**
     * Consecutive decisions that achieved nothing, and how far up the wait ladder that has climbed.
     *
     * <p>Separate from {@link #noActionStreak}, which only sees turns that returned no tool call at
     * all: a turn that plans a walk and a break does have tool calls, so a bot whose every plan died
     * on the same refusal kept its streak at zero and bought a fresh 13k-token turn every few seconds.
     *
     * <p>The rung is deliberately its own counter. Production, 11:48-12:11: a standing goal that
     * could not be finished (the repair needed a diamond the bot did not have) fired the backoff
     * twenty-three times and burned 231 model calls and 3.06M prompt tokens in twenty-five minutes,
     * because every ordinary turn reset the ladder index in {@link #handleCompletion} - so every one
     * of those twenty-three waits was the first rung, sixty ticks, three seconds.
     */
    private int noProgressStreak;
    private int noProgressRung;
    /** The visible state (dimension, position, carried items, backpack) when this decision started. */
    private long progressFingerprint;
    /**
     * Whether the turn just run asked for work and had every answered call refused.
     *
     * <p>Recorded inside {@link #runTurn} because the result array it is read from is cleared by
     * {@link #flushResults} before the caller gets control back - reading it afterwards threw
     * NullPointerException inside handleCompletion, which the catch there turned into a 100-tick
     * cooldown and a log line the harness saw as "Error handling LLM completion".
     */
    private boolean turnEntirelyRefused;
    /** The first refusal of the turn, so an abandoned objective can name what actually failed. */
    private String turnRefusalDetail = "";
    /** One-shot guard for the offered-vs-plannable consistency warning. */
    private boolean unplannableToolsChecked;
    /** A chat, explicit think or physical state change may start work without a standing goal. */
    private boolean unassignedWorkActive;
    /** Read-only answers already returned in the same idle state, even if other queries interleave. */
    private final java.util.Set<String> idleReadSignatures = new java.util.HashSet<>();
    private long idleReadStateFingerprint;
    private int statsJevRequests;
    private int statsIntercepted;
    private int statsSpeechAvoided;
    private int statsLeaseContinuations;
    private int statsBackoffAvoided;
    private int statsIdleSkipped;
    private int statsIdleContinuations;
    private int statsEscalated;
    private long statsWindowStartTick = -1L;
    private volatile ActionPolicy policy;
    private final List<LlmClient.Message> history = new ArrayList<>();

    private final AtomicBoolean thinking = new AtomicBoolean(false);
    private boolean initialised;

    /** The block-breaking job in progress, if any. */
    @Nullable
    private MineJob mineJob;

    /** The branch-mining trip in progress, if any. Runtime-owned: it buys no planning turns. */
    @Nullable
    private BranchMineJob branchMine;
    /** Where the last queued branch run ends, so the pattern can move its anchor. */
    @Nullable
    private BlockPos branchRunEnd;
    /** The first cell of a run that had no floor: open space the controller can pave. */
    @Nullable
    private BlockPos branchRefusedFloor;
    /** Open spaces paved since the current trip began. */
    private int branchBridges;

    /**
     * Surface blocks which the deterministic tunnel macro, and only that macro, may remove.
     *
     * <p>Coordinates are issued after the route has passed all safety checks and consumed when the
     * queued mine step begins. Model-provided arguments cannot grant this permission, which keeps the
     * home-terrain rule a server-side invariant rather than another prompt instruction.
     */
    private final java.util.Set<BlockPos> authorisedTunnelClearance = new java.util.HashSet<>();

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

    /**
     * What started the decision now being made.
     *
     * <p>Every input that makes a bot think is labelled, because the cheap decision layer is not
     * allowed to touch all of them equally. Chat is the speech gate's case, an expired cooldown is
     * the routing layer's case, and two triggers are never second-guessed at all - see
     * {@link #startDecision()}.
     */
    public enum Trigger {
        /** A player said something this bot should answer. */
        CHAT,
        /** The ordinary cooldown expired: the bot finished a turn and has to decide what is next. */
        IDLE,
        /** An operator asked for a decision now, via {@code /mcagent think}. */
        COMMAND,
        /** The liveness watchdog fired: no decision has completed for a minute. */
        STUCK,
        /** The bot was just unpaused. */
        RESUME
    }

    /** Per-trigger tally of what the routing layer did, for {@link #debugState()} and diagnostics. */
    private static final class RouteCounts {
        /** Decisions actually sent to the adviser (never counted for the guard or a bypass). */
        int asked;
        /** Confident CONTINUE answers that really did skip a planning turn. */
        int continued;
        /** Decisions that reached the planning model instead. */
        int escalated;
        /** Adviser calls that failed, timed out or were refused by the breaker. */
        int failed;
    }

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

    /** One durable mine entrance, working face, and breadcrumbs through the excavated corridor. */
    private record MineRoute(String dimension, BlockPos entrance, BlockPos face, Direction direction,
                             List<BlockPos> waypoints) {
    }

    /** A long-lived mining trip owned by the runtime rather than by a model-generated step list. */
    private static final class MiningGoal {
        final List<String> priorities;
        final String primary;
        final int requestedAmount;
        final int startingAmount;
        final int maxTunnelChunks;
        final long startedAt;
        int tunnelChunks;
        int resourcesStarted;
        boolean returning;
        boolean returnScheduled;

        MiningGoal(List<String> priorities, String primary, int requestedAmount,
                   int startingAmount, int maxTunnelChunks, long startedAt) {
            this.priorities = List.copyOf(priorities);
            this.primary = primary;
            this.requestedAmount = requestedAmount;
            this.startingAmount = startingAmount;
            this.maxTunnelChunks = maxTunnelChunks;
            this.startedAt = startedAt;
        }
    }

    /**
     * A branch-mining trip: one main corridor at a fixed level, with perpendicular branches every
     * few blocks.
     *
     * <p>The shape is the point. A single tunnel only sees the line it cuts; a corridor with branches
     * sweeping off it covers a whole band of a level, and the bot's occlusion-tolerant scan reaches a
     * few blocks through solid rock either side of every face - so ore between the tunnels is found
     * rather than missed. The pattern is generated as it goes and anchored on {@link #resumeAt},
     * which is also what brings the bot back after it walks off to an ore it spotted.
     *
     * <p>It buys no planning turns at all. Only two things can interrupt it: a bounded typed question
     * to Jev when the trip is disturbed (a mob, a worn tool, a full pack), and - when even that
     * cannot answer - handing the trip back to the model with the reason.
     */
    private static final class BranchMineJob {
        final int targetY;
        final Direction mainDirection;
        final int branchSpacing;
        final int branchLength;
        final int maxBranches;
        final String primary;
        final List<String> priorities;
        final int requestedAmount;
        final int startingAmount;
        final long startedAt;
        /** Main-corridor blocks still to dig before the next branch. */
        int nextBranchIn;
        /** Progress in the branch being dug, and which side of the corridor it is on. */
        int branchBlocksDug;
        int branchSide = 1;
        int branchesDug;
        int mainBlocksDug;
        int resourcesStarted;
        /** The corridor heading, which can be turned once when the level blocks it. */
        Direction heading;
        int headingTurns;
        boolean returning;
        boolean returnScheduled;
        /** The anchor: where the pattern expects the bot, and where it walks back to. */
        @Nullable BlockPos resumeAt;
        /** Where the branch being dug started, so the bot can return to its junction. */
        @Nullable BlockPos branchJunction;
        /** Interrupt bookkeeping: one question at a time, and a quiet period after an answer. */
        boolean interruptPending;
        long interruptQuietUntil;
        /**
         * When the scan last looked for ore. Zero, not {@code Long.MIN_VALUE}: {@code now - MIN_VALUE}
         * overflows to a negative number, and the scan then never runs at all - which is exactly what
         * the harness caught, a trip that dug its pattern perfectly and never looked for ore once.
         */
        long lastOreScanTick;
        boolean scanReported;
        int interruptsAsked;
        int interruptsApplied;
        String lastInterrupt = "";

        BranchMineJob(int targetY, Direction mainDirection, int branchSpacing, int branchLength,
                      int maxBranches, String primary, List<String> priorities, int requestedAmount,
                      int startingAmount, long startedAt) {
            this.targetY = targetY;
            this.mainDirection = mainDirection;
            this.branchSpacing = Math.max(2, branchSpacing);
            this.branchLength = Math.max(2, branchLength);
            this.maxBranches = Math.max(1, maxBranches);
            this.primary = primary;
            this.priorities = List.copyOf(priorities);
            this.requestedAmount = requestedAmount;
            this.startingAmount = startingAmount;
            this.startedAt = startedAt;
            this.heading = mainDirection;
            this.nextBranchIn = this.branchSpacing;
        }
    }

    /**
     * A field the bot keeps.
     *
     * <p>The opposite of {@link MiningGoal} in one respect: a mining trip is finished when the ore
     * is in the pack, and a farm never is. Crops ripen on their own schedule, so this skill stays
     * alive until it is interrupted and only claims a tick when something is actually ripe. Between
     * harvests the bot is free - ordinary decisions, other jobs, the model's own plan - which is the
     * point: the harvest itself must never cost a planning turn, but a field must not swallow the
     * bot either.
     */
    private static final class FarmGoal {
        final BlockPos centre;
        final int radius;
        final long startedAt;
        int harvested;
        int replanted;
        int ripeWhenStarted;
        /** Ticks since the last harvest, for the "still nothing ripe" report. */
        int idleTicks;
        /** The crop being put back after a harvest, and what to put there. */
        @Nullable BlockPos pendingReplant;
        String pendingSeed = "";
        boolean idleReported;
        /** Tick before which a refused harvest is not retried. */
        long harvestBlockedUntil;
        /** When the last harvest started, and when Jev was last asked whether the field is ready. */
        long lastHarvestTick;
        long lastRipePingTick;
        long lastRipeCheckTick;
        boolean ripePingPending;

        FarmGoal(BlockPos centre, int radius, int ripeWhenStarted, long startedAt) {
            this.centre = centre.immutable();
            this.radius = radius;
            this.ripeWhenStarted = ripeWhenStarted;
            this.startedAt = startedAt;
            this.lastHarvestTick = startedAt;
        }

        boolean contains(BlockPos pos) {
            return Math.abs(pos.getX() - this.centre.getX()) <= this.radius
                    && Math.abs(pos.getZ() - this.centre.getZ()) <= this.radius
                    && Math.abs(pos.getY() - this.centre.getY()) <= 4;
        }
    }

    /**
     * A field being built, one step per tick.
     *
     * <p>Laid out before the first block is touched, so the whole job is a list that shrinks rather
     * than a plan that is re-derived: the layout is what the operator asked for, and a build that
     * re-decides as it goes is a build that can end up as a different field.
     */
    private static final class FarmBuildJob {
        final FarmSite.Site site;
        /** Cells still to till. The water cell and the torch corners are not in here. */
        final List<BlockPos> toTill = new ArrayList<>();
        /**
         * Cells that were tilled and still need sowing.
         *
         * <p>A separate list because tilling consumes the first one: an earlier version reused a
         * single list for both passes, so by the time the sowing ran there was nothing left in it and
         * the bot reported a finished field with nothing planted in it.
         */
        final List<BlockPos> toPlant = new ArrayList<>();
        final List<BlockPos> torches = new ArrayList<>();
        int tilled;
        int planted;
        int torchesPlaced;
        int ticks;
        boolean walking;
        /** Whether the bot has taken up position in the middle of the field yet. */
        boolean inPosition;
        /** Ticks left in the water hole's break, which is done inline to keep the bot in place. */
        int holeTicks;
        boolean waterBroken;
        boolean waterPlaced;
        boolean waterSkipped;
        boolean torchesSkipped;

        FarmBuildJob(FarmSite.Site site) {
            this.site = site;
        }
    }

    /** A field build is a few hundred block operations; a minute is generous and still bounded. */
    private static final int FARM_BUILD_TIMEOUT_TICKS = 2400;

    private static final String MINE_ROUTE_STATE = "mine_route_v1";
    /** The operator's standing objective, remembered across reloads and restarts. */
    private static final String STANDING_GOAL_STATE = "standing_goal_v1";
    @Nullable
    private MiningGoal miningGoal;
    /** The field this bot keeps, if any. See {@link FarmGoal}. */
    @Nullable
    private FarmGoal farmGoal;
    /** The field being built right now, if any. See {@link FarmBuildJob}. */
    @Nullable
    private FarmBuildJob farmBuildJob;
    /**
     * How the last persistent mining trip ended, kept after the goal is gone.
     *
     * <p>{@code actionReports} is drained into the next prompt, so by the time a test or an operator
     * wants to know why a trip stopped, the line has been handed to the model and removed. A trip
     * that ends silently is exactly the kind of thing this project has learned not to trust, so the
     * outcome is kept here too.
     */
    @Nullable
    private String lastMiningGoalOutcome;

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
    /**
     * Index of the sibling call whose {@code execute()} is running right now, or -1.
     *
     * <p>Only {@code interrupt} tears down its own turn from inside the dispatcher, and it is the one
     * call whose answer the model most needs to see: "cancelled" is the outcome of the request, not
     * the request itself. Knowing which slot belongs to the running call is what lets
     * {@link #abandonPlan(String)} cancel the *other* steps without throwing away that answer.
     */
    private int executingCallIndex = -1;
    /** Set when a step tore its own turn down; the remaining siblings must not run. */
    private boolean turnAbandoned;

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
    /** Reject an in-flight model answer that tries to finish a newer operator goal. */
    private long standingGoalVersion;
    private long decisionGoalVersion;
    private boolean goalCompletedThisTurn;

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
     * <p>Five minutes. Genuine direct questions bypass this; autonomous progress narration does not.
     * Two minutes was measured against production and was not enough: the six unsolicited lines in
     * one two-hour session were spaced 83, 11, 2.5, 10 and 6 minutes apart, so only one of them was
     * inside the old window. The narration rule above catches what those lines actually were; this
     * is the backstop for the ones it does not recognise.
     */
    private static final int UNPROMPTED_CHAT_COOLDOWN = 6000;

    /**
     * Off only for the harness's positive control ({@code MCAGENT_NARRATION_GUARD=off}), which has to
     * see the production narration lines go out to prove the test can detect them at all.
     */
    private static final boolean NARRATION_GUARD_ENABLED =
            !"off".equalsIgnoreCase(System.getenv("MCAGENT_NARRATION_GUARD"));

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
        /** Dimension in which the origin was observed; async recovery must not cross dimensions. */
        final String dimension;
        /** How far from the origin the job may spread. 0 means the single requested block. */
        final int radius;
        /** The block type being removed, or null when only the one block was asked for. */
        @Nullable
        final net.minecraft.world.level.block.Block type;
        /** The originally requested block, distinct from access-clearance blocks. */
        @Nullable
        final net.minecraft.world.level.block.Block originType;
        /**
         * The tick the job started, or -1 for a job that only collects.
         *
         * <p>Collection uses it to tell this job's own leftovers from everything else on the ground:
         * an item older than the job was already lying there when it began. A job built by
         * {@code pickup} has no such thing as "its own" items, so it takes the -1 and skips the test.
         */
        final long startTick;
        /** This job is one server-planned cell of the one established tunnel. */
        final boolean allowHomeSurface;

        final java.util.Set<BlockPos> known = new java.util.HashSet<>();
        final java.util.ArrayDeque<BlockPos> pending = new java.util.ArrayDeque<>();

        /** The block currently being broken, if any. */
        @Nullable
        BlockPos current;
        @Nullable
        net.minecraft.world.level.block.Block currentType;
        Direction face = Direction.UP;
        int ticksRemaining;
        boolean finishSent;
        int verifyTicksRemaining;
        boolean originCompleted;
        /** Blocks actually broken so far. */
        int broken;
        /** Ticks spent failing to reach the next block, so a job cannot hang forever. */
        int approachTicks;
        /** Targets skipped because no reachable standing position could be found. */
        int unreachable;
        /** Blocks removed only to expose/reach the requested resource. */
        final java.util.Set<BlockPos> clearanceTargets = new java.util.HashSet<>();
        int clearanceBroken;
        /** Structured failure counts, rendered into logs and the next model observation. */
        final Map<String, Integer> failures = new LinkedHashMap<>();

        /** True once breaking is finished and the job is collecting what it dropped. */
        boolean collecting;
        /** Where the bot is currently walking to pick something up. */
        @Nullable
        Vec3 collectTarget;
        int collectTicks;
        int collected;

        MineJob(BlockPos origin, String dimension, int radius,
                @Nullable net.minecraft.world.level.block.Block type,
                @Nullable net.minecraft.world.level.block.Block originType, long startTick,
                boolean allowHomeSurface) {
            this.origin = origin;
            this.dimension = dimension;
            this.radius = radius;
            this.type = type;
            this.originType = originType;
            this.startTick = startTick;
            this.allowHomeSurface = allowHomeSurface;
            this.known.add(origin);
            this.pending.add(origin);
        }

        void failed(String code) {
            this.failures.merge(code, 1, Integer::sum);
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

    /** Re-point typed decisions when the runtime-only Jev config is reloaded. */
    public void setJevClient(@Nullable JevClient client) {
        this.jevClient = client;
        // A lease is an answer from one exact adviser/configuration. A reload may change mode,
        // threshold, endpoint or prompt contract, so never carry the old answer across it.
        this.clearRouteLease();
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
        return this.startMine(pos, Math.max(0, Math.min(MAX_MINE_RADIUS, radius)), "", false);
    }

    /**
     * One entry point for the backpack tools.
     *
     * <p>Every path answers honestly when the mod is missing or the bot has no backpack: "you have
     * none, ask a player or craft one" is actionable, while an exception would just look like the bot
     * is broken. The backpack lives in the Curios {@code back} slot and is found by
     * {@link com.melody.mcagent.rt.action.Backpacks}, which is reflective because the dev server does
     * not have this mod on its classpath.
     */
    private String backpackTool(String tool, JsonObject args) {
        if (!com.melody.mcagent.rt.action.Backpacks.available()) {
            return "failed: this server does not have the Sophisticated Backpacks mod, so you have no "
                    + "backpack to use";
        }
        net.minecraft.world.item.ItemStack backpack =
                com.melody.mcagent.rt.action.Backpacks.equipped(this.bot);
        if (backpack == null) {
            return "failed: you have no Sophisticated Backpack. Ask a player for one, or craft one, "
                    + "then wear it in the back slot";
        }
        String item = string(args, "item", "");
        int count = (int) arg(args, "count", 0);
        String result = switch (tool) {
            case "backpack" -> com.melody.mcagent.rt.action.Backpacks.describe(this.bot, backpack);
            case "backpack_wear" -> com.melody.mcagent.rt.action.Backpacks
                    .equip(this.bot, item);
            case "backpack_sort" -> com.melody.mcagent.rt.action.Backpacks.sort(backpack);
            case "backpack_put" -> com.melody.mcagent.rt.action.Backpacks
                    .move(this.bot, backpack, true, item, count);
            case "backpack_take" -> com.melody.mcagent.rt.action.Backpacks
                    .move(this.bot, backpack, false, item, count);
            default -> com.melody.mcagent.rt.action.Backpacks
                    .insertUpgrade(this.bot, backpack, item);
        };
        this.actionReports.addLast(tool + " -> " + firstLine(result));
        return result;
    }

    /** Start a non-blocking mining trip; subsequent steps are selected by the runtime on ticks. */
    private String startMiningGoal(JsonObject args) {
        if (!this.policy.canBreakBlocks()) {
            return "failed: you are not allowed to break blocks";
        }
        List<String> priorities = new ArrayList<>();
        String primary = string(args, "primary", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!primary.isBlank()) {
            priorities.add(primary);
        }
        if (args.has("secondary") && args.get("secondary").isJsonArray()) {
            for (JsonElement raw : args.getAsJsonArray("secondary")) {
                if (raw.isJsonPrimitive()) {
                    String value = raw.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                    if (!value.isBlank() && !priorities.contains(value) && priorities.size() < 8) {
                        priorities.add(value);
                    }
                }
            }
        }
        if (priorities.isEmpty()) {
            priorities.addAll(List.of("iron_ore", "coal_ore", "copper_ore", "gold_ore",
                    "redstone_ore", "diamond_ore"));
            primary = priorities.get(0);
        }
        int amount = (int) Math.max(0, Math.min(4096, arg(args, "amount", 0)));
        if ("branch".equals(string(args, "mode", "resource").trim().toLowerCase(java.util.Locale.ROOT))) {
            return this.startBranchMineGoal(args, priorities, primary, amount);
        }
        int maxChunks = (int) Math.max(1, Math.min(64, arg(args, "max_tunnel_chunks", 12)));
        this.miningGoal = new MiningGoal(priorities, primary, amount,
                this.matchingResourceCount(primary), maxChunks, this.bot.level().getGameTime());
        this.cooldownTicks = 0;
        LOG.info("Bot {} started a persistent mining goal: priorities={}, target={}, "
                        + "max_tunnel_chunks={}", this.bot.getName().getString(), priorities,
                amount > 0 ? amount + " new item(s) matching " + primary : "a normal trip", maxChunks);
        return "started persistent mining goal: priorities=" + priorities
                + (amount > 0 ? ", collect at least " + amount + " new matching item(s)" : "")
                + ", return after inventory pressure, danger, or " + maxChunks
                + " tunnel chunk(s). The runtime now owns navigation, resource selection, mining "
                + "and return; do not submit per-block mining plans.";
    }

    /**
     * Start a branch-mining trip at a fixed level, owned end to end by the runtime.
     *
     * <p>The model's whole contribution is this call: where to dig and what to look for. From here
     * the runner decides the pattern, spots ore through the rock, and only asks Jev - never the
     * planner - when the trip is disturbed.
     */
    private String startBranchMineGoal(JsonObject args, List<String> priorities, String primary,
                                       int amount) {
        if (!this.policy.canBreakBlocks()) {
            return "failed: you are not allowed to break blocks";
        }
        if (!miningSkillEnabled()) {
            return "failed: the persistent mining skill is disabled on this server";
        }
        int minY = this.bot.level().getMinBuildHeight() + 1;
        int targetY = (int) Math.max(minY,
                Math.min(this.bot.blockPosition().getY(), arg(args, "y", this.bot.blockPosition().getY())));
        Direction direction = parseHorizontalDirection(string(args, "direction", ""));
        if (direction == null) {
            direction = this.bot.getDirection();
        }
        int spacing = (int) Math.max(2, Math.min(32,
                arg(args, "branch_spacing", BRANCH_DEFAULT_SPACING)));
        int length = (int) Math.max(2, Math.min(MAX_TUNNEL_LENGTH,
                arg(args, "branch_length", BRANCH_DEFAULT_LENGTH)));
        int maxBranches = (int) Math.max(1, Math.min(64,
                arg(args, "max_branches", BRANCH_DEFAULT_MAX)));
        BranchMineJob job = new BranchMineJob(targetY, direction, spacing, length, maxBranches,
                primary, priorities, amount, this.matchingResourceCount(primary),
                this.bot.level().getGameTime());
        job.resumeAt = this.bot.blockPosition();
        this.branchBridges = 0;
        this.branchRefusedFloor = null;
        this.branchMine = job;
        this.miningGoal = null;
        this.cooldownTicks = 0;
        LOG.info("Bot {} started branch mining: y={} heading={} spacing={} branch_length={} "
                        + "max_branches={} priorities={}",
                this.bot.getName().getString(), targetY, direction.getName(), spacing, length,
                maxBranches, priorities);
        return "started branch mining at y=" + targetY + ", heading " + direction.getName()
                + ": one main corridor with a " + length + "-block branch every " + spacing
                + " blocks, up to " + maxBranches + " branches, diverting to any ore the scan sees "
                + "through the rock. The runtime owns this trip end to end - do not plan or call "
                + "mining tools for it, and expect no further planning turns until it returns home"
                + (amount > 0 ? " or brings back " + amount + " matching item(s)" : "") + ".";
    }

    /**
     * Adopt a field: harvest what is ripe from now on, and replant it.
     *
     * <p>The field is described by a centre and a radius rather than by the blocks themselves, so it
     * survives the crops being broken and replanted - which is what happens to it constantly.
     */
    private String startFarmGoal(JsonObject args) {
        if (!this.policy.canBreakBlocks()) {
            return "failed: you are not allowed to break blocks";
        }
        BlockPos centre = args.has("x") || args.has("z")
                ? blockPos(args)
                : this.bot.blockPosition();
        int radius = (int) Math.max(3, Math.min(24, arg(args, "radius", 8)));
        int ripe = this.ripeCropsIn(new FarmGoal(centre, radius, 0, 0)).size();
        LOG.info("FARMDEBUG bot={} field set at {} radius {}", this.bot.getName().getString(),
                centre.toShortString(), radius);
        this.farmGoal = new FarmGoal(centre, radius, ripe, this.bot.level().getGameTime());
        this.cooldownTicks = 0;
        LOG.info("Bot {} is now keeping the field at {} (radius {}): {} ripe crop(s) right now",
                this.bot.getName().getString(), centre.toShortString(), radius, ripe);
        return "keeping the field centred on " + centre.toShortString() + " (radius " + radius
                + "): " + ripe + " crop(s) are ripe. The runtime now harvests every ripe crop and "
                + "replants it with the seeds you carry, without asking you each time. Use interrupt "
                + "to stop, or call farm again to move the field.";
    }

    /**
     * Lay out and build a field: till it, put water in the middle, light it, plant it.
     *
     * <p>Two decisions are made here and nowhere else. <b>Where</b>: with no coordinates the bot
     * chooses the nearest level, open, unbuilt ground that a hoe can actually work (see
     * {@link FarmSite}) - "do not just dig anywhere" is the point, because a field sited inside
     * somebody's build fails one cell at a time and in public. <b>What the bot is missing</b>: the
     * kit is checked up front and named, so a bot without a hoe is told to get a hoe rather than
     * half-tilling a lawn.
     */
    private String startFarmBuild(JsonObject args) {
        if (!this.policy.canBreakBlocks() || !this.policy.canPlaceBlocks()) {
            return "failed: you are not allowed to change the world";
        }
        // Capped at 2 (a 5x5 field) on purpose: the bot works the field from its middle and must not
        // walk across it once the soil is turned, because walking over farmland tramples it back into
        // dirt. From the middle every cell of a 5x5 is inside reach; a 7x7 is not, and the first
        // version of this job duly wrecked a third of its own field trying to reach the corners.
        int radius = (int) Math.max(1, Math.min(2, arg(args, "radius", 2)));
        FarmSite.Site site;
        if (args.has("x") || args.has("z")) {
            BlockPos centre = blockPos(args);
            String why = FarmSite.reject(this.bot, centre, radius);
            if (why != null) {
                return "failed: " + why;
            }
            site = new FarmSite.Site(centre, radius, centre.getY(), List.of());
        } else {
            site = FarmSite.find(this.bot, radius, 24);
            if (site == null) {
                return "failed: no level, open, unbuilt ground within 24 blocks can hold a "
                        + (radius * 2 + 1) + "x" + (radius * 2 + 1) + " field. Walk somewhere flatter "
                        + "and further from buildings, or name the coordinates yourself.";
            }
        }

        String hoe = Farming.findHoe(this.bot);
        String seed = Farming.firstSeed(this.bot);
        List<String> missing = new ArrayList<>();
        if (hoe == null) {
            missing.add("a hoe");
        }
        if (seed.isEmpty()) {
            missing.add("seeds (wheat seeds, carrots, potatoes, beetroot or nether wart)");
        }
        if (!missing.isEmpty()) {
            return "failed: a field needs " + String.join(" and ", missing) + ", and you have "
                    + (hoe == null ? "no hoe" : "no seeds") + ". Craft or fetch that first.";
        }

        FarmBuildJob job = new FarmBuildJob(site);
        // The middle becomes the water source and the four corners carry the torches, so neither is
        // farmland: a torch on a farm is light, not a crop, and the water has to sit in a hole.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                job.toTill.add(site.cell(dx, dz));
            }
        }
        // Light goes on the ring just outside the field, not on its corners: a torch in the field
        // costs a crop, and the corners are the cells a bot standing in the middle cannot reach
        // anyway.
        int ring = radius + 1;
        job.torches.add(site.cell(-ring, -ring));
        job.torches.add(site.cell(-ring, ring));
        job.torches.add(site.cell(ring, -ring));
        job.torches.add(site.cell(ring, ring));
        this.farmBuildJob = job;
        this.cooldownTicks = 0;
        int torches = Farming.countOf(this.bot, "minecraft:torch");
        boolean bucket = Farming.countOf(this.bot, "minecraft:water_bucket") > 0;
        LOG.info("Bot {} is building {} - {} cell(s) to till and plant, water in the middle ({}), "
                + "{} corner torch(es) ({} carried)", this.bot.getName().getString(),
                site.describe(), job.toTill.size(), bucket ? "bucket ready" : "NO WATER BUCKET",
                job.torches.size(), torches);
        return "building " + site.describe() + ": " + job.toTill.size() + " cell(s) to till and "
                + "plant, a water source in the middle, and " + job.torches.size() + " torch(es) on "
                + "the corners. This runs by itself; use interrupt to stop."
                + (bucket ? "" : " No water bucket: the field will be dry until you bring one.")
                + (torches >= job.torches.size() ? "" : " Only " + torches + " torch(es) carried.");
    }

    /**
     * One tick of building. Returns true while the job owns the bot.
     *
     * <p>Order matters and is the order a player works in: stand in the field, till it, open the
     * water hole, light it, then sow it. Planting before the water is in would just mean a second
     * pass over every cell.
     */
    private boolean tickFarmBuildJob() {
        FarmBuildJob job = this.farmBuildJob;
        if (job == null) {
            return false;
        }
        if (this.bot.isRemoved() || this.bot.isDeadOrDying()) {
            this.farmBuildJob = null;
            return false;
        }
        if (job.ticks % 200 == 0) {
            // Progress line: a field build that stalls must say where it is and what it is waiting
            // for, or the only symptom is a timeout with no explanation.
            LOG.info("Bot {} farm build progress: tick={} bot={} till-left={} plant-left={} tilled={} "
                    + "planted={} torches={} water(broken={} placed={} holeTicks={}) inField={} walking={}",
                    this.bot.getName().getString(), job.ticks, this.bot.blockPosition().toShortString(),
                    job.toTill.size(), job.toPlant.size(), job.tilled, job.planted, job.torchesPlaced,
                    job.waterBroken, job.waterPlaced, job.holeTicks, this.inTheField(job), job.walking);
        }
        if (++job.ticks > FARM_BUILD_TIMEOUT_TICKS) {
            this.finishFarmBuild("gave up after "
                    + (FARM_BUILD_TIMEOUT_TICKS / 20) + "s with " + job.tilled + " tilled and "
                    + job.planted + " planted");
            return false;
        }
        // A dig or a walk already in progress owns the bot.
        if (this.mineJob != null || this.isMoving()) {
            return true;
        }

        // 1. Light first, while the field is still grass: the torches sit on the ring just outside
        // it, so this means walking the perimeter - and walking the perimeter later, over freshly
        // tilled soil, is how a bot tramples its own field back into dirt.
        while (!job.torches.isEmpty()) {
            BlockPos corner = job.torches.get(0);
            if (!Actions.canReach(this.bot, corner)) {
                if (this.walkTo(job, corner)) {
                    return true;
                }
                job.torches.remove(0);
                continue;
            }
            if (!this.bot.level().getBlockState(corner.above()).isAir()) {
                job.torches.remove(0);
                continue;
            }
            if (Farming.countOf(this.bot, "minecraft:torch") <= 0) {
                job.torchesSkipped = true;
                break;
            }
            Actions.Result held = Actions.holdItem(this.bot, "minecraft:torch");
            if (held.success()) {
                Actions.useOnBlock(this.bot, corner, net.minecraft.core.Direction.UP);
                if (this.bot.level().getBlockState(corner.above())
                        .is(net.minecraft.world.level.block.Blocks.TORCH)) {
                    job.torchesPlaced++;
                }
            } else {
                this.reportFarmBuild(job, held.message());
                job.torchesSkipped = true;
                break;
            }
            job.torches.remove(0);
            return true;
        }
        if (job.torchesSkipped) {
            job.torches.clear();
            job.torchesSkipped = false;
            this.reportFarmBuild(job, "out of torches, so the rest of the field is unlit");
            return true;
        }

        // 2. Stand in the middle of the field. Not a reach test: a bot four blocks away can "reach"
        // the middle and will happily dig and pour from there, and then find that most of the field is
        // out of reach and that a bucket aimed at a hole four blocks away puts the water somewhere
        // else. This is the position every later step is done from.
        if (!job.inPosition || !this.inTheField(job)) {
            if (this.inTheField(job)) {
                job.inPosition = true;
                return true;
            }
            if (!job.walking) {
                job.walking = true;
                if (!this.walkTo(job, job.site.centre())) {
                    // Cannot get there: carry on from where it stands and let the per-cell reach
                    // checks report what that costs.
                    job.inPosition = true;
                }
            }
            return true;
        }

        // 3. The water source, before a single cell is tilled. The bot digs the block it is standing
        // on and drops into the hole, which is harmless while the ground is still grass - and it then
        // stays in that hole for the rest of the build, so it never has to walk across its own field.
        if (!job.waterBroken) {
            BlockPos centre = job.site.centre();
            if (this.bot.level().getBlockState(centre).isAir()) {
                job.waterBroken = true;
                job.waterPlaced = true;
                return true;
            }
            // Broken inline rather than through startMine: a mine job walks the bot to its drop when
            // the break finishes, and a bot that has wandered three blocks away cannot pour a bucket
            // into the hole it is no longer standing in. That is exactly how this failed first.
            Actions.Result started = Actions.startBreak(this.bot, centre,
                    net.minecraft.core.Direction.UP);
            if (!started.success()) {
                this.reportFarmBuild(job, "could not open the water hole - " + started.message());
                job.waterBroken = true;
                job.waterPlaced = true;
                job.waterSkipped = true;
                return true;
            }
            job.waterBroken = true;
            job.holeTicks = Actions.ticksToBreak(this.bot, centre);
            return true;
        }
        if (job.holeTicks > 0) {
            job.holeTicks--;
            if (job.holeTicks == 0) {
                Actions.finishBreak(this.bot, job.site.centre(), net.minecraft.core.Direction.UP);
                this.bot.resetAttackStrengthTicker();
            }
            return true;
        }
        if (!job.waterPlaced) {
            var hole = this.bot.level().getBlockState(job.site.centre());
            if (!hole.isAir()) {
                this.reportFarmBuild(job, "the water hole at " + job.site.centre().toShortString()
                        + " is still " + hole.getBlock().getName().getString()
                        + ", so there is nowhere to put the water");
                job.waterPlaced = true;
                job.waterSkipped = true;
                return true;
            }
            if (Farming.countOf(this.bot, "minecraft:water_bucket") <= 0) {
                this.reportFarmBuild(job, "no water bucket, so the field is dry - farmland needs "
                        + "water within four blocks or the crops will not grow");
                job.waterPlaced = true;
                job.waterSkipped = true;
                return true;
            }
            Actions.Result held = Actions.holdItem(this.bot, "water_bucket");
            if (!held.success()) {
                this.reportFarmBuild(job, held.message());
                job.waterPlaced = true;
                job.waterSkipped = true;
                return true;
            }
            Actions.Result poured = Farming.placeFluid(this.bot, job.site.centre(), "water_bucket");
            job.waterPlaced = true;
            job.waterSkipped = !poured.success();
            this.reportFarmBuild(job, poured.success()
                    ? poured.message()
                    : "no water in the field: " + poured.message());
            return true;
        }

        // 4. Till everything that will be farmland, from the water hole the bot is standing in.
        while (!job.toTill.isEmpty()) {
            BlockPos soil = job.toTill.get(0);
            job.toTill.remove(0);
            if (!Actions.canReach(this.bot, soil)) {
                this.reportFarmBuild(job, soil.toShortString()
                        + " is out of reach and was left untilled");
                continue;
            }
            Actions.Result result = Farming.till(this.bot, soil, "");
            if (result.success()) {
                job.tilled++;
                job.toPlant.add(soil);
            } else {
                this.reportFarmBuild(job, result.message());
            }
            return true;
        }

        // 5. Sow it, in the same pass order and without moving.
        while (!job.toPlant.isEmpty()) {
            BlockPos soil = job.toPlant.get(0);
            job.toPlant.remove(0);
            if (!Actions.canReach(this.bot, soil)) {
                this.reportFarmBuild(job, soil.toShortString()
                        + " is out of reach and was left bare");
                continue;
            }
            BlockPos crop = soil.above();
            if (!this.bot.level().getBlockState(crop).isAir()) {
                continue;
            }
            String seed = Farming.firstSeed(this.bot);
            if (seed.isEmpty()) {
                this.reportFarmBuild(job, "out of seeds after " + job.planted
                        + " planted; the rest of the field is bare");
                break;
            }
            Actions.Result planted = Farming.plant(this.bot, crop, seed);
            if (planted.success()) {
                job.planted++;
            } else {
                this.reportFarmBuild(job, planted.message());
            }
            return true;
        }

        this.finishFarmBuild("built " + job.tilled + " farmland cell(s), planted " + job.planted
                + ", " + job.torchesPlaced + " torch(es)"
                + (job.waterSkipped ? ", no water" : ", watered"));
        // Adopt it: building a field and then walking away from it would be a strange thing to do.
        this.farmGoal = new FarmGoal(job.site.centre(), job.site.radius() + 1, 0,
                this.bot.level().getGameTime());
        return false;
    }

    /** Is the bot standing in the middle of the field, or in the water hole at its centre? */
    private boolean inTheField(FarmBuildJob job) {
        BlockPos here = this.bot.blockPosition();
        return here.distSqr(job.site.centre()) <= 2.25D
                || here.distSqr(job.site.centre().above()) <= 2.25D;
    }

    /**
     * Walk to a cell this step cannot reach from where the bot stands.
     *
     * <p>Only ever used before the soil is turned: a bot that walks over its own finished field is a
     * bot that tramples it, and farmland that has been jumped on is dirt again.
     *
     * @return true when a walk was started, so the caller should wait
     */
    private boolean walkTo(FarmBuildJob job, BlockPos target) {
        var handle = com.melody.mcagent.rt.Agent.botManager() == null ? null
                : com.melody.mcagent.rt.Agent.botManager().get(this.bot.getName().getString());
        if (handle == null) {
            this.reportFarmBuild(job, "cannot walk to " + target.toShortString()
                    + ": this bot is not registered");
            return false;
        }
        var plan = handle.movement().setPathTarget(target.above(), GOTO_PLAN_RANGE);
        if (plan != MovementDriver.Plan.FOUND) {
            this.reportFarmBuild(job, target.toShortString() + " cannot be walked to (" + plan + ")");
            return false;
        }
        return true;
    }

    /**
     * Say what went wrong, in the log as well as to the model.
     *
     * <p>The model only sees these lines in its next prompt, which is no use at all to whoever is
     * reading the server log to find out why a field came out wrong - which is exactly how the
     * first version of this job was debugged.
     */
    private void reportFarmBuild(FarmBuildJob job, String message) {
        this.actionReports.addLast("farm build: " + message);
        LOG.info("Bot {} farm build: {}", this.bot.getName().getString(), message);
    }

    /** Close a field build and make the outcome visible, in the log and to the model. */
    private void finishFarmBuild(String outcome) {
        FarmBuildJob job = this.farmBuildJob;
        this.farmBuildJob = null;
        this.stopMoving();
        this.actionReports.addLast("farm build: " + outcome);
        LOG.info("Bot {} farm build finished: {}", this.bot.getName().getString(), outcome);
        if (job != null) {
            LOG.info("Bot {} farm build detail: site={} tilled={} planted={} torches={}",
                    this.bot.getName().getString(), job.site.describe(), job.tilled, job.planted,
                    job.torchesPlaced);
        }
    }

    /**
     * Every ripe crop inside the field that the bot can currently see.
     *
     * <p>One perception scan serves the whole question, sorted by distance so the bot takes the
     * nearest first and does not walk the field in an arbitrary order.
     */
    private List<Perception.SeenBlock> ripeCropsIn(FarmGoal field) {
        List<Perception.SeenBlock> out = new ArrayList<>();
        for (Perception.SeenBlock seen : Perception.visibleBlocks(this.bot, field.radius + 4)) {
            if (Crops.isMature(seen.state()) && field.contains(seen.pos())) {
                out.add(seen);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(Perception.SeenBlock::distance));
        return out;
    }

    /** Every crop the bot can see inside the field, ripe or not. */
    private List<Perception.SeenBlock> cropsIn(FarmGoal field) {
        List<Perception.SeenBlock> out = new ArrayList<>();
        for (Perception.SeenBlock seen : Perception.visibleBlocks(this.bot, field.radius + 4)) {
            if (Crops.isCrop(seen.state()) && field.contains(seen.pos())) {
                out.add(seen);
            }
        }
        return out;
    }

    /** A small piece of work the bot owes itself: done in an idle moment, at no model cost. */
    private record TodoItem(String kind, BlockPos at, int radius, String description) {
    }

    private final java.util.Deque<TodoItem> todo = new java.util.ArrayDeque<>();

    /** Remember something for an idle moment, once per place and kind. */
    private void addTodo(TodoItem item) {
        for (TodoItem existing : this.todo) {
            if (existing.kind().equals(item.kind()) && existing.at().equals(item.at())) {
                return;
            }
        }
        this.todo.addLast(item);
        this.actionReports.addLast("on the todo list for an idle moment: " + item.description());
        LOG.info("Bot {} todo + {}", this.bot.getName().getString(), item.description());
    }

    /**
     * Do one thing the bot owes itself, when the moment is free.
     *
     * <p>No model is asked about it: the item was already decided, and asking again is how a "later"
     * turns back into a planning call. The skill it starts carries the work out with no turns of its
     * own - adopting the field is what makes a deferred harvest free.
     *
     * @return true when a todo item was taken up this tick
     */
    private boolean tickTodo() {
        if (this.todo.isEmpty() || this.farmGoal != null || this.branchMine != null
                || this.miningGoal != null || this.farmBuildJob != null
                || this.isLongActionRunning() || !this.queue.isEmpty() || this.thinking.get()
                || this.bot.isRemoved() || this.bot.isDeadOrDying() || this.paused
                || this.bot.getHealth() <= 6.0F) {
            return false;
        }
        TodoItem item = this.todo.pollFirst();
        if (!"farm_harvest".equals(item.kind())) {
            return false;
        }
        long now = this.bot.level().getGameTime();
        int ripe = this.ripeCropsIn(new FarmGoal(item.at(), item.radius(), 0, now)).size();
        this.farmGoal = new FarmGoal(item.at(), item.radius(), ripe, now);
        this.cooldownTicks = 0;
        LOG.info("Bot {} picked up a todo item: {} ({} ripe now)", this.bot.getName().getString(),
                item.description(), ripe);
        this.actionReports.addLast("idle moment: starting the deferred " + item.description()
                + " (" + ripe + " ripe crop(s)) with no planning turn");
        return true;
    }

    /**
     * Periodically consider whether the field is ready, and let Jev decide when to go and harvest.
     *
     * <p>Timed from the last harvest rather than from a clock, because a field is "roughly ready"
     * either when the crops the bot can see are mostly ripe, or when enough time has passed that they
     * should be - which is the only signal there is when the bot is deep in a mine and cannot see the
     * field at all. The answer is a scheduling decision: go now, keep it on the todo list, or wait.
     * There is deliberately no ESCALATE_LLM among the candidates: "later" is always a legal answer to
     * a scheduling question, so there is nothing here a planning turn could decide better.
     */
    private void tickFarmRipeness() {
        FarmGoal field = this.farmGoal;
        if (field == null || field.ripePingPending) {
            return;
        }
        long now = this.bot.level().getGameTime();
        if (now - field.lastRipeCheckTick < FARM_RIPE_CHECK_INTERVAL_TICKS) {
            return;
        }
        field.lastRipeCheckTick = now;
        if (now - field.lastRipePingTick < FARM_RIPE_PING_COOLDOWN_TICKS) {
            LOG.info("FARMPING bot={} quiet: {}s of ping cooldown left", this.bot.getName().getString(),
                    (FARM_RIPE_PING_COOLDOWN_TICKS - (now - field.lastRipePingTick)) / 20L);
            return;
        }
        List<Perception.SeenBlock> ripe = this.ripeCropsIn(field);
        int crops = this.cropsIn(field).size();
        boolean canSee = !ripe.isEmpty() || crops > 0;
        // The denominator is every crop block in the field, ripe ones included - adding ripe to crops
        // counts them twice, which made a field that was entirely ripe read as 50% ready and never
        // ask. A field with nothing in sight falls back to the clock instead.
        boolean roughlyReady = canSee
                ? ripe.size() >= Math.max(1, (int) Math.ceil(crops * FARM_RIPE_FRACTION))
                : now - field.lastHarvestTick >= FARM_RIPE_ESTIMATE_TICKS;
        if (!roughlyReady) {
            LOG.info("FARMPING bot={} not ready: ripe={} crops={} sinceHarvest={}s",
                    this.bot.getName().getString(), ripe.size(), crops,
                    (now - field.lastHarvestTick) / 20L);
            return;
        }
        String state = "Minecraft farm check. bot=" + this.bot.getName().getString()
                + "; field=" + field.centre.toShortString() + " radius=" + field.radius
                + "; ripe_now=" + ripe.size() + "; growing_now=" + crops
                + "; seconds_since_last_harvest=" + ((now - field.lastHarvestTick) / 20L)
                + "; harvested_so_far=" + field.harvested
                + "; current_action=" + this.describeCurrentAction()
                + "; busy_with=" + (this.branchMine != null ? "branch_mining"
                        : this.miningGoal != null ? "mining"
                        : this.isLongActionRunning() ? "long_action"
                        : this.queue.isEmpty() ? "idle" : "queued_work");
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("HARVEST_NOW", "Go and harvest the field now; it is ready");
        candidates.put("TODO_LATER", "Keep it for an idle moment; the bot has something better in hand");
        candidates.put("WAIT", "Not ready yet; ask again later");

        JevClient adviser = this.jevClient;
        JevClient.GateMode mode = adviser == null ? JevClient.GateMode.OFF
                : adviser.settings().interrupts();
        if (adviser == null || !adviser.settings().isUsable() || mode == JevClient.GateMode.OFF) {
            LOG.info("FARMPING bot={} adviser unusable: adviser={} gate={}",
                    this.bot.getName().getString(), adviser == null ? "none" : "present", mode);
            field.lastRipePingTick = now;
            return;
        }
        field.ripePingPending = true;
        field.lastRipePingTick = now;
        this.statsJevRequests++;
        CompletableFuture
                .supplyAsync(() -> adviser.choose(state, "farm_ripe",
                        com.melody.mcagent.rt.llm.JevPrompts.FARM_RIPE, candidates), executor())
                .thenAccept(choice -> this.bot.server.execute(
                        () -> this.applyFarmRipeAnswer(field, adviser, mode, choice)));
    }

    /** Apply a ripeness answer: harvest by doing nothing (the skill harvests when ripe), defer, wait. */
    private void applyFarmRipeAnswer(FarmGoal field, JevClient adviser, JevClient.GateMode mode,
                                     JevClient.Choice choice) {
        if (this.farmGoal != field) {
            return;
        }
        field.ripePingPending = false;
        boolean failed = choice.failed();
        boolean confident = !failed && choice.confidence() >= MIN_ACTIVE_JEV_CONFIDENCE;
        LOG.info("JEV {} bot={} event=FARM_RIPE choice={} confidence={} applied={} error={} "
                        + "field={} ripe_when_asked={}",
                mode == JevClient.GateMode.ACTIVE ? "ACTIVE" : "SHADOW",
                this.bot.getName().getString(), choice.choice(),
                String.format(java.util.Locale.ROOT, "%.3f", choice.confidence()),
                mode == JevClient.GateMode.ACTIVE && confident, failed ? firstLine(choice.error()) : "-",
                field.centre.toShortString(), field.ripeWhenStarted);
        if (failed || mode != JevClient.GateMode.ACTIVE) {
            return;
        }
        if (!confident) {
            return;
        }
        switch (choice.choice()) {
            case "HARVEST_NOW" -> this.actionReports.addLast("Jev said the field is ready ("
                    + String.format(java.util.Locale.ROOT, "%.2f", choice.confidence())
                    + "); the next idle tick harvests it");
            case "TODO_LATER" -> this.addTodo(new TodoItem("farm_harvest", field.centre, field.radius,
                    "harvest the field at " + field.centre.toShortString() + " (radius "
                            + field.radius + ") - Jev deferred it"));
            case "WAIT" -> this.actionReports.addLast("Jev said the field is not ready yet; "
                    + "checking again later");
            default -> LOG.warn("JEV ACTIVE bot={} answered '{}' for farm ripeness, which was not "
                    + "offered; ignoring it", this.bot.getName().getString(), choice.choice());
        }
    }

    /**
     * One tick of farm work: replant what was just taken, or start on the nearest ripe crop.
     *
     * @return true when the skill did something and the tick belongs to it
     */
    private boolean advanceFarmGoal() {
        FarmGoal field = this.farmGoal;
        if (field == null) {
            return false;
        }
        if (this.bot.isRemoved() || this.bot.isDeadOrDying()) {
            LOG.info("FARMDEBUG bot={} cleared by dead_or_removed", this.bot.getName().getString());
            this.farmGoal = null;
            return false;
        }
        // Same safety valve as the mining skill: a bot that is nearly dead has no business farming.
        if (this.bot.getHealth() <= 6.0F) {
            this.actionReports.addLast("farm: stopping, health is "
                    + String.format(java.util.Locale.ROOT, "%.1f", this.bot.getHealth()));
            LOG.info("FARMDEBUG bot={} cleared by low_health={}", this.bot.getName().getString(),
                    String.format(java.util.Locale.ROOT, "%.1f", this.bot.getHealth()));
            this.farmGoal = null;
            return false;
        }
        if (this.mineJob != null || this.isMoving()) {
            return true;
        }
        if (this.bot.level().getGameTime() < field.harvestBlockedUntil) {
            return false;
        }

        // Put back what was just harvested. This is a separate tick from the harvest because the
        // block only becomes plantable once the crop is actually gone.
        if (field.pendingReplant != null) {
            BlockPos target = field.pendingReplant;
            String seed = field.pendingSeed;
            field.pendingReplant = null;
            field.pendingSeed = "";
            Actions.Result planted = Farming.plant(this.bot, target, seed);
            if (planted.success()) {
                field.replanted++;
                this.actionReports.addLast("farm: " + planted.message());
            } else {
                // Out of seed, or the block is no longer plantable. Worth saying once per field:
                // a field that is harvested and never replanted is a field that is being eaten.
                this.actionReports.addLast("farm: could not replant " + target.toShortString()
                        + " - " + planted.message());
            }
            return true;
        }

        List<Perception.SeenBlock> ripe = this.ripeCropsIn(field);
        if (ripe.isEmpty()) {
            field.idleTicks++;
            // Say so once every 30 s rather than every tick: the point is that an operator watching
            // the log can tell "the farm is alive and waiting" from "the farm is broken".
            if (field.idleTicks > 600 && !field.idleReported) {
                field.idleReported = true;
                this.actionReports.addLast("farm: nothing ripe in the field right now ("
                        + field.harvested + " harvested, " + field.replanted + " replanted so far)");
            }
            return false;
        }

        Perception.SeenBlock target = ripe.get(0);
        // Read the seed before the crop is broken: after that there is nothing to ask.
        net.minecraft.world.item.ItemStack seed =
                Crops.replantItem(this.bot.serverLevel(), target.pos());
        field.pendingReplant = target.pos();
        field.pendingSeed = Farming.idOf(seed);
        field.idleTicks = 0;
        field.idleReported = false;
        String started = this.startMine(target.pos(), 0, "", false);
        if (started.startsWith("failed")) {
            field.pendingReplant = null;
            field.pendingSeed = "";
            // A refused crop is not a race: it is still there on the next tick, and retrying it every
            // tick produced twenty-one identical refusals a second in production (the field sits
            // twenty blocks from the bed the home rule protects) while the model was woken behind it.
            // Report it once, wait, and let the refusal streak stop the turn-buying if it persists.
            field.harvestBlockedUntil =
                    this.bot.level().getGameTime() + FARM_HARVEST_BACKOFF_TICKS;
            this.actionReports.addLast("farm: " + started);
            this.noteNoProgress("farm harvest refused");
            return false;
        }
        field.harvested++;
        // The ripeness timer starts here, not on a clock: "the field should be ready again" is
        // measured from the harvest that emptied it.
        field.lastHarvestTick = this.bot.level().getGameTime();
        LOG.info("Bot {} farm: harvesting {} at {} ({} harvested, {} replanted so far)",
                this.bot.getName().getString(),
                target.state().getBlock().getName().getString(), target.pos().toShortString(),
                field.harvested, field.replanted);
        return true;
    }

    /** Inventory count used for an amount goal; tools/armour are deliberately not ore progress. */
    private int matchingResourceCount(String resource) {
        String token = resource == null ? "" : resource.toLowerCase(java.util.Locale.ROOT);
        int colon = token.indexOf(':');
        if (colon >= 0) {
            token = token.substring(colon + 1);
        }
        token = token.replace("deepslate_", "").replace("nether_", "")
                .replace("_ore", "").replace("raw_", "");
        if (token.isBlank()) {
            return 0;
        }
        int count = 0;
        for (var stack : this.bot.getInventory().items) {
            if (stack.isEmpty() || stack.isDamageableItem()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().toLowerCase(java.util.Locale.ROOT);
            if (id.contains(token)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int emptyInventorySlots() {
        int empty = 0;
        for (var stack : this.bot.getInventory().items) {
            if (stack.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /**
     * Advance one semantic mining step. Returns true while the skill still owns ordinary idle
     * decisions; player chat and explicit operator decisions remain able to interrupt it.
     */
    private boolean advanceMiningGoal() {
        MiningGoal goal = this.miningGoal;
        if (goal == null) {
            return false;
        }
        int gained = this.matchingResourceCount(goal.primary) - goal.startingAmount;
        boolean unsafe = this.bot.getHealth() <= 6.0F || this.bot.getFoodData().getFoodLevel() <= 4;
        if (!goal.returning && ((goal.requestedAmount > 0 && gained >= goal.requestedAmount)
                || this.emptyInventorySlots() <= 2 || unsafe
                || goal.tunnelChunks >= goal.maxTunnelChunks)) {
            goal.returning = true;
            String returning = "mining skill is returning: gained=" + gained
                    + ", empty_slots=" + this.emptyInventorySlots() + ", health="
                    + String.format(java.util.Locale.ROOT, "%.1f", this.bot.getHealth())
                    + ", food=" + this.bot.getFoodData().getFoodLevel()
                    + ", tunnel_chunks=" + goal.tunnelChunks;
            LOG.info("Bot {} {}", this.bot.getName().getString(), returning);
            this.actionReports.addLast(returning);
        }

        if (goal.returning) {
            BlockPos home = this.homePosition();
            if (home != null && this.bot.blockPosition().distSqr(home) <= 256.0D) {
                this.completeMiningGoal(goal, gained, "arrived back near home");
                return false;
            }
            if (!goal.returnScheduled) {
                String backtrack = this.scheduleJevBacktrack();
                if (!backtrack.startsWith("failed:")) {
                    goal.returnScheduled = true;
                    this.actionReports.addLast("mining skill return -> " + backtrack);
                    return true;
                }
            }
            String returned = this.returnToSpawnNow();
            this.completeMiningGoal(goal, gained, returned);
            return false;
        }

        // Prefer concrete perceived resources. The skill chooses only semantic targets; startMine
        // still owns access planning, protection, tool timing, drops and failure reporting.
        // One scan serves every priority: this runs on the server thread, and a fresh raycast walk
        // per resource would repeat the same work up to eight times in a single tick.
        List<Perception.SeenBlock> visible = Perception.visibleBlocks(this.bot, this.observeRadius);
        for (String resource : goal.priorities) {
            List<Perception.SeenBlock> matches = this.matchesIn(visible, resource);
            for (int i = 0; i < Math.min(3, matches.size()); i++) {
                Perception.SeenBlock target = matches.get(i);
                String result = this.startMine(target.pos(), 6, "", false);
                if (!isFailure(result)) {
                    goal.resourcesStarted++;
                    this.actionReports.addLast("mining skill selected " + resource + " at "
                            + target.pos().toShortString() + " -> " + firstLine(result));
                    return true;
                }
            }
        }

        MineRoute route = this.loadMineRoute();
        Direction direction = route != null && route.dimension().equals(this.currentDimension())
                ? route.direction() : this.bot.getDirection();
        String mode = route != null && route.dimension().equals(this.currentDimension())
                && route.face().getY() <= -48 ? "level" : "down";
        String tunnel = this.digTunnel(direction.getName(), mode, 8, "", false);
        if (isFailure(tunnel)) {
            String outcome = "persistent mining goal handed back to the model: " + tunnel;
            this.lastMiningGoalOutcome = outcome;
            this.actionReports.addLast(outcome);
            LOG.info("Bot {} {}", this.bot.getName().getString(), outcome);
            this.miningGoal = null;
            this.cooldownTicks = 0;
            return false;
        }
        goal.tunnelChunks++;
        this.actionReports.addLast("mining skill found no priority ore; " + firstLine(tunnel));
        return true;
    }

    /**
     * One tick of the branch-mining skill.
     *
     * <p>Everything the trip needs is decided here: the pattern, ore diversion, the return trip and
     * the two ways it can be interrupted. The planning model is not consulted - a mining trip that
     * costs a 13k-token turn every few seconds is the thing this skill exists to replace - and the
     * only model of any kind it talks to is Jev, in one bounded typed question, when the trip is
     * actually disturbed.
     *
     * @return true while the skill still owns the bot's decisions
     */
    private boolean advanceBranchMine() {
        BranchMineJob job = this.branchMine;
        if (job == null) {
            return false;
        }
        if (this.bot.isRemoved() || this.bot.isDeadOrDying()) {
            this.branchMine = null;
            return false;
        }
        int gained = this.matchingResourceCount(job.primary) - job.startingAmount;

        // A full pack and a worn tool are deliberately NOT in this list: those are exactly the cases
        // Jev is asked about, and turning for home first would make the question pointless.
        if (!job.returning && ((job.requestedAmount > 0 && gained >= job.requestedAmount)
                || job.branchesDug >= job.maxBranches
                || this.bot.getHealth() <= 6.0F || this.bot.getFoodData().getFoodLevel() <= 4)) {
            job.returning = true;
            String why = "branch mining is returning: gained=" + gained + ", branches="
                    + job.branchesDug + "/" + job.maxBranches + ", empty_slots="
                    + this.emptyInventorySlots() + ", health="
                    + String.format(java.util.Locale.ROOT, "%.1f", this.bot.getHealth());
            LOG.info("Bot {} {}", this.bot.getName().getString(), why);
            this.actionReports.addLast(why);
        }
        if (job.returning) {
            return this.returnFromBranchMine(job, gained);
        }

        // The interruption check runs from tick() on every tick of the trip, including while a run
        // is still being dug - a pack does not wait politely for the end of a corridor leg, and a
        // tool does not break at one either. Only the ore scan needs the pattern to be between runs.
        if (job.interruptPending || this.mineJob != null || this.isMoving()
                || !this.queue.isEmpty()) {
            return true;
        }

        BlockPos here = this.bot.blockPosition();
        // Back to the anchor: after an ore diversion, after a finished branch, or after a detour the
        // terrain forced. The pattern is a plan about places, so it needs a place to stand.
        if (job.resumeAt != null && here.distSqr(job.resumeAt) > 6.0D) {
            JsonObject walk = new JsonObject();
            walk.addProperty("x", job.resumeAt.getX());
            walk.addProperty("y", job.resumeAt.getY());
            walk.addProperty("z", job.resumeAt.getZ());
            this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                    "branch_return", "goto", walk), -1, true));
            this.actionReports.addLast("branch mining walking back to "
                    + job.resumeAt.toShortString() + " to carry on the pattern");
            return true;
        }

        // Ore first: finding what the tunnels would miss is the whole point of the shape.
        long now = this.bot.level().getGameTime();
        if (now - job.lastOreScanTick >= BRANCH_ORE_SCAN_TICKS) {
            job.lastOreScanTick = now;
            List<Perception.SeenBlock> visible = Perception.visibleBlocks(this.bot, BRANCH_ORE_RADIUS);
            if (!job.scanReported) {
                job.scanReported = true;
                LOG.info("Bot {} branch mining scan: {} block(s) visible within {} of {}",
                        this.bot.getName().getString(), visible.size(), BRANCH_ORE_RADIUS,
                        here.toShortString());
            }
            for (String resource : job.priorities) {
                List<Perception.SeenBlock> matches = this.matchesIn(visible, resource);
                for (int i = 0; i < Math.min(2, matches.size()); i++) {
                    Perception.SeenBlock target = matches.get(i);
                    String result = this.startMine(target.pos(), BRANCH_ORE_VEIN_RADIUS, "", false);
                    if (!isFailure(result)) {
                        job.resourcesStarted++;
                        this.actionReports.addLast("branch mining spotted " + resource + " at "
                                + target.pos().toShortString() + " through the rock -> "
                                + firstLine(result));
                        return true;
                    }
                }
            }
        }

        // The pattern: descend to the level first, then a corridor with branches off it.
        List<String> problem = new ArrayList<>();
        boolean descending = here.getY() > job.targetY;
        boolean branching = !descending && job.nextBranchIn <= 0
                && job.branchesDug < job.maxBranches;
        Direction runDirection;
        int planned;
        if (descending) {
            runDirection = job.heading;
            planned = this.queueBranchRun(runDirection, MAX_BRANCH_RUN_BLOCKS, true, problem);
        } else if (branching) {
            if (job.branchJunction == null) {
                job.branchJunction = here.immutable();
                job.branchBlocksDug = 0;
            }
            runDirection = job.branchSide > 0 ? job.heading.getClockWise()
                    : job.heading.getCounterClockWise();
            int remaining = Math.max(1, job.branchLength - job.branchBlocksDug);
            planned = this.queueBranchRun(runDirection,
                    Math.min(MAX_BRANCH_RUN_BLOCKS, remaining), false, problem);
        } else {
            runDirection = job.heading;
            planned = this.queueBranchRun(runDirection,
                    Math.min(MAX_BRANCH_RUN_BLOCKS, Math.max(1, job.nextBranchIn)), false, problem);
        }

        if (planned < 0 && this.bridgeOpenSpace(job)) {
            return true;
        }
        if (planned < 0) {
            // A ravine, lava or somebody's cellar can close one heading without closing the level.
            // Turn the corridor once before calling the trip over, and never loop on a dead heading.
            String refusal = problem.isEmpty() ? "unknown terrain" : String.join("; ", problem);
            if (job.headingTurns < 2) {
                job.headingTurns++;
                job.heading = job.heading.getClockWise();
                job.nextBranchIn = Math.min(job.nextBranchIn, job.branchSpacing);
                String turned = "branch mining cannot continue " + runDirection.getName() + " ("
                        + refusal + "); turning the corridor to " + job.heading.getName();
                LOG.info("Bot {} {}", this.bot.getName().getString(), turned);
                this.actionReports.addLast(turned);
                return true;
            }
            this.completeBranchMine(job, gained, "the level is blocked: " + refusal);
            return false;
        }

        if (descending) {
            job.resumeAt = this.branchRunEnd;
        } else if (branching) {
            job.branchBlocksDug += planned;
            if (job.branchBlocksDug >= job.branchLength) {
                job.branchesDug++;
                job.nextBranchIn = job.branchSpacing;
                job.resumeAt = job.branchJunction;
                job.branchJunction = null;
                job.branchSide = -job.branchSide;
                job.headingTurns = 0;
                LOG.info("Bot {} branch mining finished branch {}/{} ({} main block(s), {} ore job(s))",
                        this.bot.getName().getString(), job.branchesDug, job.maxBranches,
                        job.mainBlocksDug, job.resourcesStarted);
            } else {
                job.resumeAt = this.branchRunEnd;
            }
        } else {
            job.mainBlocksDug += planned;
            job.nextBranchIn -= planned;
            job.resumeAt = this.branchRunEnd;
            job.headingTurns = 0;
        }
        return true;
    }

    /**
     * Answer open space the way a player does: pave it.
     *
     * <p>A hole in the floor of the corridor is not a reason to abandon a level - it is one block to
     * place, and the pattern continues on the far side. The attachment is chosen as a player would:
     * the solid block under the hole if there is one, otherwise the floor of the cell the bot stands
     * on, clicked along the run. Bounded per trip and by what the bot carries: an endless cavern is a
     * reason to turn the corridor, and an empty pack is a reason to go home.
     *
     * @return true when a place step was queued and the run should be retried next tick
     */
    private boolean bridgeOpenSpace(BranchMineJob job) {
        BlockPos hole = this.branchRefusedFloor;
        if (hole == null || this.branchBridges >= BRANCH_MAX_BRIDGES) {
            return false;
        }
        String block = this.bridgeBlock();
        if (block == null) {
            this.actionReports.addLast("branch mining met open space at " + hole.toShortString()
                    + " and is carrying nothing to pave it with");
            return false;
        }
        net.minecraft.server.level.ServerLevel world = this.bot.serverLevel();
        JsonObject place = new JsonObject();
        BlockPos below = hole.below();
        if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
            place.addProperty("x", below.getX());
            place.addProperty("y", below.getY());
            place.addProperty("z", below.getZ());
            place.addProperty("face", "up");
        } else {
            BlockPos behind = hole.relative(job.heading.getOpposite());
            boolean solidBehind = !world.getBlockState(behind)
                    .getCollisionShape(world, behind).isEmpty();
            BlockPos clicked = solidBehind ? behind : hole.relative(job.heading);
            place.addProperty("x", clicked.getX());
            place.addProperty("y", clicked.getY());
            place.addProperty("z", clicked.getZ());
            place.addProperty("face", solidBehind ? job.heading.getName()
                    : job.heading.getOpposite().getName());
        }
        place.addProperty("item", block);
        this.branchBridges++;
        this.branchRefusedFloor = null;
        this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                "branch_bridge_" + this.branchBridges, "place", place), -1, true));
        String message = "branch mining is paving the open space at " + hole.toShortString() + " with "
                + block + " (" + this.branchBridges + "/" + BRANCH_MAX_BRIDGES + ")";
        LOG.info("Bot {} {}", this.bot.getName().getString(), message);
        this.actionReports.addLast(message);
        return true;
    }

    /** The first carried building block worth making a floor out of, or null. */
    @Nullable
    private String bridgeBlock() {
        var inventory = this.bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.endsWith("cobblestone") || id.endsWith("_stone") || id.endsWith("deepslate")
                    || id.endsWith("dirt") || id.endsWith("andesite") || id.endsWith("planks")) {
                return id;
            }
        }
        return null;
    }

    /** Walk home at the end of a branch-mining trip, the same way a resource trip does. */
    private boolean returnFromBranchMine(BranchMineJob job, int gained) {
        BlockPos home = this.homePosition();
        if (home != null && this.bot.blockPosition().distSqr(home) <= 256.0D) {
            this.completeBranchMine(job, gained, "arrived back near home");
            return false;
        }
        if (!job.returnScheduled) {
            String backtrack = this.scheduleJevBacktrack();
            if (!backtrack.startsWith("failed:")) {
                job.returnScheduled = true;
                this.actionReports.addLast("branch mining return -> " + backtrack);
                return true;
            }
        }
        String returned = this.returnToSpawnNow();
        this.completeBranchMine(job, gained, returned);
        return false;
    }

    private void completeBranchMine(BranchMineJob job, int gained, String reason) {
        if (this.branchMine != job) {
            return;
        }
        long seconds = Math.max(0L, (this.bot.level().getGameTime() - job.startedAt) / 20L);
        this.branchMine = null;
        String outcome = "branch mining finished after " + seconds + "s at y=" + job.targetY + ": "
                + job.branchesDug + "/" + job.maxBranches + " branch(es), " + job.mainBlocksDug
                + " main block(s), " + job.resourcesStarted + " ore job(s), gained " + gained
                + " item(s) matching " + job.primary + ", Jev asked " + job.interruptsAsked
                + " time(s) and applied " + job.interruptsApplied + "; " + reason;
        this.lastMiningGoalOutcome = outcome;
        this.lastMiningGoalGained = gained;
        this.recordMiningGoalYield(gained);
        this.actionReports.addLast(outcome);
        LOG.info("Bot {} {}", this.bot.getName().getString(), outcome);
        this.cooldownTicks = 0;
    }

    /**
     * Watch for the three things that interrupt a branch-mining trip, and let Jev decide about them.
     *
     * <p>Only the cases that are actually true are offered, and the candidates are legal by
     * construction - the rule every typed question in this project follows, so an answer can never
     * ask for something the bot may not do (a phantom is never offered "fight it"). With no adviser,
     * or with the gate off, each case falls back to exactly what the runtime did before Jev existed,
     * so the skill is never worse off for having asked.
     *
     * @return true when the trip should hold this tick
     */
    private boolean tickBranchInterrupt(BranchMineJob job) {
        long now = this.bot.level().getGameTime();
        if (job.interruptPending) {
            return true;
        }
        if (now < job.interruptQuietUntil) {
            return false;
        }
        net.minecraft.world.item.ItemStack tool = this.bot.getMainHandItem();
        int usesLeft = tool.isEmpty() || !tool.isDamageableItem()
                ? Integer.MAX_VALUE : tool.getMaxDamage() - tool.getDamageValue();
        boolean toolWorn = usesLeft <= BRANCH_TOOL_DURABILITY_FLOOR;
        boolean packFull = this.emptyInventorySlots() <= 2;
        LivingEntity threat = this.nearestHostileWithin(BRANCH_THREAT_RADIUS);

        String caseName;
        String state;
        Map<String, String> candidates = new LinkedHashMap<>();
        boolean phantom = this.phantomThreatening();
        if (threat != null) {
            caseName = "mob_nearby";
            state = "Branch mining interrupted. bot=" + this.bot.getName().getString()
                    + "; dimension=" + this.currentDimension()
                    + "; mob=" + threat.getName().getString()
                    + "; distance=" + Math.round(Math.sqrt(this.bot.distanceToSqr(threat)))
                    + "; under_attack=" + this.underAttack()
                    + "; phantom=" + phantom
                    + "; health=" + String.format(java.util.Locale.ROOT, "%.1f", this.bot.getHealth())
                    + "; food=" + this.bot.getFoodData().getFoodLevel()
                    + "; holding=" + (tool.isEmpty() ? "empty hand" : tool.getHoverName().getString())
                    + "; tool_uses_left=" + (usesLeft == Integer.MAX_VALUE ? "n/a" : usesLeft)
                    + "; empty_slots=" + this.emptyInventorySlots()
                    + "; target=" + job.primary
                    + "; branches_done=" + job.branchesDug + "/" + job.maxBranches;
            candidates.put("KEEP_MINING", "The mob is not a threat yet; carry on with the pattern");
            if (!phantom) {
                candidates.put("SWAP_TO_WEAPON",
                        "Hold the best weapon and fight it off before carrying on");
            }
            candidates.put("RETREAT_HOME", "Abandon the trip and get out of the mine");
            candidates.put("ESCALATE_LLM", "None of these fit; ask the planning model");
        } else if (toolWorn) {
            caseName = "tool_worn";
            state = "Branch mining interrupted. bot=" + this.bot.getName().getString()
                    + "; holding=" + (tool.isEmpty() ? "empty hand" : tool.getHoverName().getString())
                    + "; tool_uses_left=" + (usesLeft == Integer.MAX_VALUE ? "n/a" : usesLeft)
                    + "; spare_tool=" + (this.hasSpareTool() ? "yes" : "no")
                    + "; empty_slots=" + this.emptyInventorySlots()
                    + "; target=" + job.primary
                    + "; branches_done=" + job.branchesDug + "/" + job.maxBranches
                    + "; main_blocks=" + job.mainBlocksDug;
            if (this.hasSpareTool()) {
                candidates.put("SWAP_TOOL", "Hold another pickaxe you are carrying and carry on");
            }
            candidates.put("KEEP_MINING", "Use this tool until it breaks; there is more to find");
            candidates.put("RETURN_HOME", "End the trip now and bank what you have");
            candidates.put("ESCALATE_LLM", "None of these fit; ask the planning model");
        } else if (packFull) {
            caseName = "pack_full";
            state = "Branch mining interrupted. bot=" + this.bot.getName().getString()
                    + "; empty_slots=" + this.emptyInventorySlots()
                    + "; target=" + job.primary
                    + "; branches_done=" + job.branchesDug + "/" + job.maxBranches
                    + "; main_blocks=" + job.mainBlocksDug
                    + "; carrying=" + this.matchingResourceCount(job.primary) + " matching item(s)";
            candidates.put("RETURN_HOME", "End the trip and take the haul home");
            candidates.put("KEEP_MINING", "Carry on; anything that will not fit can be dropped");
            candidates.put("ESCALATE_LLM", "None of these fit; ask the planning model");
        } else {
            return false;
        }

        JevClient adviser = this.jevClient;
        JevClient.GateMode mode = adviser == null ? JevClient.GateMode.OFF
                : adviser.settings().interrupts();
        LOG.info("Bot {} branch mining interrupt: case={} holding={} uses_left={} adviser={} gate={}",
                this.bot.getName().getString(), caseName,
                tool.isEmpty() ? "empty" : tool.getHoverName().getString(),
                usesLeft == Integer.MAX_VALUE ? "n/a" : usesLeft,
                adviser == null ? "none" : "present", mode);
        if (adviser == null || !adviser.settings().isUsable() || mode == JevClient.GateMode.OFF) {
            this.applyDefaultInterrupt(job, caseName);
            return true;
        }
        job.interruptPending = true;
        job.interruptsAsked++;
        job.lastInterrupt = caseName;
        String askedState = state;
        String askedCase = caseName;
        Map<String, String> askedCandidates = candidates;
        CompletableFuture
                .supplyAsync(() -> adviser.choose(askedState, "mining_interrupt",
                        com.melody.mcagent.rt.llm.JevPrompts.MINING_INTERRUPT, askedCandidates),
                        executor())
                .thenAccept(choice -> this.bot.server.execute(
                        () -> this.applyBranchInterrupt(job, adviser, mode, askedCase, choice)));
        return true;
    }

    /** What the trip did about an interruption before Jev existed, used when nobody can be asked. */
    private void applyDefaultInterrupt(BranchMineJob job, String caseName) {
        job.interruptQuietUntil = this.bot.level().getGameTime() + BRANCH_INTERRUPT_QUIET_TICKS;
        if ("pack_full".equals(caseName)) {
            job.returning = true;
            this.actionReports.addLast("branch mining is returning: the pack is full and no adviser "
                    + "could be asked about it");
        }
    }

    /** Apply a Jev answer on the server thread: only the whitelisted, bounded choices move the bot. */
    private void applyBranchInterrupt(BranchMineJob job, JevClient adviser, JevClient.GateMode mode,
                                      String caseName, JevClient.Choice choice) {
        if (this.branchMine != job) {
            return;
        }
        job.interruptPending = false;
        long now = this.bot.level().getGameTime();
        boolean failed = choice.failed();
        boolean confident = !failed && choice.confidence() >= MIN_ACTIVE_JEV_CONFIDENCE;
        boolean applied = mode == JevClient.GateMode.ACTIVE && confident;
        LOG.info("JEV {} bot={} event=MINING_INTERRUPT case={} choice={} confidence={} applied={} "
                        + "error={}",
                mode == JevClient.GateMode.ACTIVE ? "ACTIVE" : "SHADOW",
                this.bot.getName().getString(), caseName, choice.choice(),
                String.format(java.util.Locale.ROOT, "%.3f", choice.confidence()), applied,
                failed ? firstLine(choice.error()) : "-");
        if (!applied) {
            // Low confidence, an error or shadow mode: keep mining, and do not ask again for a while.
            job.interruptQuietUntil = now + BRANCH_INTERRUPT_QUIET_TICKS;
            if (failed) {
                this.applyDefaultInterrupt(job, caseName);
            }
            return;
        }
        job.interruptsApplied++;
        job.interruptQuietUntil = now + BRANCH_INTERRUPT_QUIET_TICKS;
        switch (choice.choice()) {
            case "KEEP_MINING" -> this.actionReports.addLast(
                    "Jev judged the " + caseName.replace('_', ' ') + " not worth stopping for ("
                            + String.format(java.util.Locale.ROOT, "%.2f", choice.confidence())
                            + "); carrying on with the pattern");
            case "SWAP_TOOL" -> {
                String swapped = this.swapToBestTool(new String[] { "diamond_pickaxe",
                        "iron_pickaxe", "stone_pickaxe", "wooden_pickaxe" });
                this.actionReports.addLast("Jev said to change tools: " + swapped);
            }
            case "SWAP_TO_WEAPON" -> {
                String swapped = this.swapToBestTool(new String[] { "diamond_sword", "iron_sword",
                        "stone_sword", "wooden_sword", "diamond_axe", "iron_axe", "stone_axe" });
                this.actionReports.addLast("Jev said to arm up: " + swapped);
            }
            case "RETURN_HOME" -> {
                job.returning = true;
                this.actionReports.addLast("Jev ended the branch-mining trip: returning home now");
            }
            case "RETREAT_HOME" -> {
                job.returning = true;
                this.breakOffForDanger("Jev read the " + caseName.replace('_', ' ') + " as dangerous");
                this.headHome(true);
                this.actionReports.addLast("Jev said to get out: heading home");
            }
            case "ESCALATE_LLM" -> this.handMiningBackToModel(job,
                    "Jev could not decide about a " + caseName.replace('_', ' ')
                            + " (confidence " + String.format(java.util.Locale.ROOT, "%.2f",
                                    choice.confidence()) + ")");
            default -> this.handMiningBackToModel(job,
                    "Jev answered '" + choice.choice() + "', which was not offered");
        }
    }

    /** Give the trip back to the planning model, with the reason in the next observation. */
    private void handMiningBackToModel(BranchMineJob job, String why) {
        if (this.branchMine != job) {
            return;
        }
        this.branchMine = null;
        String outcome = "branch mining handed back to the model: " + why;
        this.lastMiningGoalOutcome = outcome;
        this.actionReports.addLast(outcome + " - decide what to do about it now");
        LOG.info("Bot {} {}", this.bot.getName().getString(), outcome);
        this.cooldownTicks = 0;
    }

    /** Hold the first of these the bot has, preferring the order given. */
    private String swapToBestTool(String[] preference) {
        for (String query : preference) {
            Actions.Result held = Actions.holdItem(this.bot, query);
            if (held.success()) {
                return held.message();
            }
        }
        return "failed: nothing suitable to hold";
    }

    /** Whether the bot carries another pickaxe besides the one in its hand. */
    private boolean hasSpareTool() {
        var inventory = this.bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot == inventory.selected) {
                continue;
            }
            if (Actions.isUsablePickaxe(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private LivingEntity nearestHostileWithin(double radius) {
        List<net.minecraft.world.entity.monster.Monster> found = this.bot.level().getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                this.bot.getBoundingBox().inflate(radius), mob -> mob.isAlive() && !mob.isRemoved());
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var mob : found) {
            double distance = this.bot.distanceToSqr(mob);
            if (distance < best) {
                best = distance;
                nearest = mob;
            }
        }
        return nearest;
    }

    private void completeMiningGoal(MiningGoal goal, int gained, String reason) {
        if (this.miningGoal != goal) {
            return;
        }
        long seconds = Math.max(0L, (this.bot.level().getGameTime() - goal.startedAt) / 20L);
        this.miningGoal = null;
        String outcome = "persistent mining goal complete after " + seconds
                + "s: gained " + gained + " item(s) matching " + goal.primary + ", started "
                + goal.resourcesStarted + " resource job(s), dug " + goal.tunnelChunks
                + " tunnel chunk(s); " + reason;
        this.lastMiningGoalOutcome = outcome;
        this.lastMiningGoalGained = gained;
        this.recordMiningGoalYield(gained);
        this.actionReports.addLast(outcome);
        LOG.info("Bot {} {}", this.bot.getName().getString(), outcome);
        this.cooldownTicks = 0;
    }

    /** Update the bounded re-plan gate when a runtime-owned mining trip finishes. */
    private void recordMiningGoalYield(int gained) {
        if (gained > 0) {
            this.zeroYieldNextPlannerTick = Long.MIN_VALUE / 2;
            this.zeroYieldBackoffIndex = 0;
            return;
        }
        // The first planning turn after the failure is immediate. shouldBackoffZeroYieldPlanning()
        // consumes that opportunity and arms the first delay before a second one.
        this.zeroYieldNextPlannerTick = this.bot.level().getGameTime();
    }

    /** Test seam for the production failure: a completed mining trip brought back no target ore. */
    public void recordZeroYieldMiningGoalForTest() {
        this.lastMiningGoalGained = 0;
        this.recordMiningGoalYield(0);
    }

    /** Start combat exactly as the tool would, for deterministic smoke tests. */
    public String attackAsTool(Entity target) {
        if (!this.policy.canAttack()) {
            return "failed: you are not allowed to attack";
        }
        return this.startCombat(target);
    }

    /**
     * Test seam: pretend the quiet period has already elapsed.
     *
     * <p>Exists so a harness can reach the narration rule without waiting five real minutes. Without
     * it the quiet period masks the narration rule in a test that sends its lines seconds apart, and
     * a control run would "pass" for the wrong reason - which is exactly the kind of false confidence
     * this project has paid for before.
     */
    public void clearQuietPeriodForTest() {
        this.lastSpokenTick = this.bot.level().getGameTime() - UNPROMPTED_CHAT_COOLDOWN;
    }

    /** True while a mining or collection job is running. For tests and diagnostics. */
    public boolean isMining() {
        return this.mineJob != null;
    }

    /** True while the bot is pursuing or striking a combat target. */
    public boolean isFighting() {
        return this.combatJob != null;
    }

    /** How the last persistent mining trip ended, or null if none has run. */
    @Nullable
    public String lastMiningGoalOutcome() {
        return this.lastMiningGoalOutcome;
    }

    /** True while the runtime-owned persistent mining skill is driving this bot. */
    public boolean isRunningMiningGoal() {
        return this.miningGoal != null;
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
        this.noActionStreak = 0;
        this.clearNoProgress();
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
        this.standingGoalVersion++;
        this.unassignedWorkActive = false;
        this.cachedToolSchemaTokens = -1;
        this.noActionStreak = 0;
        this.clearNoProgress();
        // A changed operator objective is new information: an old empty mining trip and an old
        // CONTINUE answer must not suppress the first decision about it.
        this.lastMiningGoalGained = -1;
        this.zeroYieldNextPlannerTick = Long.MIN_VALUE / 2;
        this.zeroYieldBackoffIndex = 0;
        this.clearRouteLease();
        // Remembered, not just pinned. The pin lives in the transcript, which a reload throws away
        // with the brain: an operator's "去挖矿" survived until the next hot deploy and then vanished,
        // and with it the runtime's ability to carry the job without a planning call.
        if (goal == null || goal.isBlank()) {
            this.memory().removeSystemValue(STANDING_GOAL_STATE);
        } else {
            this.memory().putSystemValue(STANDING_GOAL_STATE, goal);
        }

        // The identity and ability pins are installed on the first model turn. If a command sets
        // the goal before then, leave the transcript empty; beginDecision restores this saved value
        // after creating those two pins, in the correct order and without a duplicate goal.
        if (!this.initialised) {
            this.cooldownTicks = 0;
            return;
        }

        // Rebuild the pinned prefix: identity, abilities, then the goal if one is set.
        while (this.history.size() > 2 && this.pinnedCount > 2) {
            this.history.remove(this.pinnedCount - 1);
            this.pinnedCount--;
        }
        if (goal != null && !goal.isBlank()) {
            this.history.add(LlmClient.Message.system("YOUR STANDING OBJECTIVE: " + goal
                    + "\nWhen this objective is actually finished, clear it in the same decision: "
                    + "use say(goal_complete=true) with your final report, or complete_goal if "
                    + "no report is needed. Do not keep working on an already completed objective."
                    + "\nIf it cannot be done - the material or tool it needs is not there, or the "
                    + "world keeps refusing it - say what is missing and call abandon_goal instead of "
                    + "trying the same thing again. Giving up honestly is better than looping."));
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

    /** Accept a completion declaration only for the goal this model turn actually saw. */
    private String completeStandingGoal() {
        String goal = this.standingGoal;
        if (goal == null || goal.isBlank()) {
            return "failed: there is no standing objective to complete";
        }
        if (this.decisionGoalVersion != this.standingGoalVersion) {
            return "failed: the standing objective changed while this decision was in flight";
        }
        if (this.isLongActionRunning() || !this.queue.isEmpty() || this.miningGoal != null
                || this.branchMine != null) {
            return "failed: work is still running; complete the standing objective after it finishes";
        }
        if (this.turnCalls != null && this.executingCallIndex >= 0) {
            for (int i = this.executingCallIndex + 1; i < this.turnCalls.size(); i++) {
                String later = this.turnCalls.get(i).name();
                if (!"remember".equals(later) && !"recall".equals(later)
                        && !"forget".equals(later)) {
                    return "failed: finish the remaining actions before declaring the standing "
                            + "objective complete";
                }
            }
        }
        this.setStandingGoal(null);
        this.goalCompletedThisTurn = true;
        this.actionReports.addLast("the previous standing objective was completed and cleared; "
                + "do not resume it unless a player assigns it again");
        LOG.info("Bot {} completed and cleared standing goal: {}",
                this.bot.getName().getString(), goal);
        return "standing objective completed and cleared";
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
            this.noActionStreak = 0;
            this.clearNoProgress();
            this.cooldownTicks = 0;
            // Unpausing is a trigger in its own right: the bot has to work out what, if anything, it
            // still has to carry on with. Only when it really was paused, so a redundant
            // setPaused(false) cannot overwrite a trigger that belongs to the current tick.
            if (wasPaused) {
                this.pendingTrigger = Trigger.RESUME;
            }
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
            this.authorisedTunnelClearance.clear();
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
            // Every staircase would dig through something. If that something is a player's building,
            // the honest escape is the one a player would take: walk out of the door.
            String walkedOut = this.walkOutOfStructure(level);
            if (walkedOut != null) {
                return walkedOut;
            }
            return "failed: no safe rising staircase can be dug from here. Fluids, a gap under the "
                    + "next tread, falling blocks, a player-built structure or unbreakable terrain "
                    + "block every direction; walk out through the building's door, or use "
                    + "return_to_spawn.";
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

    /**
     * Whether one cell of a straight run may be dug, and which blocks that cell has to clear.
     *
     * <p>{@code dig_tunnel} and the branch-mining controller both come through here, so the two can
     * never disagree about fluid, falling blocks, unbreakable terrain, player-built structures or the
     * protected home surface - the rules a branch would otherwise be free to ignore simply because it
     * was not {@code dig_tunnel} that asked.
     *
     * @param feet     where the bot will stand for this cell
     * @param levelRun true for a horizontal run, false for a descending one
     * @param runStart where the run began, for the player-structure test
     * @param clear    appended with the blocks this cell must break; empty on refusal
     * @return the reason the cell may not be dug, or null when it is fine
     */
    @Nullable
    private String checkRunCell(BlockPos feet, boolean levelRun, BlockPos runStart,
                                List<BlockPos> clear) {
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return "your current dimension cannot be excavated";
        }
        List<BlockPos> clearance = levelRun
                ? List.of(feet, feet.above())
                : List.of(feet, feet.above(), feet.above(2));
        for (BlockPos pos : clearance) {
            if (com.melody.mcagent.rt.path.PathFinder.canPass(level, pos)) {
                continue;
            }
            // A horizontal tunnel in the surface band is a trench. The one entrance may descend
            // through this band, but branch/strip mining starts only after it is underground.
            if (levelRun && this.isProtectedHomeSurface(pos)) {
                return "the protected home surface at " + pos.toShortString()
                        + "; descend through the established entrance before branch mining";
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()
                    || state.getBlock() instanceof FallingBlock
                    || level.getBlockEntity(pos) != null
                    || Actions.ticksToBreak(this.bot, pos) == Integer.MAX_VALUE) {
                return "fluid, falling, protected or unbreakable terrain at " + pos.toShortString();
            }
            if (PlayerStructure.isProtected(level, runStart, pos)) {
                return "the player-built structure at " + pos.toShortString();
            }
            clear.add(pos.immutable());
        }
        return null;
    }

    /**
     * Queue one straight run of tunnel cells from where the bot stands, for a skill that owns its own
     * path - the branch-mining controller. Unlike {@code dig_tunnel} this never walks back to a saved
     * working face and never touches the single-entrance rule: the pattern it serves is the path.
     *
     * @return how many blocks of the run were queued, or -1 when nothing could be dug
     */
    private int queueBranchRun(Direction direction, int length, boolean descending,
                               List<String> problem) {
        if (!(this.bot.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            problem.add("your current dimension cannot be excavated");
            return -1;
        }
        BlockPos start = this.bot.blockPosition();
        List<TunnelStep> planned = new ArrayList<>();
        for (int index = 1; index <= length; index++) {
            BlockPos feet = start.relative(direction, index);
            if (descending) {
                feet = feet.below(index);
            }
            if (feet.getY() <= level.getMinBuildHeight()
                    || !level.getWorldBorder().isWithinBounds(feet)) {
                problem.add("the world boundary");
                break;
            }
            BlockPos floor = feet.below();
            BlockState floorState = level.getBlockState(floor);
            if (floorState.getCollisionShape(level, floor).isEmpty()) {
                // Open space under the line: a cave, a ravine or somebody's cellar. Remember the floor
                // cell so the controller can pave it instead of turning the corridor away from a level
                // that is perfectly good on the far side.
                problem.add("a missing floor at " + floor.toShortString());
                this.branchRefusedFloor = floor.immutable();
                break;
            }
            if (!floorState.getFluidState().isEmpty()
                    || floorState.getBlock() instanceof FallingBlock
                    || isEscapeHazard(floorState)) {
                problem.add("an unsafe or missing floor at " + floor.toShortString());
                break;
            }
            List<BlockPos> clear = new ArrayList<>(descending ? 3 : 2);
            String refusal = this.checkRunCell(feet, !descending, start, clear);
            if (refusal != null) {
                problem.add(refusal);
                break;
            }
            if (ceilingWouldFall(level, feet, 2)) {
                problem.add("the gravel or sand above " + feet.toShortString()
                        + ", which would fall in on you");
                break;
            }
            planned.add(new TunnelStep(feet.immutable(), List.copyOf(clear)));
            if (planned.size() >= MAX_BRANCH_RUN_BLOCKS) {
                break;
            }
        }
        if (planned.isEmpty()) {
            return -1;
        }
        int sequence = 0;
        for (TunnelStep step : planned) {
            for (BlockPos clear : step.clear()) {
                this.authorisedTunnelClearance.add(clear.immutable());
                JsonObject mine = new JsonObject();
                mine.addProperty("x", clear.getX());
                mine.addProperty("y", clear.getY());
                mine.addProperty("z", clear.getZ());
                this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                        "branch_mine_" + (++sequence), "mine", mine), -1, true));
            }
            JsonObject walk = new JsonObject();
            walk.addProperty("x", step.feet().getX());
            walk.addProperty("y", step.feet().getY());
            walk.addProperty("z", step.feet().getZ());
            this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                    "branch_walk_" + (++sequence), "goto", walk), -1, true));
        }
        this.branchRunEnd = planned.get(planned.size() - 1).feet();
        return planned.size();
    }

    public String digTunnel(String directionName, String modeName, int requestedLength,
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
        // `new_site` used to be merely a prompt-level promise. Production showed the planner setting
        // it dozens of times around the base, overwriting the remembered route and opening a fresh
        // staircase each time. Once a home-area route exists it is immutable from gameplay tools:
        // continuing must reuse it, and changing it requires explicit operator maintenance rather
        // than one model-generated boolean.
        if (newSite && saved != null && saved.dimension().equals(dimension)
                && (this.isNearHome(start) || this.isNearHome(saved.entrance()))) {
            return "failed: home already has one established mine entrance at "
                    + saved.entrance().toShortString()
                    + "; a second entrance is forbidden. Call dig_tunnel without new_site so the "
                    + "existing route is reused";
        }
        if (!newSite && saved != null && saved.dimension().equals(dimension)
                && start.distSqr(saved.face()) > 64.0D) {
            List<QueuedCall> resume = new ArrayList<>();
            int sequence = 0;
            if (start.distSqr(saved.entrance()) > 9.0D) {
                resume.add(new QueuedCall(new LlmClient.ToolCall(
                        "plan_step_mine_resume_" + (++sequence), "goto",
                        positionArgs(saved.entrance())), -1, true));
            }
            BlockPos lastResume = saved.entrance();
            for (BlockPos waypoint : saved.waypoints()) {
                if (waypoint.distSqr(lastResume) < 4.0D || waypoint.distSqr(start) < 4.0D) {
                    continue;
                }
                resume.add(new QueuedCall(new LlmClient.ToolCall(
                        "plan_step_mine_resume_" + (++sequence), "goto",
                        positionArgs(waypoint)), -1, true));
                lastResume = waypoint;
            }
            if (lastResume.distSqr(saved.face()) >= 4.0D) {
                resume.add(new QueuedCall(new LlmClient.ToolCall(
                        "plan_step_mine_resume_" + (++sequence), "goto",
                        positionArgs(saved.face())), -1, true));
            }
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
        // A new hole may not be opened inside somebody's building. The resume path above is walking
        // rather than digging, so it is deliberately allowed through: that is how an existing mine
        // outside the base is re-entered.
        PlayerStructure.Region inside = PlayerStructure.detectCached(level, start);
        if (inside == null) {
            LOG.info("Bot {} dig_tunnel from {}: no player-built structure detected around the bot",
                    this.bot.getName().getString(), start.toShortString());
        } else {
            LOG.info("Bot {} dig_tunnel from {}: structure {} containsStart={}",
                    this.bot.getName().getString(), start.toShortString(), inside.describe(),
                    inside.contains(start));
        }
        if (inside != null && inside.contains(start)) {
            LOG.info("Bot {} refused to open a tunnel from {}: {}",
                    this.bot.getName().getString(), start.toShortString(), inside.describe());
            return "failed: you are standing inside the " + inside.describe()
                    + ". Digging here would put a hole in the player's building, so no tunnel may be "
                    + "started from this spot. Walk out through its door or opening first and dig in "
                    + "natural ground away from the base; if a mine already exists, call dig_tunnel "
                    + "again from outside so it walks back to the established working face instead of "
                    + "opening a new hole.";
        }

        BlockPos entrance = saved == null || newSite || !saved.dimension().equals(dimension)
                ? start.immutable() : saved.entrance();
        if (saved == null || newSite || !saved.dimension().equals(dimension)) {
            this.saveMineRoute(new MineRoute(
                    dimension, entrance, start.immutable(), direction, List.of(entrance)));
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
            if (PlayerStructure.isProtected(level, start, floor)) {
                stoppedBy = "the player-built structure floor at " + floor.toShortString();
                break;
            }

            // A level tunnel needs feet+head. Descending one full block while moving horizontally
            // also needs the sloped-ceiling block above the destination head: until gravity has
            // lowered the player, its 1.8-block body still intersects that third block. The exact
            // same collision is why upward escape stairs clear three blocks.
            List<BlockPos> clear = new ArrayList<>("down".equals(mode) ? 3 : 2);
            String cellProblem = this.checkRunCell(feet, "level".equals(mode), start, clear);
            if (cellProblem != null) {
                stoppedBy = cellProblem;
                break;
            }
            blocks += clear.size();
            // Same rule as the escape stair: a tunnel whose ceiling is gravel drops it on the digger.
            if (ceilingWouldFall(level, feet, 2)) {
                stoppedBy = "the gravel or sand above " + feet.toShortString()
                        + ", which would fall in on you";
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
                this.authorisedTunnelClearance.add(clear.immutable());
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
        List<BlockPos> previousWaypoints = saved != null && saved.dimension().equals(dimension)
                && !newSite ? saved.waypoints() : List.of(entrance);
        checkpoint.addProperty("waypoints", encodeMineWaypoints(
                mergeMineWaypoints(previousWaypoints,
                        route.stream().map(TunnelStep::feet).toList(), entrance, finalFace)));
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
            if (part.length != 8 && part.length != 9) {
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
                            Integer.parseInt(part[6])), direction,
                    part.length == 9 ? decodeMineWaypoints(part[8]) : List.of(
                            new BlockPos(Integer.parseInt(part[1]), Integer.parseInt(part[2]),
                                    Integer.parseInt(part[3])),
                            new BlockPos(Integer.parseInt(part[4]), Integer.parseInt(part[5]),
                                    Integer.parseInt(part[6]))));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Reset only the durable tunnel route for an isolated harness world. */
    public void clearMineRouteForTest() {
        this.memory().removeSystemValue(MINE_ROUTE_STATE);
        this.authorisedTunnelClearance.clear();
    }

    private void saveMineRoute(MineRoute route) {
        this.memory().putSystemValue(MINE_ROUTE_STATE,
                route.dimension() + "|" + route.entrance().getX() + "|" + route.entrance().getY()
                + "|" + route.entrance().getZ() + "|" + route.face().getX() + "|"
                + route.face().getY() + "|" + route.face().getZ() + "|"
                + route.direction().getName() + "|" + encodeMineWaypoints(route.waypoints()));
    }

    private static String encodeMineWaypoints(List<BlockPos> points) {
        return points.stream().map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<BlockPos> decodeMineWaypoints(String encoded) {
        List<BlockPos> points = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return points;
        }
        for (String raw : encoded.split(";")) {
            String[] xyz = raw.split(",", -1);
            if (xyz.length == 3) {
                points.add(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
                        Integer.parseInt(xyz[2])));
            }
        }
        return List.copyOf(points);
    }

    /** Keep a useful breadcrumb every few blocks, bounded so resume never creates an endless plan. */
    private static List<BlockPos> mergeMineWaypoints(List<BlockPos> previous, List<BlockPos> added,
                                                      BlockPos entrance, BlockPos face) {
        List<BlockPos> merged = new ArrayList<>();
        merged.add(entrance.immutable());
        for (BlockPos point : previous) {
            if (merged.get(merged.size() - 1).distSqr(point) >= 9.0D) {
                merged.add(point.immutable());
            }
        }
        for (BlockPos point : added) {
            if (merged.get(merged.size() - 1).distSqr(point) >= 9.0D) {
                merged.add(point.immutable());
            }
        }
        if (!merged.get(merged.size() - 1).equals(face)) {
            merged.add(face.immutable());
        }
        if (merged.size() <= 24) {
            return List.copyOf(merged);
        }
        List<BlockPos> bounded = new ArrayList<>(24);
        bounded.add(merged.get(0));
        double stride = (merged.size() - 1) / 23.0D;
        for (int i = 1; i < 23; i++) {
            bounded.add(merged.get(Math.min(merged.size() - 2, (int) Math.round(i * stride))));
        }
        bounded.add(merged.get(merged.size() - 1));
        return List.copyOf(bounded);
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
                    // Emergency excavation may not punch another exit through the ground around
                    // home. A natural/open exit is still usable; otherwise the bot must backtrack
                    // through the established mine or use return_to_spawn.
                    if (this.isProtectedHomeSurface(pos)) {
                        safe = false;
                        break;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty()
                            || state.getBlock() instanceof FallingBlock
                            || level.getBlockEntity(pos) != null) {
                        safe = false;
                        break;
                    }
                    // An escape stair that would break the player's house is not an escape route.
                    // Every direction being blocked by a building is what sends escapeUp to the
                    // walk-out fallback below instead.
                    if (PlayerStructure.isProtected(level, start, pos)) {
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
                // Clearing this tread would drop the gravel above it onto the bot. Six deaths came
                // from exactly that, so the route simply does not go that way.
                if (ceilingWouldFall(level, feet, 2)) {
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

    /**
     * Leave a player-built structure on foot instead of digging through it.
     *
     * <p>Called when every staircase would break the building. A player walks out of their own house
     * through the door, and the bot has ordinary pathfinding that opens doors itself, so the honest
     * answer to "get me out" is a walk. Nothing is broken here; if no walkable exit exists the
     * caller reports the failure and the model falls back to {@code return_to_spawn}.
     *
     * @return what was queued, or {@code null} when there is no walkable way out
     */
    @Nullable
    private String walkOutOfStructure(net.minecraft.server.level.ServerLevel level) {
        BlockPos here = this.bot.blockPosition();
        PlayerStructure.Region region = PlayerStructure.detectCached(level, here);
        if (region == null) {
            return null;
        }
        BlockPos min = region.min();
        BlockPos max = region.max();
        int[][] offsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] offset : offsets) {
            int x = offset[0] > 0 ? max.getX() + 3 : offset[0] < 0 ? min.getX() - 3 : here.getX();
            int z = offset[1] > 0 ? max.getZ() + 3 : offset[1] < 0 ? min.getZ() - 3 : here.getZ();
            int surface = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // The building's own floor level first: that is where a door leads. The height map
            // is only a fallback, because next to a base on a hillside - or in a chunk that has
            // never been generated - it says nothing useful.
            for (int y : new int[] {here.getY(), surface, surface - 1, surface + 1}) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (!com.melody.mcagent.rt.path.PathFinder.canStandAt(level, candidate)) {
                    LOG.info("Bot {} walk-out candidate {} is not standable",
                            this.bot.getName().getString(), candidate.toShortString());
                    continue;
                }
                com.melody.mcagent.rt.path.PathFinder.Path path =
                        com.melody.mcagent.rt.path.PathFinder.findPath(level, here, candidate, 128);
                if (path == null || path.isEmpty()) {
                    LOG.info("Bot {} walk-out candidate {} has no walking route from {}",
                            this.bot.getName().getString(), candidate.toShortString(),
                            here.toShortString());
                    continue;
                }
                this.prependOrAppendMacro(List.of(new QueuedCall(new LlmClient.ToolCall(
                        "leave_structure_1", "goto", positionArgs(candidate)), -1, true)));
                this.cooldownTicks = PLAN_PREFETCH_DELAY_TICKS;
                String message = "you are inside the " + region.describe()
                        + "; walking out through its own door/opening to " + candidate.toShortString()
                        + " instead of digging through the building";
                LOG.info("Bot {} {}", this.bot.getName().getString(), message);
                this.actionReports.addLast(message);
                return message;
            }
        }
        return null;
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

        this.noActionStreak = 0;
        this.clearNoProgress();
        this.cooldownTicks = 0;
        // An operator asking for a decision by name is the one input the cheap layer must never
        // argue with; the trigger travels with the decision so startDecision can say so in the log.
        this.pendingTrigger = Trigger.COMMAND;
        this.startDecision();
    }

    /**
     * Test seam: start a decision labelled exactly as the stuck watchdog labels its own.
     *
     * <p>The real path needs a request to hang for a full minute before the watchdog fires; this
     * makes the label reachable without waiting, so the rule that a forced recovery turn must not
     * consume a player's message can be asserted directly.
     */
    public void forceStuckDecisionForTest() {
        if (this.paused) {
            return;
        }
        this.cooldownTicks = 0;
        this.pendingTrigger = Trigger.STUCK;
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

        this.logStatsWindow();

        // Advance whatever long-running thing owns the bot, and notice when a journey ends.
        this.tickDangerReflex();

        // The field's ripeness is considered even while something else owns the bot: the answer is a
        // scheduling decision, and its "later" lands on the todo list rather than on a model call.
        this.tickFarmRipeness();

        boolean busy = this.tickCombatJob();
        busy = this.tickMineJob() || busy;
        busy = this.tickFarmBuildJob() || busy;
        this.noticeMovementFinished();
        this.tickPendingSleep();
        busy = busy || this.isMoving();

        // Long idle waits are interrupted when the bot moves or receives an item. Neither change
        // needs a model to detect, and both can make a previously impossible standing goal possible.
        if (this.noActionStreak > 0 && !busy && this.queue.isEmpty()
                && !this.thinking.get() && this.cooldownTicks > 0
                && this.bot.level().getGameTime() % 20L == 0L
                && this.idleWakeFingerprint() != this.noActionWakeFingerprint) {
            LOG.info("Bot {} idle wait ended because position or inventory changed",
                    this.bot.getName().getString());
            this.noActionStreak = 0;
            this.cooldownTicks = 0;
            this.unassignedWorkActive = true;
        }

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
        // A decision that never completes is what this watchdog is for. A bot that is busy with a
        // long action that is visibly progressing is not stuck: counting those ticks aborted the
        // plan every sixty seconds during ordinary mining, and the bot then re-decided from scratch -
        // mining blocks that were already gone, planning empty plans, walking back and forth. That
        // churn is what "the bot has crashed" looks like from in game. Movement and mining have
        // their own no-progress checks, so nothing is left unguarded by resetting here.
        if (busy) {
            this.ticksSinceDecision = 0;
        }
        this.ticksSinceDecision++;
        // A deliberate idle cooldown is not a hung decision. Only an in-flight model call can
        // time out here; movement and jobs have their own progress checks.
        if (this.thinking.get() && this.ticksSinceDecision > DECISION_WATCHDOG_TICKS) {
            LOG.warn("Bot {} has not completed a decision in {} ticks (state: {}, paused: {}); forcing one",
                    this.bot.getName().getString(), this.ticksSinceDecision,
                    this.describeCurrentAction(), this.paused);
            this.ticksSinceDecision = 0;
            this.cooldownTicks = 0;
            // A detected hang is not a moment to economise on: label the forced decision so the cheap
            // layer leaves it alone (see startDecision).
            this.pendingTrigger = Trigger.STUCK;
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

        // A MiningGoal is a runtime-owned skill, not a prompt-generated plan. While it is active,
        // ordinary IDLE ticks advance the skill and never buy another planning turn. Direct player
        // chat still proceeds below and can use interrupt to cancel or replace the goal.
        if (!addressed && !busy && this.queue.isEmpty() && this.miningGoal != null
                && !this.thinking.get()) {
            this.advanceMiningGoal();
            if (!this.queue.isEmpty()) {
                this.runQueued();
            }
            busy = this.isLongActionRunning();
        }
        if (!addressed && this.miningGoal != null) {
            return;
        }

        // A branch-mining trip owns the bot the same way, and for the same reason: it buys no
        // planning turns while it runs. Its interruptions are checked here, every tick, so a worn
        // tool or a full pack reaches Jev while the run is still going.
        if (!addressed && this.branchMine != null && !this.thinking.get()) {
            this.tickBranchInterrupt(this.branchMine);
        }
        if (!addressed && !busy && this.queue.isEmpty() && this.branchMine != null
                && !this.thinking.get()) {
            this.advanceBranchMine();
            if (!this.queue.isEmpty()) {
                this.runQueued();
            }
            busy = this.isLongActionRunning();
        }
        if (!addressed && this.branchMine != null) {
            return;
        }

        // A todo item is work the bot already decided to do; picking it up must cost no model turn.
        if (!addressed) {
            this.tickTodo();
        }

        // A field being built owns the bot outright, the way the mining goal does. Merely being busy
        // is not enough: the lookahead below deliberately buys a planning turn every couple of
        // seconds while something long is running, which is right for a walk or a dig the model asked
        // for and pure waste for a build it already described. Measured before this line existed: a
        // single 5x5 field cost five planning turns to build, all of them asking "what next?" about a
        // job that was already in progress.
        if (!addressed && this.farmBuildJob != null) {
            return;
        }

        // A FarmGoal works the same way with one difference: it only claims the tick when there is
        // something to do. Between harvests the bot is free to plan and do other work, and a field
        // that is merely growing never costs a planning turn - but a field must not swallow the bot
        // either, which is why this does not return unconditionally the way the mining goal does.
        if (!addressed && !busy && this.queue.isEmpty() && this.farmGoal != null
                && !this.thinking.get() && this.advanceFarmGoal()) {
            if (!this.queue.isEmpty()) {
                this.runQueued();
            }
            return;
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
        //
        // Which layer owns this decision is decided by what woke it: a watchdog restart or an
        // operator's think already claimed the trigger earlier in this tick, and a prompt-worthy chat
        // line belongs to the speech gate. Everything else is the ordinary idle cooldown, which is
        // the routing layer's case.
        if (this.pendingTrigger == Trigger.IDLE && addressed) {
            this.pendingTrigger = Trigger.CHAT;
        }
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

    /** Health at which the bot breaks off what it is doing, and health at which it may resume. */
    private static final float RETREAT_HEALTH = 8.0F;
    private static final float RECOVERED_HEALTH = 14.0F;
    /** Food level at which eating stops being the model's choice and becomes a reflex. */
    private static final int EAT_FOOD = 6;
    /** The reflex runs at most this often; it is a reflex, not a per-tick loop. */
    private static final int REFLEX_INTERVAL_TICKS = 20;

    /** True while the bot has broken off work to recover. */
    private boolean retreating;
    private long lastReflexTick = -1L;
    /** The last thing the reflex said, so it does not repeat itself every second. */
    private String lastReflexReport = "";

    /**
     * Survival reflexes: what the bot does about a threat without being asked.
     *
     * <p>Production made the case for this. 84% of the bot's deaths happened outside combat - 25
     * while mining, 21 while walking - because nothing ever interrupted work for a threat. It kept
     * digging at {@code health=0.3} and died three seconds later, and it asked players for food in
     * chat ("血量只剩5了，你有食物吗？") while standing next to its own full inventory.
     *
     * <p>Two rules, both of them things a player does without deciding to:
     * <ul>
     *   <li><b>Phantoms are not fought.</b> They cannot be reached on foot, and sleeping is what
     *       stops them. The bot goes home and gets into bed.</li>
     *   <li><b>Low health breaks off work.</b> The current job is abandoned, the bot eats if it is
     *       hungry, and it goes home. It stays in that state until it has actually recovered, so a
     *       model that re-issues "mine" while the bot is nearly dead is refused rather than obeyed
     *       into its own grave.</li>
     * </ul>
     */
    private void tickDangerReflex() {
        if (this.bot.isRemoved() || this.bot.isDeadOrDying() || this.paused) {
            return;
        }
        long now = this.bot.level().getGameTime();
        if (this.lastReflexTick >= 0 && now - this.lastReflexTick < REFLEX_INTERVAL_TICKS) {
            return;
        }
        this.lastReflexTick = now;

        if (this.phantomThreatening()) {
            this.breakOffForDanger("phantoms are circling and cannot be fought on foot");
            // Eat on the way. Found by the harness: the phantom branch returned before the eating
            // branch ever ran, so a bot hiding from phantoms at food 4 never regenerated and sat at
            // 5 health until something else killed it.
            this.eatIfNeeded();
            this.headHome(true);
            return;
        }

        float health = this.bot.getHealth();
        if (!this.retreating && health <= RETREAT_HEALTH) {
            this.retreating = true;
            this.breakOffForDanger("health was " + String.format(java.util.Locale.ROOT, "%.1f", health));
        }
        if (!this.retreating) {
            return;
        }
        if (health >= RECOVERED_HEALTH && !this.underAttack()) {
            this.retreating = false;
            this.lastReflexReport = "";
            this.actionReports.addLast("recovered to "
                    + String.format(java.util.Locale.ROOT, "%.1f", health)
                    + " health; I can work again");
            return;
        }
        this.eatIfNeeded();
        this.headHome(false);
    }

    /**
     * Would standing here get the bot buried?
     *
     * <p>Breaking a block takes the support out from under whatever is falling above it, and what
     * lands is the bot. Six of production's deaths were exactly this - self-dug shafts in gravel and
     * sand, "suffocated in a wall", most of them within seconds of starting the dig. The check is on
     * the block <em>above the cleared space</em>, because that is the one the dig would drop: the
     * excavation macros already refuse to break a falling block directly, which is not the same thing.
     *
     * @param headroom how many blocks the bot needs clear above its feet
     */
    private static boolean ceilingWouldFall(net.minecraft.server.level.ServerLevel level,
                                            BlockPos feet, int headroom) {
        for (int up = 1; up <= headroom + 1; up++) {
            BlockPos above = feet.above(up);
            if (level.isOutsideBuildHeight(above)) {
                return false;
            }
            BlockState state = level.getBlockState(above);
            if (state.getBlock() instanceof FallingBlock) {
                return true;
            }
            // Anything solid above stops the fall, so only the first obstruction matters.
            if (!state.getCollisionShape(level, above).isEmpty()) {
                return false;
            }
        }
        return false;
    }

    /** Is a phantom visible nearby, or was the bot just bitten by one? */
    private boolean phantomThreatening() {
        if (this.bot.getLastHurtByMob() instanceof net.minecraft.world.entity.monster.Phantom) {
            return true;
        }
        for (Perception.SeenEntity seen : Perception.visibleEntities(this.bot)) {
            if (seen.entity() instanceof net.minecraft.world.entity.monster.Phantom) {
                return true;
            }
        }
        return false;
    }

    /** Was the bot hurt in the last few seconds? */
    private boolean underAttack() {
        return this.bot.getLastHurtByMob() != null
                && this.bot.tickCount - this.bot.getLastHurtByMobTimestamp() < 100;
    }

    /**
     * Drop whatever the bot is doing because of a threat, and say so once per episode.
     *
     * <p>Only once: a bot that is being bitten reports it, and then acts. Repeating it every second
     * would fill the log with the same line and tell the model nothing new.
     */
    private void breakOffForDanger(String reason) {
        boolean busy = this.isLongActionRunning() || !this.queue.isEmpty();
        if (busy) {
            this.abandonCurrentAction();
            this.abandonPlan("I broke off: " + reason);
        }
        this.miningGoal = null;
        if (this.farmBuildJob != null) {
            this.finishFarmBuild("abandoned: " + reason);
        }
        if (!reason.equals(this.lastReflexReport)) {
            this.lastReflexReport = reason;
            this.actionReports.addLast("I stopped what I was doing because " + reason);
            LOG.info("Bot {} broke off for danger: {}", this.bot.getName().getString(), reason);
        }
    }

    /** Eat if the bot is hungry and carrying food; the same action the {@code eat} tool performs. */
    private void eatIfNeeded() {
        if (this.bot.getFoodData().getFoodLevel() > EAT_FOOD || this.bot.isUsingItem()) {
            return;
        }
        String eaten = this.eat("");
        if (!eaten.startsWith("failed")) {
            this.actionReports.addLast("I ate because my food was low: " + eaten);
        }
    }

    /**
     * Walk home, and get into bed if that is what the bot came for.
     *
     * <p>Home is the bed it last slept in - the respawn point - which is also where its chests and
     * its spare armour are. A bot with no bed has nowhere to go, and is told so rather than being
     * left to pace.
     */
    private void headHome(boolean sleepWhenThere) {
        BlockPos home = this.homePosition();
        if (home == null) {
            if (sleepWhenThere && !"no bed".equals(this.lastReflexReport)) {
                this.lastReflexReport = "no bed";
                this.actionReports.addLast("I have no bed to go to: phantoms keep coming until "
                        + "somebody sleeps, so place a bed and sleep in it");
            }
            return;
        }
        double distance = Math.sqrt(this.bot.blockPosition().distSqr(home));
        if (distance > 3.0D) {
            if (!this.isMoving()) {
                var handle = com.melody.mcagent.rt.Agent.botManager() == null ? null
                        : com.melody.mcagent.rt.Agent.botManager()
                                .get(this.bot.getName().getString());
                if (handle != null) {
                    handle.movement().setPathTarget(home, GOTO_PLAN_RANGE);
                }
            }
            return;
        }
        if (sleepWhenThere && !this.bot.isSleeping()) {
            BlockPos bed = Actions.findBed(this.bot, 16);
            if (bed != null) {
                this.pendingSleep = bed;
            }
        }
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
                this.authorisedTunnelClearance.clear();
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
    /**
     * Kick off one observe → decide → act cycle without blocking the server thread.
     *
     * <p>The first layer is always the cheap typed decision, and only the triggers it is allowed to
     * answer are offered to it. Chat belongs to the speech gate, an expired cooldown belongs to the
     * routing layer, and two triggers are never second-guessed at all - see below.
     */
    public void startDecision() {
        if (this.paused) {
            return;
        }
        Trigger requested = this.pendingTrigger;
        if ((requested == Trigger.IDLE || requested == Trigger.RESUME)
                && (this.standingGoal == null || this.standingGoal.isBlank())
                && !this.unassignedWorkActive && this.queue.isEmpty()
                && !this.isLongActionRunning()
                && !com.melody.mcagent.rt.perception.ChatLog.shouldRespondPromptly(this.bot)) {
            // The brain is new after a reload; the memory is not, and a goal is remembered precisely
            // so it survives one. Parking without reading it back is how that silently stopped
            // working: production came back from a hot deploy with the goal in memory, history=0, no
            // decision, and the event-wait cooldown - a bot that had been given a task and would
            // never do it. Adopting it here also means the guard only ever parks a bot with no task
            // anywhere, which is what it was written for.
            String rememberedGoal = this.memory().systemValue(STANDING_GOAL_STATE);
            if (rememberedGoal != null && !rememberedGoal.isBlank()) {
                this.setStandingGoal(rememberedGoal);
                this.cooldownTicks = 0;
                LOG.info("Bot {} picked its standing goal back up after a reload: {}",
                        this.bot.getName().getString(), rememberedGoal);
            } else {
                // No task and no new event: there is no decision for either model to make. Arm the
                // existing inventory/position wake path and keep all survival reflexes ticking.
                this.cooldownTicks = Integer.MAX_VALUE;
                this.noActionStreak = Math.max(1, this.noActionStreak);
                this.noActionWakeFingerprint = this.idleWakeFingerprint();
                return;
            }
        }
        if (!this.thinking.compareAndSet(false, true)) {
            return;
        }
        // Consume the label now. Whatever started this decision, the next one starts from a clean
        // slate rather than inheriting a marker that belonged to a moment already gone.
        Trigger trigger = this.pendingTrigger;
        this.pendingTrigger = Trigger.IDLE;
        this.currentTrigger = trigger;
        if (trigger == Trigger.CHAT || trigger == Trigger.COMMAND) {
            this.unassignedWorkActive = true;
        }
        if (trigger == Trigger.CHAT || trigger == Trigger.COMMAND) {
            // New input is new information: a refusal streak from before it says nothing about now.
            this.noActionStreak = 0;
            this.clearNoProgress();
        }
        this.ticksSinceDecision = 0;

        // A mining recovery is in flight: hold the ordinary decision for the fraction of a second it
        // needs, so the cheap answer is not overtaken by the very planning turn it exists to save.
        // Chat is never held - a player waiting for an answer outranks a recovery - and the hold is
        // bounded, so a request that never returns cannot stall the bot.
        if (trigger == Trigger.IDLE && this.jevRecoveryRequestedAt >= 0
                && this.bot.level().getGameTime() - this.jevRecoveryRequestedAt
                        < RECOVERY_HOLD_TICKS) {
            this.thinking.set(false);
            this.cooldownTicks = 2;
            return;
        }

        switch (trigger) {
            case CHAT -> {
                // Before spending a whole planning turn on chat, let Jev answer the cheap question
                // the planning model keeps getting wrong: does this message need an answer at all?
                // A false return means the gate declined or was not applicable, in which case the
                // turn goes ahead exactly as it did before the gate existed.
                if (!this.trySpeechGate()) {
                    this.beginDecision();
                }
            }
            case IDLE, RESUME -> {
                // Is there work in flight worth carrying on with, or does this need a fresh plan?
                if (!this.tryRoutingDecision(trigger)) {
                    this.beginDecision();
                }
            }
            case COMMAND, STUCK -> {
                // An explicit operator instruction and a detected hang are exactly the cases that
                // must never be second-guessed. "/mcagent think" is a human asking for a fresh look
                // at the world right now, and the watchdog fires only when no decision has completed
                // for a minute - in both, a cheap "carry on with what you have" would silently
                // cancel the very thing the operator or the watchdog asked for. They therefore go
                // straight to the planning model, and the bypass is logged rather than left to be
                // inferred from the absence of a JEV line.
                this.logRoutingBypassed(trigger);
                this.beginDecision();
            }
        }
    }

    /**
     * Say, once and greppably, that the cheap layer deliberately did not touch this decision.
     *
     * <p>Only logged while routing is actually live: with {@code routing=off} or no adviser there is
     * nothing to have bypassed, and a line per {@code /mcagent think} would be noise claiming an
     * action that never existed.
     */
    private void logRoutingBypassed(Trigger trigger) {
        JevClient adviser = this.jevClient;
        if (adviser == null || !adviser.settings().isUsable()
                || adviser.settings().routing() == JevClient.GateMode.OFF) {
            return;
        }
        LOG.info("JEV ROUTING bot={} trigger={} bypass={}", this.bot.getName().getString(),
                trigger, trigger == Trigger.COMMAND ? "explicit_input" : "detected_hang");
    }

    /**
     * The model turn itself.
     *
     * <p>Split out of {@link #startDecision()} so the speech gate can hand the turn on without
     * releasing and re-acquiring {@link #thinking}, which would let two turns start at once.
     * The caller must already hold that latch.
     */
    private void beginDecision() {
        // What the bot can see right now, so the next decision can tell progress from churn.
        this.progressFingerprint = this.idleWakeFingerprint();
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
            // Take the message list before the observation is built and mark exactly these read
            // afterwards. Anything arriving during the observation stays unheard, so it is shown
            // next turn instead of being consumed unread.
            int shownMessages = com.melody.mcagent.rt.perception.ChatLog.unheard(this.bot).size();
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
            // new messages instead of repeating the same lines every turn. A turn the stuck
            // watchdog forced is the exception: it is about recovery, and production showed a
            // player's two questions being consumed by exactly such a turn without a spoken answer
            // (the endpoint was hanging, so the bot never got a conversational turn). Those
            // messages stay unheard so the next turn can actually answer them.
            if (keepChatThroughForcedTurn() && this.currentTrigger == Trigger.STUCK
                    && shownMessages > 0) {
                this.actionReports.addLast("kept " + shownMessages + " chat message(s) unheard: this "
                        + "turn was forced by the stuck watchdog, so the next one can answer them");
            } else {
                com.melody.mcagent.rt.perception.ChatLog.markRead(this.bot, shownMessages);
            }
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
            // Restore the operator's standing objective, if one was remembered from before this
            // reload. Done after the identity prefix exists so the pin order stays identity,
            // abilities, objective.
            String rememberedGoal = this.memory().systemValue(STANDING_GOAL_STATE);
            if (rememberedGoal != null && !rememberedGoal.isBlank()) {
                this.setStandingGoal(rememberedGoal);
                LOG.info("Bot {} restored its standing goal from memory: {}",
                        this.bot.getName().getString(), rememberedGoal);
            }
        }
        this.history.add(LlmClient.Message.user(observation));
        this.compact();
        this.decisionGoalVersion = this.standingGoalVersion;
        this.goalCompletedThisTurn = false;

        List<LlmClient.ToolSpec> tools = buildTools();
        List<LlmClient.Message> snapshot = List.copyOf(this.history);

        this.statsPlannerRequests++;
        CompletableFuture
                // Count attempts, not only successful answers with tool calls. Errors, truncation
                // and prose-only completions still consumed a real provider request.
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
                    this.statsPlannerFailed++;
                    LOG.error("Brain task failed for {}", this.bot.getName().getString(), error);
                    this.thinking.set(false);
                    this.cooldownTicks = 100;
                    return null;
                });
    }

    /**
     * Ask Jev whether a chat message needs an answer at all, before a planning turn is spent on it.
     *
     * <p>This is the decision the planning model is worst at and the one a bot gets judged on:
     * whether to say anything. One instruction produced three near-identical acknowledgements,
     * because each chat-triggered turn asked a 12k-token model "should I reply?" and it kept saying
     * yes. A cheap typed choice answers it in a few hundred milliseconds and, when it is confident,
     * the planning turn is not made at all.
     *
     * <p>Safety rules, in order: a message that names the bot and asks a question is never filtered
     * here; a failed, slow or low-confidence answer falls through to the planning model; and in
     * shadow mode the answer is only logged. Losing a player's message is much worse than one extra
     * model turn, so every uncertain path keeps the old behaviour.
     *
     * @return true when the gate has taken responsibility for this decision
     */
    private boolean trySpeechGate() {
        JevClient adviser = this.jevClient;
        if (adviser == null || !adviser.settings().isUsable()) {
            return false;
        }
        JevClient.GateMode mode = adviser.settings().speechGate();
        if (mode == JevClient.GateMode.OFF
                || !com.melody.mcagent.rt.perception.ChatLog.shouldRespondPromptly(this.bot)) {
            return false;
        }
        List<com.melody.mcagent.rt.perception.ChatLog.Heard> unheard =
                com.melody.mcagent.rt.perception.ChatLog.unheard(this.bot);
        if (unheard.isEmpty()) {
            return false;
        }
        // A question put to this bot goes to the planning model, never to the gate: that is what a
        // player is waiting on. "Addressed" now includes a message that reaches only this bot, so an
        // unnamed question from the player talking to it still qualifies - while a question between
        // two players ("渊夜你那个浮空艇放哪了") does not, and is left to the cheap decision.
        //
        // A language request is the other case that must never be silenced. It is a message about how
        // to talk rather than about the work, so a gate looking for "does this need an action" reads
        // it as chatter: production had a player answer the bot's question with "转中文" and the bot
        // said nothing, which reads as being ignored. Deliberately limited to language requests -
        // ordinary task instructions ("继续挖钻石") still go through the gate, where the calibrated
        // silence and repetition rules live.
        for (com.melody.mcagent.rt.perception.ChatLog.Heard heard : unheard) {
            if (heard.directedAtBot()
                    && (looksLikeQuestion(heard.text()) || looksLikeLanguageRequest(heard.text()))) {
                return false;
            }
        }

        // Deterministic case, no model involved: the same instruction, sent again verbatim within a
        // couple of minutes. In production three identical lines produced three "收到..." replies;
        // a player repeating themselves is not waiting for a second acknowledgement. This costs
        // nothing and cannot misread a message that has already been answered.
        com.melody.mcagent.rt.perception.ChatLog.Heard newest = unheard.get(unheard.size() - 1);
        int repeats = this.countRecentRepeats(newest.text());
        if (repeats >= 2) {
            LOG.info("SPEECH GATE bot={} event=SPEECH_GATE decision=STAY_SILENT "
                    + "source=duplicate_instruction repeats={} newest_message={}",
                    this.bot.getName().getString(), repeats, newest.format());
            this.staySilent("the same instruction arrived " + repeats + " times", newest);
            return true;
        }
        this.speechGatePending = true;
        String state = this.speechGateState(unheard);
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("SPEAK", "Answer now: something new needs a reply, or this has not been answered yet");
        candidates.put("STAY_SILENT", "Say nothing: background chatter, or the bot already dealt with this");
        this.statsJevRequests++;
        CompletableFuture
                .supplyAsync(() -> adviser.choose(state, "speech_gate",
                        com.melody.mcagent.rt.llm.JevPrompts.SPEECH_GATE,
                        candidates), executor())
                .thenAccept(choice -> this.bot.server.execute(
                        () -> this.applySpeechGate(adviser, mode, choice, unheard)));
        return true;
    }

    /**
     * How many times this exact line was heard in the last two minutes, including the newest.
     *
     * <p>Comparison ignores punctuation and spacing, the same rule the repetition guard uses for the
     * bot's own lines.
     */
    private int countRecentRepeats(String text) {
        String wanted = com.melody.mcagent.rt.perception.ChatLog.normalise(text);
        if (wanted.isEmpty()) {
            return 0;
        }
        long now = this.bot.level().getGameTime();
        int count = 0;
        for (com.melody.mcagent.rt.perception.ChatLog.Heard heard
                : com.melody.mcagent.rt.perception.ChatLog.recent(this.bot, 12)) {
            if (now - heard.gameTime() > CHAT_REPEAT_WINDOW_TICKS) {
                continue;
            }
            if (com.melody.mcagent.rt.perception.ChatLog.normalise(heard.text()).equals(wanted)) {
                count++;
            }
        }
        return count;
    }

    /**
     * End a chat-triggered decision without spending a model turn, and say why in the log and in the
     * next observation. Shared by the deterministic repeat rule and the gate's own STAY_SILENT.
     */
    private void staySilent(String reason, com.melody.mcagent.rt.perception.ChatLog.Heard newest) {
        com.melody.mcagent.rt.perception.ChatLog.markRead(this.bot);
        this.statsSpeechAvoided++;
        this.actionReports.addLast("you stayed silent on " + newest.format() + " (" + reason
                + "); keep working instead of answering again");
        this.thinking.set(false);
        this.cooldownTicks = GATE_SILENCE_COOLDOWN_TICKS;
    }

    /** Apply a gate answer on the server thread: either skip the turn, or make it. */
    private void applySpeechGate(JevClient adviser, JevClient.GateMode mode, JevClient.Choice choice,
                                 List<com.melody.mcagent.rt.perception.ChatLog.Heard> unheard) {
        this.speechGatePending = false;
        String botName = this.bot.getName().getString();
        String newest = unheard.get(unheard.size() - 1).format();
        if (this.jevClient != adviser || this.paused || this.bot.isRemoved()
                || this.bot.hasDisconnected()) {
            this.thinking.set(false);
            return;
        }
        boolean failed = choice.failed();
        boolean silent = !failed && "STAY_SILENT".equals(choice.choice());
        if (failed) {
            LOG.info("JEV SPEECH_GATE bot={} event=CHAT unavailable={} newest_message={}",
                    botName, choice.error(), newest);
        } else {
            LOG.info("JEV {} bot={} event=SPEECH_GATE choice={} confidence={} probabilities={} "
                    + "newest_message={}",
                    mode == JevClient.GateMode.ACTIVE ? "ACTIVE" : "SHADOW", botName,
                    choice.choice(), String.format(java.util.Locale.ROOT, "%.3f", choice.confidence()),
                    choice.probabilities(), newest);
        }

        if (mode == JevClient.GateMode.ACTIVE && silent) {
            // The choice is honoured, not the confidence. Every question put to this bot was already
            // routed to the planning model before the gate was asked (see trySpeechGate), so what
            // reaches here is a message nobody is waiting on: an instruction already being carried
            // out, a repeat, or background chatter. Discarding a low-confidence STAY_SILENT is how
            // production got its chatter back - the gate answered STAY_SILENT at 0.14 and 0.02, the
            // 0.85 floor threw both answers away, and the 12k-token planner answered anyway
            // ("一组太多了…", "好，正在往下挖，挖到就给你"). Acting on the answer is the whole point
            // of asking before the model runs; the message stays in the chat log, so the next turn
            // still sees it and can act on it. No planning turn, no tokens, no acknowledgement.
            LOG.info("JEV ACTIVE bot={} event=SPEECH_GATE applied=stay_silent confidence={} "
                            + "probabilities={} (the choice decides; a question never reaches the gate)",
                    botName, String.format(java.util.Locale.ROOT, "%.3f", choice.confidence()),
                    choice.probabilities());
            this.staySilent("jev said STAY_SILENT with confidence "
                    + String.format(java.util.Locale.ROOT, "%.2f", choice.confidence()),
                    unheard.get(unheard.size() - 1));
            return;
        }
        // Anything else - SPEAK, an error, a stale answer - keeps the old behaviour exactly: the
        // planning model sees the message and decides for itself.
        this.beginDecision();
    }

    /** The compact state a speak/silent decision needs: what was said, and what the bot answered. */
    private String speechGateState(List<com.melody.mcagent.rt.perception.ChatLog.Heard> unheard) {
        StringBuilder sb = new StringBuilder("Minecraft chat event. bot=")
                .append(this.bot.getName().getString())
                .append("; current_action=").append(this.describeCurrentAction())
                .append("; new_messages=[");
        for (int i = 0; i < unheard.size(); i++) {
            com.melody.mcagent.rt.perception.ChatLog.Heard heard = unheard.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append('<').append(heard.speaker()).append("> ").append(heard.text());
            if (heard.directedAtBot()) {
                sb.append(" (names this bot)");
            }
        }
        sb.append(']');
        // The recent conversation, so a repeat is visible as a repeat rather than a fresh request.
        sb.append("; recent_conversation=[");
        List<com.melody.mcagent.rt.perception.ChatLog.Heard> recent =
                com.melody.mcagent.rt.perception.ChatLog.recent(this.bot, 6);
        for (int i = 0; i < recent.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append('<').append(recent.get(i).speaker()).append("> ")
              .append(recent.get(i).text());
        }
        sb.append(']');
        sb.append("; this_exact_message_heard_times=")
          .append(this.countRecentRepeats(unheard.get(unheard.size() - 1).text()));
        List<com.melody.mcagent.rt.perception.ChatLog.Said> own =
                com.melody.mcagent.rt.perception.ChatLog.recentOwn(this.bot, 4);
        if (!own.isEmpty()) {
            sb.append("; you_already_said=[");
            for (int i = 0; i < own.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append('"').append(own.get(i).text()).append('"');
            }
            sb.append(']');
            long secondsAgo = Math.max(0L,
                    (this.bot.level().getGameTime() - own.get(own.size() - 1).gameTime()) / 20L);
            sb.append("; you_last_spoke_seconds_ago=").append(secondsAgo);
        }
        String goal = this.standingGoal;
        if (goal != null && !goal.isBlank()) {
            sb.append("; standing_goal=").append(goal);
        }
        return sb.toString();
    }

    /**
     * Ask Jev whether the work already under way is worth continuing, before a whole planning turn is
     * spent re-deciding it.
     *
     * <p>The failure this exists for is churn, not stupidity: a bot that has a plan running is asked
     * "what next?" every couple of seconds anyway, and a 12k-token model answering that question
     * tends to invent new work, re-mine blocks that are already gone, or walk back and forth. The
     * cheap typed question - "is there something to carry on with, or does this need thinking about?"
     * - is one a small evaluation model answers well.
     *
     * <p>Safety rules, in order: with nothing queued and nothing running the bot <em>must</em> plan,
     * so the adviser is not asked at all; a failed, slow or low-confidence answer falls through to
     * the planning model; and in shadow mode the answer is only logged. Standing still is the one
     * outcome worse than an unnecessary planning turn, so every uncertain path keeps the old
     * behaviour.
     *
     * @return true when the routing layer has taken responsibility for this decision
     */
    private boolean tryRoutingDecision(Trigger trigger) {
        JevClient adviser = this.jevClient;
        if (adviser == null || !adviser.settings().isUsable()) {
            return false;
        }
        JevClient.GateMode mode = adviser.settings().routing();
        if (mode == JevClient.GateMode.OFF) {
            return false;
        }
        // Only ask when there is something to continue. With an empty queue and nothing running an
        // idle bot has nothing to carry on with, so the only honest answer is "plan" - and a question
        // whose wrong answer is a bot that stands still forever is not worth asking.
        if (this.queue.isEmpty() && !this.isLongActionRunning()) {
            // ...unless the runtime can carry the job itself. This branch is where the money went:
            // measured over three production hours, 412 of 831 decisions were "the bot finished a
            // one-to-three step plan and is idle again", every one of them a ~12k-token planning turn
            // for a bot whose standing goal already said what its job was.
            if (this.startIdleContinuation(trigger)) {
                return true;
            }
            if (this.backoffZeroYieldPlanning(trigger)) {
                return true;
            }
            LOG.info("JEV ROUTING bot={} trigger={} skipped=no_work_to_continue",
                    this.bot.getName().getString(), trigger);
            this.statsIdleSkipped++;
            return false;
        }

        String leaseKey = this.currentRouteLeaseKey();
        long now = this.bot.level().getGameTime();
        if (leaseKey != null && leaseKey.equals(this.routeLeaseKey)
                && now < this.routeLeaseExpiresAt) {
            this.statsLeaseContinuations++;
            this.thinking.set(false);
            this.cooldownTicks = ROUTE_CONTINUE_COOLDOWN_TICKS;
            LOG.debug("JEV ROUTING bot={} trigger={} lease=continue remaining_ticks={} work={}",
                    this.bot.getName().getString(), trigger, this.routeLeaseExpiresAt - now,
                    leaseKey);
            return true;
        }

        this.countersFor(trigger).asked++;
        this.statsJevRequests++;
        String state = this.routingState(trigger);
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("CONTINUE", "Carry on with the work already queued; no new plan is needed");
        candidates.put("ESCALATE_LLM", "Think again before acting: this situation needs a new plan");
        CompletableFuture
                .supplyAsync(() -> adviser.choose(state, "routing",
                        // Shared with tools/jev-replay: a re-scored recording is only comparable when
                        // the wording is identical (see JevPrompts).
                        com.melody.mcagent.rt.llm.JevPrompts.ROUTING,
                        candidates), executor())
                .thenAccept(choice -> this.bot.server.execute(
                        () -> this.applyRoutingDecision(
                                adviser, mode, trigger, choice, state, leaseKey)));
        return true;
    }

    /**
     * Apply a routing answer on the server thread: skip the planning turn, or make it.
     *
     * <p>Runs with the {@link #thinking} latch still held, exactly like the speech gate's apply step,
     * so it either hands the turn on to {@link #beginDecision()} or releases the latch itself. Every
     * exit path does one or the other; a path that did neither would freeze the bot.
     */
    private void applyRoutingDecision(JevClient adviser, JevClient.GateMode mode, Trigger trigger,
                                      JevClient.Choice choice, String state,
                                      @Nullable String askedWorkKey) {
        String botName = this.bot.getName().getString();
        String modeName = mode == JevClient.GateMode.ACTIVE ? "ACTIVE" : "SHADOW";
        // A config reload, a pause or a dead body makes the answer stale: never act on advice about a
        // moment that has already passed, and never let it swallow the decision it was asked for.
        if (this.jevClient != adviser || this.paused || this.bot.isRemoved()
                || this.bot.hasDisconnected()) {
            LOG.info("JEV {} bot={} event=ROUTING trigger={} applied=stale",
                    modeName, botName, trigger);
            this.thinking.set(false);
            return;
        }

        RouteCounts counts = this.countersFor(trigger);
        boolean failed = choice.failed();
        boolean confident = !failed && choice.confidence() >= MIN_ACTIVE_ROUTE_CONFIDENCE;
        boolean keepWorking = !failed && "CONTINUE".equals(choice.choice());
        boolean sameWork = askedWorkKey != null
                && askedWorkKey.equals(this.currentRouteLeaseKey());
        boolean applied = mode == JevClient.GateMode.ACTIVE && keepWorking && confident && sameWork;
        // "false" means the layer deliberately did not skip the turn (a confident ESCALATE_LLM, a
        // shadow-mode answer, or an unknown choice); "low_confidence" is reserved for the case where
        // the answer was not trusted, so the two are never confused when reading the log back.
        String outcome = failed ? "unavailable" : applied ? "true"
                : confident ? "false" : "low_confidence";

        if (failed) {
            counts.failed++;
        }
        // The state goes into the log too: without it a shadow sample cannot be re-scored offline by
        // tools/jev-replay, and the confidence band this layer will be judged on is exactly what that
        // replay produces. Truncated to one line, like the mining-recovery samples.
        LOG.info("JEV {} bot={} event=ROUTING trigger={} choice={} confidence={} probabilities={} "
                + "applied={} state={}{}",
                modeName, botName, trigger, choice.choice(),
                String.format(java.util.Locale.ROOT, "%.3f", choice.confidence()),
                choice.probabilities(), outcome, oneLineState(state),
                failed ? " error=" + firstLine(choice.error()) : "");

        if (applied) {
            counts.continued++;
            this.statsIntercepted++;
            this.routeLeaseKey = askedWorkKey;
            this.routeLeaseExpiresAt = this.bot.level().getGameTime() + ROUTE_LEASE_TICKS;
            // The point of the routing layer: no planning turn, no tokens, and the bot carries on
            // with the job it was already doing. The report is what the next observation shows, so
            // the model (when it is next asked) knows the gap was deliberate.
            this.actionReports.addLast("Jev routing said CONTINUE with confidence "
                    + String.format(java.util.Locale.ROOT, "%.2f", choice.confidence())
                    + "; keep working on what is already queued instead of planning again");
            this.thinking.set(false);
            this.cooldownTicks = ROUTE_CONTINUE_COOLDOWN_TICKS;
            return;
        }

        // ESCALATE_LLM, shadow mode, a low-confidence CONTINUE, an error or an unknown choice: the
        // planning model sees the situation and decides for itself, exactly as before routing existed.
        counts.escalated++;
        this.statsEscalated++;
        this.beginDecision();
    }

    /**
     * Suppress repeated open-ended planning after a bounded mining trip proved this site fruitless.
     * The first turn is immediate; subsequent turns use 60/180/300 second delays. Direct chat and
     * COMMAND/STUCK decisions never enter this IDLE/RESUME guard and therefore remain immediate.
     */
    private boolean backoffZeroYieldPlanning(Trigger trigger) {
        if (this.lastMiningGoalGained != 0 || this.standingGoal == null
                || !looksLikeMiningJob(this.standingGoal)) {
            return false;
        }
        long now = this.bot.level().getGameTime();
        if (now >= this.zeroYieldNextPlannerTick) {
            int index = Math.min(this.zeroYieldBackoffIndex,
                    ZERO_YIELD_BACKOFF_TICKS.length - 1);
            int delay = ZERO_YIELD_BACKOFF_TICKS[index];
            this.zeroYieldBackoffIndex = Math.min(this.zeroYieldBackoffIndex + 1,
                    ZERO_YIELD_BACKOFF_TICKS.length - 1);
            this.zeroYieldNextPlannerTick = now + delay;
            LOG.info("JEV ROUTING bot={} trigger={} zero_yield_replan=allowed "
                            + "next_after_ticks={} goal={}",
                    this.bot.getName().getString(), trigger, delay, this.standingGoal);
            return false;
        }
        long remaining = this.zeroYieldNextPlannerTick - now;
        this.statsBackoffAvoided++;
        this.actionReports.addLast("the last mining trip found none of the requested resource; "
                + "waiting " + Math.max(1L, remaining / 20L)
                + "s before buying another identical planning turn unless new input arrives");
        LOG.info("JEV ROUTING bot={} trigger={} zero_yield_replan=backoff "
                        + "remaining_ticks={} goal={}",
                this.bot.getName().getString(), trigger, remaining, this.standingGoal);
        this.thinking.set(false);
        this.cooldownTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remaining));
        return true;
    }

    /** Stable identity of the exact physical work a CONTINUE answer was about. */
    @Nullable
    private String currentRouteLeaseKey() {
        MineJob mine = this.mineJob;
        if (mine != null) {
            return "mine:" + System.identityHashCode(mine) + ':' + mine.failures.hashCode()
                    + ':' + mine.unreachable;
        }
        CombatJob combat = this.combatJob;
        if (combat != null) {
            return "combat:" + combat.targetId + ':' + combat.blockedPlans;
        }
        if (this.isMoving()) {
            var manager = com.melody.mcagent.rt.Agent.botManager();
            var handle = manager == null ? null : manager.get(this.bot.getName().getString());
            Vec3 target = handle == null ? null : handle.movement().getTarget();
            return target == null ? "move:unknown"
                    : String.format(java.util.Locale.ROOT, "move:%.2f:%.2f:%.2f",
                            target.x, target.y, target.z);
        }
        QueuedCall first = this.queue.peekFirst();
        return first == null ? null : "queue:" + first.call.id() + ':' + this.queue.size();
    }

    private void clearRouteLease() {
        this.routeLeaseKey = null;
        this.routeLeaseExpiresAt = -1L;
    }

    /**
     * Carry an idle bot's standing goal with the runtime instead of buying a planning turn for it.
     *
     * <p>This is the other half of the idle loop. The routing layer deliberately does not ask JEV
     * when nothing is queued and nothing is running, because a cheap "carry on" answer there would be
     * a bot standing still forever - but the answer does not have to come from a model at all when the
     * runtime owns a skill for the job the operator asked for.
     *
     * <p>Bounded on purpose: only an operator-set standing goal ({@code /mcagent goal <bot> ...}) that
     * reads as a mining job counts, the pack must have room, and a trip may only be started once every
     * {@link #IDLE_CONTINUATION_COOLDOWN_TICKS}. Anything else - no goal, a goal about something the
     * runtime has no skill for, a full pack - falls through to the planning model exactly as before,
     * with a line saying which precondition failed. Guessing at a goal the runtime cannot actually
     * carry is how a cheap layer turns into a bot that does nothing all day.
     */
    private boolean startIdleContinuation(Trigger trigger) {
        String goal = this.standingGoal;
        if (goal == null || goal.isBlank() || !miningSkillEnabled() || !this.policy.canBreakBlocks()
                || this.miningGoal != null || !looksLikeMiningJob(goal)) {
            return false;
        }
        String botName = this.bot.getName().getString();
        long now = this.bot.level().getGameTime();
        if (now - this.lastIdleContinuationTick < IDLE_CONTINUATION_COOLDOWN_TICKS) {
            return false;
        }
        if (this.emptyInventorySlots() <= 2) {
            LOG.info("JEV ROUTING bot={} trigger={} idle_continuation=blocked "
                    + "reason=inventory_full empty_slots={} goal={}",
                    botName, trigger, this.emptyInventorySlots(), goal);
            return false;
        }
        if (this.lastMiningGoalGained == 0) {
            LOG.info("JEV ROUTING bot={} trigger={} idle_continuation=blocked "
                    + "reason=last_trip_found_nothing gained={} goal={}",
                    botName, trigger, this.lastMiningGoalGained, goal);
            return false;
        }

        this.lastIdleContinuationTick = now;
        JsonObject args = new JsonObject();
        // No resource is guessed from the goal text: the skill's own priority list (iron, coal,
        // copper, gold, redstone, diamond) is the honest general answer, and it stops after a bounded
        // number of tunnel chunks rather than mining forever.
        args.addProperty("max_tunnel_chunks", 4);
        String result = this.startMiningGoal(args);
        if (isFailure(result)) {
            LOG.info("JEV ROUTING bot={} trigger={} idle_continuation=failed goal={} result={}",
                    botName, trigger, goal, firstLine(result));
            return false;
        }
        this.statsIdleContinuations++;
        this.actionReports.addLast("the runtime carried your standing goal itself instead of planning "
                + "again: " + firstLine(result));
        LOG.info("JEV ROUTING bot={} trigger={} idle_continuation=mining goal={} result={}",
                botName, trigger, goal, firstLine(result));
        this.thinking.set(false);
        this.cooldownTicks = ROUTE_CONTINUE_COOLDOWN_TICKS;
        return true;
    }

    /**
     * Whether a standing goal reads as a mining job.
     *
     * <p>A token test, deliberately narrow and deliberately not clever: the cost of a false positive
     * is a bot that goes mining when the operator asked for something else, and the cost of a false
     * negative is one planning turn - which is exactly what happens today anyway.
     */
    private static boolean looksLikeMiningJob(String goal) {
        String text = goal.toLowerCase(java.util.Locale.ROOT);
        for (String token : new String[] {"挖矿", "采矿", "开采", "下矿", "矿石", "矿脉", "挖点矿",
                "mine", "mining", "ore", "quarry", "dig down"}) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The compact state a continue/think-again decision is asked with.
     *
     * <p>Bounded on purpose: this question is asked on ordinary idle ticks, so it gets a few hundred
     * characters rather than an observation. It has to answer one thing - is there real work in
     * progress that a new plan would interrupt - and everything here is chosen for that.
     */
    private String routingState(Trigger trigger) {
        StringBuilder sb = new StringBuilder("Minecraft bot decision. bot=")
                .append(this.bot.getName().getString())
                .append("; dimension=").append(this.bot.level().dimension().location())
                .append("; trigger=").append(trigger)
                .append("; current_action=").append(this.describeCurrentAction())
                .append("; queue_size=").append(this.queue.size())
                .append("; long_action_running=").append(this.isLongActionRunning());
        if (!this.queue.isEmpty()) {
            sb.append("; queued_in_order=[");
            int shown = 0;
            for (QueuedCall queued : this.queue) {
                if (shown == 4) {
                    sb.append(" | ...");
                    break;
                }
                if (shown++ > 0) {
                    sb.append(" | ");
                }
                sb.append(queued.call.name());
            }
            sb.append(']');
        }
        String goal = this.standingGoal;
        sb.append("; standing_goal=")
          .append(goal == null || goal.isBlank() ? "(none)" : goal);
        long now = this.bot.level().getGameTime();
        sb.append("; seconds_since_last_completed_turn=")
          .append(this.lastCompletedTurnTick < 0 ? "never"
                  : Math.max(0L, (now - this.lastCompletedTurnTick) / 20L));
        List<String> reports = this.recentReports(3);
        if (!reports.isEmpty()) {
            sb.append("; recent_reports=[");
            for (int i = 0; i < reports.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(firstLine(reports.get(i)));
            }
            sb.append(']');
        }
        String state = sb.toString();
        return state.length() <= ROUTING_STATE_MAX_CHARS ? state
                : state.substring(0, ROUTING_STATE_MAX_CHARS - 3) + "...";
    }

    /** The newest action reports, without consuming them the way an observation does. */
    private List<String> recentReports(int limit) {
        int size = this.actionReports.size();
        if (size == 0 || limit <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.min(limit, size));
        int skip = Math.max(0, size - limit);
        int index = 0;
        for (String report : this.actionReports) {
            if (index++ >= skip) {
                out.add(report);
            }
        }
        return out;
    }

    /** This trigger's routing tally, created on first use. Server thread only. */
    private RouteCounts countersFor(Trigger trigger) {
        return this.routingCounts.computeIfAbsent(trigger, key -> new RouteCounts());
    }

    /**
     * Write the cheap-layer counters to the log every few minutes, and start a new window.
     *
     * <p>This exists because "is the cheap layer reducing LLM calls?" could not be answered from the
     * log at all: a call that did not happen leaves no line, so the only visible number was the
     * absolute call rate, which moves with how busy the bot happens to be. One line per window makes
     * the answer a subtraction: calls that were made, versus decisions the routing layer intercepted
     * and decisions the runtime carried itself.
     */
    private void logStatsWindow() {
        long now = this.bot.level().getGameTime();
        if (this.statsWindowStartTick < 0) {
            this.statsWindowStartTick = now;
            return;
        }
        long elapsed = now - this.statsWindowStartTick;
        if (elapsed < STATS_WINDOW_TICKS) {
            return;
        }
        int avoided = this.statsIntercepted + this.statsSpeechAvoided
                + this.statsLeaseContinuations + this.statsIdleContinuations
                + this.statsBackoffAvoided;
        int opportunities = this.statsPlannerRequests + avoided;
        LOG.info("JEV STATS bot={} window={}m planner_requests={} planner_succeeded={} "
                        + "planner_failed={} planner_no_action={} jev_requests={} routed={} "
                        + "lease={} speech_silent={} idle_continuation={} zero_yield_backoff={} "
                        + "idle_escalated={} jev_escalated={} avoided={} avoided_percent={}",
                this.bot.getName().getString(), Math.max(1, elapsed / 1200),
                this.statsPlannerRequests, this.statsPlannerSucceeded, this.statsPlannerFailed,
                this.statsPlannerNoAction, this.statsJevRequests, this.statsIntercepted,
                this.statsLeaseContinuations, this.statsSpeechAvoided,
                this.statsIdleContinuations, this.statsBackoffAvoided, this.statsIdleSkipped,
                this.statsEscalated, avoided,
                opportunities == 0 ? 0 : (100 * avoided / opportunities));
        this.statsPlannerRequests = 0;
        this.statsPlannerSucceeded = 0;
        this.statsPlannerFailed = 0;
        this.statsPlannerNoAction = 0;
        this.statsJevRequests = 0;
        this.statsIntercepted = 0;
        this.statsSpeechAvoided = 0;
        this.statsLeaseContinuations = 0;
        this.statsBackoffAvoided = 0;
        this.statsIdleSkipped = 0;
        this.statsIdleContinuations = 0;
        this.statsEscalated = 0;
        this.statsWindowStartTick = now;
    }

    /** Every trigger's routing tally, including the ones never seen (as zeroes). */
    private Map<String, Object> routingCounters() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Trigger trigger : Trigger.values()) {
            RouteCounts counts = this.routingCounts.get(trigger);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("asked", counts == null ? 0 : counts.asked);
            one.put("continued", counts == null ? 0 : counts.continued);
            one.put("escalated", counts == null ? 0 : counts.escalated);
            one.put("failed", counts == null ? 0 : counts.failed);
            out.put(trigger.name(), one);
        }
        return out;
    }

    /**
     * Whether a line asks this bot to speak a particular language.
     *
     * <p>A token list rather than anything clever, and deliberately only about language: the cost of
     * a false positive is one planning turn, the cost of a false negative is a player who is certain
     * the bot is broken. Production needed exactly this case - a player answered the bot's question
     * with "转中文" and the silence gate read it as chatter.
     */
    private static boolean looksLikeLanguageRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : new String[] {"中文", "汉语", "普通话", "chinese", "mandarin", "中文回",
                "中文说"}) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a line reads as a question in the languages this server's players use. */
    private static boolean looksLikeQuestion(String text) {
        if (text == null) {
            return false;
        }
        if (text.indexOf('?') >= 0 || text.indexOf('？') >= 0) {
            return true;
        }
        for (String marker : new String[] {"吗", "呢", "怎么", "为什么", "如何", "什么", "哪", "几点",
                "多少", "是不是", "能不能", "可不可以"}) {
            if (text.contains(marker)) {
                return true;
            }
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : new String[] {"what ", "why ", "how ", "where ", "when ", "who ",
                "can you", "could you", "do you", "are you", "is it"}) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void handleCompletion(LlmClient.Completion completion) {
        try {
            if (completion.failed()) {
                this.statsPlannerFailed++;
                LOG.warn("Bot {} got an LLM error: {}", this.bot.getName().getString(), completion.error());
                // Back off so a broken endpoint does not hammer the API.
                this.cooldownTicks = 200;
                return;
            }
            this.statsPlannerSucceeded++;

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
                this.statsPlannerNoAction++;
                if (this.standingGoal == null && !this.isLongActionRunning()
                        && this.queue.isEmpty()) {
                    this.unassignedWorkActive = false;
                }
                this.ticksSinceDecision = 0;
                this.lastCompletedTurnTick = this.bot.level().getGameTime();
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
                this.scheduleActionlessRetry("no tool calls");
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

            // A stop while nothing is moving does not change the world. Production had the model
            // emit that one tool every few seconds, bypassing the no-tool backoff indefinitely.
            boolean redundantIdleStop = (this.currentTrigger == Trigger.IDLE
                            || this.currentTrigger == Trigger.RESUME)
                    && completion.toolCalls().size() == 1
                    && "stop".equals(completion.toolCalls().get(0).name())
                    && !this.isLongActionRunning() && this.queue.isEmpty();
            boolean idleReadCandidate = !redundantIdleStop
                    && (this.currentTrigger == Trigger.IDLE
                            || this.currentTrigger == Trigger.RESUME)
                    && completion.toolCalls().size() == 1
                    && isRepeatableIdleRead(completion.toolCalls().get(0).name())
                    && !this.isLongActionRunning() && this.queue.isEmpty();
            long stateBeforeRead = idleReadCandidate ? this.idleReadStateFingerprint() : 0L;
            this.runTurn(completion.toolCalls());
            boolean refusedTurn = this.turnEntirelyRefused;
            boolean repeatedIdleRead = false;
            if (idleReadCandidate && !this.isLongActionRunning() && this.queue.isEmpty()
                    && !this.history.isEmpty()) {
                String result = this.history.get(this.history.size() - 1).content();
                long stateAfterRead = this.idleReadStateFingerprint();
                if (result != null && stateBeforeRead == stateAfterRead) {
                    if (this.idleReadStateFingerprint != stateAfterRead) {
                        this.idleReadSignatures.clear();
                        this.idleReadStateFingerprint = stateAfterRead;
                        this.noActionStreak = 0;
                    }
                    LlmClient.ToolCall call = completion.toolCalls().get(0);
                    repeatedIdleRead = !this.idleReadSignatures.add(
                            call.name() + '\n' + call.arguments() + '\n' + result);
                } else {
                    this.idleReadSignatures.clear();
                    this.noActionStreak = 0;
                }
            }
            if (!redundantIdleStop && !idleReadCandidate) {
                this.noActionStreak = 0;
                this.idleReadSignatures.clear();
            }

            this.turnsCompleted.incrementAndGet();
            this.ticksSinceDecision = 0;
            // How long the bot has been without a fresh plan, which is what a routing decision needs
            // to know; ticksSinceDecision also resets while a long action runs, so it cannot answer it.
            this.lastCompletedTurnTick = this.bot.level().getGameTime();

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
            // After the cooldown above, not before it: noteNoProgress is what sets a wait, and
            // assigning the ordinary cooldown afterwards silently discarded it. Measured in the
            // harness: the refused plan was re-derived every second as if the streak did not exist.
            boolean workInFlight = this.isLongActionRunning() || !this.queue.isEmpty();
            if (refusedTurn) {
                this.noteNoProgress(this.turnRefusalDetail.isEmpty()
                        ? "every action in the turn was refused" : this.turnRefusalDetail);
            } else if (!workInFlight && turnAskedForWork(completion.toolCalls())
                    && this.idleWakeFingerprint() == this.progressFingerprint) {
                // Calls that "succeeded" and left the world exactly as it was are the other half of
                // the same problem: production's stuck repair alternated failing plans with
                // successful observes, so a rule that only counted outright refusals kept resetting.
                this.noteNoProgress("the turn asked for work and changed nothing");
            } else {
                this.clearNoProgress();
            }
            if (this.goalCompletedThisTurn && !this.isLongActionRunning() && this.queue.isEmpty()) {
                // The task is done. A fresh chat, command or inventory change still wakes the bot;
                // a timer alone should not immediately buy another 12k-token planning turn.
                this.noActionStreak = NO_ACTION_RETRY_TICKS.length - 1;
                this.respondPromptly = false;
                this.scheduleActionlessRetry("standing goal completed");
                return;
            }
            boolean onlyReported = completion.toolCalls().stream().anyMatch(
                    call -> "say".equals(call.name()))
                    && completion.toolCalls().stream().allMatch(call -> switch (call.name()) {
                        case "say", "remember", "recall", "forget" -> true;
                        default -> false;
                    });
            if (this.standingGoal == null && !onlyReported) {
                this.unassignedWorkActive = true;
            }
            if (this.standingGoal == null && onlyReported
                    && !this.isLongActionRunning() && this.queue.isEmpty()) {
                this.unassignedWorkActive = false;
                this.respondPromptly = false;
                this.scheduleActionlessRetry("finished responding without a standing goal");
                return;
            }
            if ((redundantIdleStop || repeatedIdleRead)
                    && !this.isLongActionRunning() && this.queue.isEmpty()) {
                this.statsPlannerNoAction++;
                this.scheduleActionlessRetry(redundantIdleStop
                        ? "stop with no movement to stop" : "repeated read with the same result");
                return;
            }
            this.respondPromptly = false;
            this.directlyAddressed = false;
        } catch (Throwable t) {
            LOG.error("Error handling LLM completion for {}", this.bot.getName().getString(), t);
            this.cooldownTicks = 100;
        } finally {
            this.thinking.set(false);
        }
    }

    private static boolean isRepeatableIdleRead(String tool) {
        return switch (tool) {
            case "backpack", "backpack_wear", "find_item", "find_uses", "craftable_now",
                    "recall", "remember" -> true;
            default -> false;
        };
    }

    /** Inventory, position and pinned memory: changes here make a previous read result stale. */
    private long idleReadStateFingerprint() {
        long fingerprint = this.idleWakeFingerprint();
        if (!this.history.isEmpty() && this.history.get(0).content() != null) {
            fingerprint = 31L * fingerprint + this.history.get(0).content().hashCode();
        }
        return fingerprint;
    }

    /** A completed turn that changed nothing should not buy another identical turn immediately. */
    private void scheduleActionlessRetry(String reason) {
        if ((this.standingGoal == null || this.standingGoal.isBlank())
                && !this.isLongActionRunning() && this.queue.isEmpty()) {
            this.unassignedWorkActive = false;
            this.respondPromptly = false;
            this.cooldownTicks = Integer.MAX_VALUE;
            this.noActionWakeFingerprint = this.idleWakeFingerprint();
            this.noActionStreak = Math.max(1, this.noActionStreak + 1);
            LOG.info("Bot {} idle planner is waiting for chat, command or state change ({})",
                    this.bot.getName().getString(), reason);
            return;
        }
        // Chat can be intentionally silent; do not let it increase the idle retry delay.
        // A job still running gets a fast follow-up when it finishes.
        if (this.respondPromptly) {
            this.cooldownTicks = 8;
        } else if (this.isLongActionRunning() || !this.queue.isEmpty()) {
            this.noActionStreak = 0;
            this.cooldownTicks = PLAN_PREFETCH_DELAY_TICKS;
        } else {
            int index = Math.min(this.noActionStreak, NO_ACTION_RETRY_TICKS.length - 1);
            this.noActionWakeFingerprint = this.idleWakeFingerprint();
            this.cooldownTicks = NO_ACTION_RETRY_TICKS[index];
            this.noActionStreak++;
            if (this.noActionStreak >= 2) {
                LOG.info("Bot {} idle planner made no progress {} times ({}); next retry in {} ticks",
                        this.bot.getName().getString(), this.noActionStreak, reason,
                        this.cooldownTicks);
            }
        }
        this.respondPromptly = false;
        this.directlyAddressed = false;
    }

    /** Cheap changes that can make an actionless observation worth planning from again. */
    private long idleWakeFingerprint() {
        long hash = 31L * this.bot.level().dimension().location().hashCode()
                + this.bot.blockPosition().hashCode();
        var inventory = this.bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            hash = 31L * hash + net.minecraft.world.item.ItemStack.hashItemAndComponents(stack);
            hash = 31L * hash + stack.getCount();
            hash = 31L * hash + stack.getDamageValue();
        }
        // Curios' back slot is outside the ordinary player inventory. Include the equipped
        // backpack's item/components so a newly worn or upgraded pack wakes an idle bot too.
        var backpack = com.melody.mcagent.rt.action.Backpacks.equipped(this.bot);
        if (backpack != null) {
            hash = 31L * hash + net.minecraft.world.item.ItemStack.hashItemAndComponents(backpack);
            hash = 31L * hash + backpack.getCount();
        }
        return hash;
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
        this.turnAbandoned = false;

        int executed = 0;
        for (int i = 0; i < calls.size(); i++) {
            if (this.turnAbandoned) {
                // An earlier step in this same turn ended the turn - `interrupt` does exactly that.
                // The steps after it were answered as cancelled by abandonPlan(); running them anyway
                // would be work the model was told would not happen.
                break;
            }
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
                String result;
                this.executingCallIndex = i;
                try {
                    result = this.execute(call);
                } finally {
                    this.executingCallIndex = -1;
                }
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

        this.turnEntirelyRefused = this.turnWasEntirelyRefused(calls);
        this.turnAbandoned = false;
        this.flushResults();
    }

    /**
     * True when every tool call this turn answered with a failure and at least one asked for work.
     *
     * <p>Read-only calls are excluded on purpose. "This server does not have the Sophisticated
     * Backpacks mod" and "no perceived block matches 'iron_ore'" are honest answers about the world,
     * not the world refusing work, and counting them made a bot that was merely asking questions look
     * like one whose plans kept dying - which the routing harness caught: its repeated-read phase
     * bought two turns instead of three, and the phase after it saw no decisions at all.
     */
    private boolean turnWasEntirelyRefused(List<LlmClient.ToolCall> calls) {
        boolean askedForWork = false;
        int answered = 0;
        String detail = "";
        for (int i = 0; i < this.turnResults.length; i++) {
            String result = this.turnResults[i];
            if (result == null || result.startsWith("queued:")) {
                continue;
            }
            answered++;
            if (!isRefusedResult(result)) {
                return false;
            }
            if (detail.isEmpty()) {
                detail = i < calls.size() ? calls.get(i).name() + ": " + firstLine(result)
                        : firstLine(result);
            }
            if (i < calls.size() && !isReadOnlyTool(calls.get(i).name())) {
                askedForWork = true;
            }
        }
        this.turnRefusalDetail = detail;
        return answered > 0 && askedForWork;
    }

    /**
     * Whether a tool result means the world refused the work.
     *
     * <p>{@code isFailure} is not enough on its own: the {@code plan} tool runs its steps inside the
     * call and reports "plan stopped at step 1: failed: ...", which does not start with "failed" and
     * so slipped past the first version of this check - the harness showed the same refused plan
     * being re-derived every second with the streak still at zero.
     */
    private static boolean isRefusedResult(String result) {
        return result != null && (result.startsWith("failed")
                || result.startsWith("plan stopped at step"));
    }

    /** Whether any call this turn was something other than a look at the world. */
    private static boolean turnAskedForWork(List<LlmClient.ToolCall> calls) {
        for (LlmClient.ToolCall call : calls) {
            if (!isReadOnlyTool(call.name())) {
                return true;
            }
        }
        return false;
    }

    /** Tools that only look at the world, so a failure from one is not the world refusing work. */
    private static boolean isReadOnlyTool(String tool) {
        return switch (tool) {
            case "observe", "look_at", "say", "remember", "recall", "forget", "find_item",
                    "find_uses", "find_resource", "craftable_now", "backpack", "backpack_wear",
                    "backpack_sort", "stop", "interrupt", "complete_goal", "chat_command" -> true;
            default -> false;
        };
    }

    /**
     * Run the next queued step, and keep going while the steps are instant.
     *
     * <p>Called once a tick from {@link #tick()}. A run of instant steps drains in a single tick; the
     * moment one of them starts something long, the loop stops and the rest wait for it.
     */
    private void runQueued() {
        boolean ranSomething = false;
        boolean refusedThisDrain = false;
        while (!this.queue.isEmpty() && !this.isLongActionRunning()) {
            QueuedCall next = this.queue.pollFirst();
            ranSomething = true;
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
                this.authorisedTunnelClearance.clear();
                this.actionReports.addLast("the plan stopped because " + next.call.name()
                        + " failed; discarded " + discarded
                        + " dependent step(s) and requested a fresh decision");
                this.cooldownTicks = 0;
                refusedThisDrain = true;
                this.noteNoProgress(next.call.name() + ": " + firstLine(result));
                break;
            }
        }
        if (this.queue.isEmpty()) {
            this.authorisedTunnelClearance.clear();
            if (ranSomething && !refusedThisDrain
                    && this.idleWakeFingerprint() != this.progressFingerprint) {
                // The plan ran to its end and the bot can see that something changed. A plan that
                // completed without changing anything is still a plan that achieved nothing.
                this.clearNoProgress();
            }
        }
    }

    /**
     * Record a decision that achieved nothing, and wait longer each time it happens again.
     *
     * <p>Production, 10:42-10:50: twenty-five planning turns and seventy-one refusals in eight
     * minutes, every plan a `goto` plus a `mine_resource` whose break was refused - the bot walked a
     * few blocks, changed nothing, and bought another 13k-token turn two seconds later. None of the
     * existing guards could see it: the turns had tool calls, so they were not "no action", and the
     * first step of each plan succeeded, so nothing was obviously broken.
     *
     * <p>The first no gets its fresh decision - the world may have changed, and the model may well
     * choose differently. From the second on, the wait climbs 60/200/600/1200/3600/6000 ticks, and
     * once that ladder is spent the bot stops buying turns at all until something it can see changes
     * or a person says something. A bot that keeps spending 13k-token turns on work it cannot do is
     * worse for everybody than one that stops and asks.
     */
    private void noteNoProgress(String what) {
        this.noProgressStreak++;
        if (this.noProgressStreak < NO_PROGRESS_BACKOFF_STREAK) {
            return;
        }
        this.noProgressStreak = 0;
        this.respondPromptly = false;
        this.directlyAddressed = false;
        String why = firstLine(what);
        boolean hasGoal = this.standingGoal != null && !this.standingGoal.isBlank();
        if (!hasGoal) {
            // Nothing to work on anyway: wait for an event rather than for a timer.
            this.unassignedWorkActive = false;
            this.sleepUntilSomethingChanges();
            LOG.info("Bot {} is getting nowhere ({}); waiting for chat, a command or a change",
                    this.bot.getName().getString(), why);
            return;
        }
        if (this.noProgressRung >= NO_PROGRESS_GIVE_UP_RUNG) {
            this.abandonStuckGoal(why);
            return;
        }
        int wait = NO_ACTION_RETRY_TICKS[this.noProgressRung];
        this.noProgressRung++;
        this.cooldownTicks = wait;
        LOG.info("Bot {} made no progress ({}, rung {}/{}); next attempt in {} ticks",
                this.bot.getName().getString(), why, this.noProgressRung,
                NO_ACTION_RETRY_TICKS.length, wait);
    }

    /**
     * Stop buying turns until the bot can see a change, or a person says something.
     *
     * <p>{@code noActionStreak} has to be armed as well as the fingerprint: the tick that ends a long
     * idle wait is gated on {@code noActionStreak > 0 && fingerprint != noActionWakeFingerprint}, so
     * parking without it would be a bot that never notices the diamond it asked for being handed to
     * it - the exact opposite of the "inventory change wakes an idle bot" rule.
     */
    private void sleepUntilSomethingChanges() {
        this.cooldownTicks = Integer.MAX_VALUE;
        this.noActionStreak = Math.max(1, this.noActionStreak + 1);
        this.noActionWakeFingerprint = this.idleWakeFingerprint();
    }

    /**
     * Give up on a standing objective that will not move, cancel its work, and say why.
     *
     * <p>The alternative - which production lived through - is a bot that keeps buying 13k-token
     * turns for a task it cannot do. Dropping the goal is honest and reversible: the objective is
     * gone from memory, the queue and the current action are cancelled, the player is told what was
     * missing, and chat, a command or anything the bot can see changing wakes it again.
     *
     * @param reason what the last attempt failed with, or the model's own explanation
     */
    private String abandonStuckGoal(String reason) {
        String goal = this.standingGoal;
        if (goal == null || goal.isBlank()) {
            return "failed: there is no standing objective to abandon";
        }
        String why = firstLine(reason == null || reason.isBlank() ? "no progress" : reason);
        this.abandonPlan("the standing objective was abandoned as blocked");
        this.abandonCurrentAction();
        this.setStandingGoal(null);
        this.unassignedWorkActive = false;
        this.sleepUntilSomethingChanges();
        this.actionReports.addLast("you gave up on the objective (" + why + ") and told the player; "
                + "do not resume it unless somebody asks");
        LOG.warn("Bot {} abandoned its standing objective after no progress ({}): {}",
                this.bot.getName().getString(), why, goal);
        // A real tool failure is worth quoting verbatim - "no minecraft:diamond in that container"
        // tells the player exactly what to hand over. The runtime's own phrases ("the turn asked for
        // work and changed nothing") are internal wording and read like a bug report in chat.
        String forPlayer = why.contains("failed") ? why : "连着几次都没有任何进展";
        this.announceBlocker("这个目标我做不下去了：" + goal + "（卡在：" + forPlayer
                + "）。我先停下，需要你给材料或者换个说法。");
        return "objective abandoned and the blocker reported to the player";
    }

    /**
     * Say something the player has to know, even when the chat throttles would hold it back.
     *
     * <p>The throttles exist to stop the model narrating its own plans. A bot that has just given up
     * is the one case where silence is worse than an extra line: the player would be left with a bot
     * that stopped for no visible reason.
     */
    private void announceBlocker(String message) {
        String line = tidySpoken(message);
        if (line.isEmpty()) {
            return;
        }
        Actions.Result sent = Actions.chat(this.bot, line);
        if (sent.success()) {
            this.recordSpoken(line, this.bot.level().getGameTime());
            LOG.info("Bot {} said (blocker): {}", this.bot.getName().getString(), line);
        } else {
            LOG.warn("Bot {} could not report a blocker: {}", this.bot.getName().getString(),
                    sent.message());
        }
    }

    /** Something changed, or new input arrived: the no-progress ladder starts over. */
    private void clearNoProgress() {
        this.noProgressStreak = 0;
        this.noProgressRung = 0;
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
        boolean replaceCurrent = args.has("replace_current")
                && args.get("replace_current").isJsonPrimitive()
                && args.get("replace_current").getAsBoolean();
        if (!replaceCurrent && this.queue.size() + steps.size() > MAX_QUEUED_ACTIONS) {
            return "failed: the action queue already has " + this.queue.size()
                    + " step(s); wait for it to drain or set replace_current=true before adding "
                    + steps.size() + " more";
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

        int replaced = 0;
        String replacedAction = null;
        if (replaceCurrent) {
            replaced = this.queue.size();
            if (this.isLongActionRunning()) {
                replacedAction = this.describeCurrentAction();
            }
            this.abandonCurrentAction();
            this.queue.clear();
            this.authorisedTunnelClearance.clear();
            this.abortQueueIfMovementFails = false;
            this.pendingSleep = null;
            this.cooldownTicks = 0;
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
        if (replaceCurrent) {
            result += ". Replaced "
                    + (replacedAction == null ? "the previous schedule" : replacedAction)
                    + " and cancelled " + replaced + " older queued step(s)";
        }
        if (!immediate.isEmpty()) {
            result += ". Started: " + String.join("; ", immediate);
        }
        return result;
    }

    /** Policy gate for nested plan actions; execution checks the same policy again. */
    private boolean planToolAvailable(String tool) {
        return switch (tool) {
            case "mine", "mine_resource", "dig_tunnel", "escape_up" -> this.policy.canBreakBlocks();
            case "place", "use" -> this.policy.canPlaceBlocks();
            // The anvil and the enchanting table are gated with the containers on purpose: both are
            // "use the machine in front of you", and a separate switch would need a new key in the
            // core's config spec, which costs a server restart to deploy.
            case "open_container", "withdraw", "deposit", "repair", "enchant" ->
                    this.policy.canUseContainers();
            // Harvesting is breaking a block and replanting is placing one, so the farm needs both
            // permissions to mean anything; break is the one that gates it.
            case "farm", "build_farm" -> this.policy.canBreakBlocks();
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
        this.turnAbandoned = true;
        if (this.turnResults == null) {
            this.queue.clear();
            this.authorisedTunnelClearance.clear();
            this.abortQueueIfMovementFails = false;
            return;
        }
        // A call that cancels the turn from inside its own execution still owns its result slot:
        // leaving it null here means runTurn() records the real answer instead of the generic
        // "cancelled" placeholder. Every other pending step gets the honest cancellation.
        int running = this.executingCallIndex;
        for (int i = this.nextResultToFlush; i < this.turnResults.length; i++) {
            if (this.turnResults[i] == null && i != running) {
                this.turnResults[i] = "cancelled: " + reason;
            }
        }
        this.queue.clear();
        this.authorisedTunnelClearance.clear();
        this.abortQueueIfMovementFails = false;
        if (running < 0) {
            // Called from outside the dispatcher (death, pause, watchdog): close the turn here.
            this.flushResults();
            this.turnCalls = null;
            this.turnResults = null;
            this.nextResultToFlush = 0;
        }
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

    /**
     * Say once, loudly, when a tool the model is offered cannot appear in a plan.
     *
     * <p>This is the exact shape of a bug that cost hours in production: the backpack tools were
     * offered but absent from {@link #PLANNABLE_TOOLS}, so the model's natural two-step answer to
     * "wear the helmet in your backpack" - take it out, then wear it - was rejected whole with "plan
     * step 1 uses unsupported action 'backpack_take'", and the hat sat in the pack while the bot
     * re-planned the same refused sequence. A silent inconsistency between two lists is what made it
     * invisible, so the inconsistency itself is now reported.
     */
    private void warnAboutUnplannableTools(List<LlmClient.ToolSpec> tools) {
        if (this.unplannableToolsChecked) {
            return;
        }
        this.unplannableToolsChecked = true;
        for (LlmClient.ToolSpec tool : tools) {
            if (!PLANNABLE_TOOLS.contains(tool.name())
                    && !PLANNING_EXCLUSIONS.contains(tool.name())) {
                LOG.warn("Tool '{}' is offered to the model but a plan cannot contain it; add it to "
                        + "PLANNABLE_TOOLS or to PLANNING_EXCLUSIONS", tool.name());
            }
        }
    }

    private List<LlmClient.ToolSpec> buildTools() {
        List<LlmClient.ToolSpec> tools = new ArrayList<>();
        boolean hasStandingGoal = this.standingGoal != null && !this.standingGoal.isBlank();

        tools.add(new LlmClient.ToolSpec("plan",
                "Preferred for every task with two or more known actions. Submit one compact, "
                + "strictly sequential plan instead of many sibling tool calls. Up to "
                + MAX_PLAN_STEPS + " steps are buffered and keep running while the next LLM call "
                + "is still in flight. Put EVERY step you already know into this one plan: a plan "
                + "that ends after one or two steps buys another full planning turn seconds later, "
                + "which is the most expensive mistake you can make here. Chain the whole errand - "
                + "walk, open, take, craft, place, mine the vein, return - and let the runtime stop "
                + "it if the world disagrees. A failed step cancels later dependent steps by "
                + "default; set continue_on_failure only when that particular later work is "
                + "independent. Do not pad a plan with observe/look_at/stop ceremonies or guess "
                + "unknown coordinates. Set replace_current=true when this plan intentionally "
                + "supersedes the action and queue already in progress; do not put interrupt inside "
                + "the steps.",
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
                "Speak in chat. Other players will see this message. Speak only to answer a player, "
                + "to report a task you have finished, or to report a blocker you cannot solve. Never "
                + "narrate progress you are already making, and never announce that you will report "
                + "something later - a promise to speak again is not information."
                + (hasStandingGoal ? " If this is the final report for your standing objective, "
                        + "set goal_complete=true in this same call so it is removed without "
                        + "another model turn." : ""),
                hasStandingGoal
                        ? LlmClient.schema(LlmClient.params(
                                "message", "string: what to say",
                                "goal_complete", "boolean: true only when the standing objective is finished"),
                                List.of("message"))
                        : LlmClient.schema(LlmClient.params(
                                "message", "string: what to say"), List.of("message"))));

        if (hasStandingGoal) {
            tools.add(new LlmClient.ToolSpec("complete_goal",
                    "Clear your standing objective after verifying it is finished, if no final "
                    + "chat report is needed. Use say(goal_complete=true) when reporting instead.",
                    LlmClient.schema(LlmClient.params())));
            tools.add(new LlmClient.ToolSpec("abandon_goal",
                    "Give up on your standing objective and clear it, when it cannot be done: the "
                    + "material or tool it needs is not available, or the world keeps refusing it. "
                    + "Put what is missing in reason; the player is told and the work is cancelled. "
                    + "Do not use this for a task that is merely slow - only for one that is stuck.",
                    LlmClient.schema(LlmClient.params(
                            "reason", "string: what is missing or blocking, for the player"),
                            List.of("reason"))));
        }

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

        tools.add(new LlmClient.ToolSpec("find_resource",
                "Search current light-x-ray perception for concrete blocks matching a resource "
                + "name or registry id. Returns several exact candidates, their distance, and "
                + "whether each is exposed or occluded. Use this instead of guessing coordinates.",
                LlmClient.schema(LlmClient.params(
                        "resource", "string: block/resource name, e.g. copper_ore, oak_log or chest",
                        "radius", "number: optional search radius, 4-48; defaults to current perception radius"),
                        List.of("resource"))));

        if (com.melody.mcagent.rt.action.Backpacks.available()) {
            tools.add(new LlmClient.ToolSpec("backpack",
                    "Look inside your Sophisticated Backpack (worn in the Curios back slot): how full "
                    + "it is, what is in it, and which upgrades are installed. Check it before putting "
                    + "things away or taking them out.",
                    LlmClient.schema(LlmClient.params())));
            tools.add(new LlmClient.ToolSpec("backpack_sort",
                    "Sort the backpack's contents with the backpack mod's own sorting.",
                    LlmClient.schema(LlmClient.params())));
            tools.add(new LlmClient.ToolSpec("backpack_wear",
                    "Wear your Sophisticated Backpack in the Curios back slot. Use this after a player "
                    + "gives you one or after you craft one; it takes no armour or offhand slot. Name "
                    + "the item when you are carrying more than one backpack - a worn backpack is put "
                    + "back in your inventory when it is replaced.",
                    LlmClient.schema(LlmClient.params(
                            "item", "string: which backpack to wear, e.g. "
                                    + "sophisticatedbackpacks:diamond_backpack"))));
            tools.add(new LlmClient.ToolSpec("backpack_put",
                    "Put items from your own inventory into the backpack. Omit item to put away "
                    + "everything that fits.",
                    LlmClient.schema(LlmClient.params(
                            "item", "string: optional item id or name to put away",
                            "count", "number: optional how many"))));
            tools.add(new LlmClient.ToolSpec("backpack_take",
                    "Take items out of the backpack into your own inventory. Omit item to take out "
                    + "whatever is in there.",
                    LlmClient.schema(LlmClient.params(
                            "item", "string: optional item id or name to take out",
                            "count", "number: optional how many"))));
            tools.add(new LlmClient.ToolSpec("backpack_upgrade",
                    "Install an upgrade you are carrying - a stack upgrade you just crafted, or one a "
                    + "player handed you - into the backpack's upgrade slots.",
                    LlmClient.schema(LlmClient.params(
                            "item", "string: optional upgrade item id or name"))));
        }

        if (this.policy.canBreakBlocks() && miningSkillEnabled()) {
            tools.add(new LlmClient.ToolSpec("start_mining",
                    "Start one persistent mining trip and return immediately. Use this for player "
                    + "requests such as 'go mining' or 'bring back 32 iron': the runtime repeatedly "
                    + "finds priority ores, creates/reuses the one safe tunnel, mines, watches "
                    + "inventory/health/hunger, and returns home without another planning call. With "
                    + "mode=branch it digs a fishbone at y instead: a main corridor with side "
                    + "branches, diverting to any ore its scan sees through the rock, still with no "
                    + "further planning calls. Do "
                    + "not also submit per-block mine/dig_tunnel/escape plans for the same trip.",
                    miningGoalSchema()));

            tools.add(new LlmClient.ToolSpec("mine_resource",
                    "Find and mine the nearest currently perceived block matching a resource name. "
                    + "This avoids coordinate guessing and automatically creates a safe physical "
                    + "access route for light-x-ray/occluded targets. vein_radius follows connected "
                    + "blocks of the same exact type; use 0 for one block.",
                    LlmClient.schema(LlmClient.params(
                            "resource", "string: block/resource name or id, e.g. iron_ore or oak_log",
                            "search_radius", "number: optional search radius, default current perception radius",
                            "vein_radius", "number: optional connected-block radius, default 6",
                            "item", "string: optional tool; automatic tool economy still applies"),
                            List.of("resource"))));

            tools.add(new LlmClient.ToolSpec("farm",
                    "Keep a field: harvest every ripe crop in it and replant with the seeds you are "
                    + "carrying, from now on, without asking you each time. Stand in the field (or "
                    + "give its centre) and call this once. Crops you can see are listed under "
                    + "'Crops ready to harvest'. Use interrupt to stop.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: optional X of the field centre",
                            "y", "number: optional Y of the field centre",
                            "z", "number: optional Z of the field centre",
                            "radius", "number: optional field radius in blocks, default 8"),
                            List.of())));

            tools.add(new LlmClient.ToolSpec("build_farm",
                    "Lay out and build a field: choose the ground, till it, put a water source in "
                    + "the middle, light the corners with torches and sow it. Needs a hoe and seeds "
                    + "(and a water bucket and torches for the water and the light). With no "
                    + "coordinates it picks the nearest level, open, unbuilt ground itself. It runs "
                    + "by itself once started; use interrupt to stop.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: optional X of the field centre",
                            "y", "number: optional Y of the field centre (the soil level)",
                            "z", "number: optional Z of the field centre",
                            "radius", "number: optional half-width in blocks, 2-4, default 3"),
                            List.of())));

            tools.add(new LlmClient.ToolSpec("mine",
                    "Break a block, taking the correct amount of time for your tool. Give a radius to "
                    + "fell a whole tree or clear a vein in one go: the job keeps breaking connected "
                    + "blocks of the SAME kind within that many blocks of the one you named, then "
                    + "walks over and picks up the drops. Use radius 0 or omit it for a single block. "
                    + "Player-built blocks are protected and the job refuses them: a building's "
                    + "blocks, its furniture, storage and machines, and the ground under its floor.",
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
                    + "The first call establishes the one persistent entrance allowed near home. "
                    + "That entrance cannot be replaced by a later tool call. Calls made after unloading "
                    + "at storage automatically return through that entrance to the saved working face, "
                    + "instead of opening another hole. Horizontal tunnelling is refused in the home "
                    + "surface band; descend first, then branch underground. One call handles up to 24 "
                    + "blocks with real tool timing "
                    + "and drops. It stops before fluids, falling blocks, gaps, block entities or "
                    + "unbreakable terrain, and it refuses to start inside a player-built structure: "
                    + "walk out of the base and dig in natural ground instead of putting a hole in it.",
                    LlmClient.schema(LlmClient.params(
                            "direction", "string: north, south, east or west",
                            "mode", "string: down or level",
                            "length", "number: tunnel length from 1 to 24",
                            "item", "string: optional fallback tool; ordinary stone automatically uses stone_pickaxe"),
                            List.of("direction", "mode", "length"))));

            tools.add(new LlmClient.ToolSpec("escape_up",
                    "Escape an underground pit by choosing a safe cardinal direction, digging a "
                    + "staircase with real jump clearance and normal mining time, then walking up each tread. "
                    + "One call climbs up to 8 blocks and avoids block entities, falling blocks, "
                    + "fluids and unbreakable terrain. Prefer this after goto says there is no route; "
                    + "use return_to_spawn if it reports no safe staircase. Inside a player-built "
                    + "structure it will not dig through the building: it walks you out through the "
                    + "door or opening instead.",
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

            tools.add(new LlmClient.ToolSpec("repair",
                    "Repair a worn tool, weapon or piece of armour on an anvil, using the material it "
                    + "is made of (a diamond for diamond tools, leather for leather armour) or a "
                    + "second copy of the same item. Do this before an expensive tool breaks: your "
                    + "held item's durability is shown in your state, e.g. "
                    + "'1x Diamond Pickaxe (durability 12/1561)'. Costs experience levels.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X of the anvil", "y", "number: Y", "z", "number: Z",
                            "item", "string: the item to repair, e.g. diamond_pickaxe",
                            "material", "string: optional - what to repair it with; left out, you "
                                    + "use the best thing you are carrying"),
                            List.of("x", "y", "z", "item"))));

            tools.add(new LlmClient.ToolSpec("enchant",
                    "Enchant an item at an enchanting table. Costs experience levels and one lapis "
                    + "lazuli per offer slot. The table shows three offers, cheapest first: pass "
                    + "offer 1, 2 or 3 (1 by default). Which enchantment each one is is decided when "
                    + "it is taken, so choose by price.",
                    LlmClient.schema(LlmClient.params(
                            "x", "number: X of the enchanting table", "y", "number: Y",
                            "z", "number: Z",
                            "item", "string: the item to enchant, e.g. diamond_sword",
                            "offer", "number: 1, 2 or 3 - the cheapest is 1"),
                            List.of("x", "y", "z", "item"))));
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

        this.warnAboutUnplannableTools(tools);
        return tools;
    }

    /** JSON schema for the compact multi-action plan tool. */
    private static JsonObject planSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);

        JsonObject properties = new JsonObject();
        JsonObject replaceCurrent = new JsonObject();
        replaceCurrent.addProperty("type", "boolean");
        replaceCurrent.addProperty("description",
                "default false; true atomically stops the current walk/mine/fight and discards its "
                + "queued tail before starting these replacement steps");
        properties.add("replace_current", replaceCurrent);
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
     * The persistent-trip schema, written out rather than described to {@link LlmClient#schema}.
     *
     * <p>{@code schema()} reads a property's type from the text before the first colon, so a hint
     * written as "array of strings: ..." is emitted as {@code "type": "array of strings"}. That is not
     * a JSON Schema type, and the production provider rejected the whole request with
     * {@code 11129 invalid function call parameters} — every planning call failed until it was
     * corrected, and no dev-server test could see it because the scripted model ignores tool
     * schemas. Arrays therefore have to be built by hand, with an {@code items} type.
     */
    private static JsonObject miningGoalSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.addProperty("additionalProperties", false);

        JsonObject properties = new JsonObject();

        JsonObject primary = new JsonObject();
        primary.addProperty("type", "string");
        primary.addProperty("description",
                "main resource, e.g. iron_ore; optional for a general trip");
        properties.add("primary", primary);

        JsonObject secondary = new JsonObject();
        secondary.addProperty("type", "array");
        secondary.addProperty("description", "other useful ores in priority order");
        JsonObject secondaryItem = new JsonObject();
        secondaryItem.addProperty("type", "string");
        secondary.add("items", secondaryItem);
        properties.add("secondary", secondary);

        JsonObject amount = new JsonObject();
        amount.addProperty("type", "number");
        amount.addProperty("description",
                "optional new primary-resource items to obtain; 0 means a normal trip");
        properties.add("amount", amount);

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        mode.addProperty("description",
                "resource (default): chase visible ores and dive for them; branch: one main corridor "
                + "at y with side branches, scanning for ore through the rock the whole time");
        properties.add("mode", mode);

        JsonObject branchY = new JsonObject();
        branchY.addProperty("type", "number");
        branchY.addProperty("description",
                "branch mode: the level to mine at (never above where you stand), e.g. -54 for "
                + "diamonds at the deepslate layers");
        properties.add("y", branchY);

        JsonObject branchDirection = new JsonObject();
        branchDirection.addProperty("type", "string");
        branchDirection.addProperty("description",
                "branch mode: heading of the main corridor: north, south, east or west");
        properties.add("direction", branchDirection);

        JsonObject branchSpacing = new JsonObject();
        branchSpacing.addProperty("type", "number");
        branchSpacing.addProperty("description",
                "branch mode: main-corridor blocks between branches, default 6");
        properties.add("branch_spacing", branchSpacing);

        JsonObject branchLength = new JsonObject();
        branchLength.addProperty("type", "number");
        branchLength.addProperty("description", "branch mode: blocks per branch, default 16");
        properties.add("branch_length", branchLength);

        JsonObject branchMax = new JsonObject();
        branchMax.addProperty("type", "number");
        branchMax.addProperty("description",
                "branch mode: how many branches before returning home, default 8");
        properties.add("max_branches", branchMax);

        JsonObject chunks = new JsonObject();
        chunks.addProperty("type", "number");
        chunks.addProperty("description",
                "optional 8-block tunnel chunks before returning, default 12");
        properties.add("max_tunnel_chunks", chunks);

        root.add("properties", properties);
        root.add("required", new JsonArray());
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
            case "plan", "say", "complete_goal", "look_at", "eat", "remember", "forget", "recall",
                 "find_item", "find_uses", "craftable_now", "interrupt", "return_to_spawn",
                 "escape_up", "start_mining",
                 // Reading the backpack is a look, not a change: it must not wait behind a walk.
                 "backpack" -> true;
            default -> false;
        };
    }

    /** True while something long-running owns the bot and the next queued step must wait. */
    private boolean isLongActionRunning() {
        return this.mineJob != null || this.combatJob != null || this.farmBuildJob != null
                || this.isMoving();
    }

    /** Begin a real combat exchange rather than performing one isolated swing. */
    private String startCombat(Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive() || target.isRemoved()) {
            return "failed: that target is not alive";
        }
        // Phantoms are never fought. Production: four deaths to them, three more attacks that came
        // back "target is out of reach", and a bow that was used twice in the whole corpus. A
        // phantom dives, bites and climbs again; a bot on foot cannot chase it, and the 30-second
        // combat timeout means the attempt ends with the bot standing in the open being bitten.
        // Sleeping is the actual mechanic - phantoms exist because nobody has slept - so that is
        // what the bot is told to do, and what the reflex below makes it do.
        if (target instanceof net.minecraft.world.entity.monster.Phantom) {
            return "failed: a phantom cannot be fought on foot - it flies out of reach between dives. "
                    + "They only stop coming if you sleep, so go home and get into bed.";
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
            if (!job.finishSent) {
                if (--job.ticksRemaining > 0) {
                    return true;
                }
                Actions.finishBreak(this.bot, job.current, job.face);
                job.finishSent = true;
                job.verifyTicksRemaining = MINE_BREAK_VERIFY_TICKS;
            }
            // Protection mods and server hooks may reject STOP_DESTROY_BLOCK without throwing. Do
            // not count a block merely because the timer elapsed; verify that the original block
            // actually changed. Vanilla may publish a delayed destroy on a following server tick,
            // so give it a bounded grace period before calling the break rejected.
            BlockState after = this.bot.level().getBlockState(job.current);
            if (job.currentType != null && after.is(job.currentType)) {
                if (job.verifyTicksRemaining-- > 0) {
                    return true;
                }
                job.failed("BREAK_REJECTED");
                job.unreachable++;
                LOG.info("Bot {} could not break {}: the original block remained after the timed "
                        + "break (protected or rejected)", this.bot.getName().getString(),
                        job.current.toShortString());
                job.current = null;
                job.currentType = null;
                job.approachTicks = 0;
            } else {
                job.broken++;
                if (job.current.equals(job.origin)) {
                    job.originCompleted = true;
                }
                if (job.clearanceTargets.remove(job.current)) {
                    job.clearanceBroken++;
                }
                this.rememberSelfCleared(job.current);
                // Take this block's drops with it, as it falls. Walking to each one is what used to
                // make a "chop the tree" job take minutes; see Actions.collectBreakDrops.
                job.collected += Actions.collectBreakDrops(this.bot, job.current);
                this.expandMineFrontier(job, job.current);
                job.current = null;
                job.currentType = null;
                job.approachTicks = 0;
            }
            job.finishSent = false;
            job.verifyTicksRemaining = 0;
        }

        // Start the next one.
        while (job.current == null && !job.pending.isEmpty()) {
            BlockPos next = job.pending.pollFirst();
            if (this.bot.level().getBlockState(next).isAir()) {
                if (next.equals(job.origin)) {
                    job.originCompleted = true;
                }
                job.failed("TARGET_BECAME_AIR");
                continue;
            }
            if (!job.allowHomeSurface && this.isProtectedHomeSurface(next)) {
                job.failed("PROTECTED_HOME_SURFACE");
                job.unreachable++;
                LOG.info("Bot {} abandoned mining target {}: {}", this.bot.getName().getString(),
                        next.toShortString(), this.homeSurfaceProtectionReason(next));
                continue;
            }
            // Radius jobs spread to neighbours of the same block without passing through startMine
            // again, so the structure rule is re-checked for every new target in the frontier.
            String refusedTarget = PlayerStructure.protectionReason(
                    this.bot.serverLevel(), this.bot.blockPosition(), next);
            if (refusedTarget != null) {
                job.failed("PROTECTED_STRUCTURE");
                job.unreachable++;
                LOG.info("Bot {} abandoned mining target {}: {}", this.bot.getName().getString(),
                        next.toShortString(), refusedTarget);
                continue;
            }

            // Light x-ray is perception, not reach. If another solid block is between the eyes and
            // the target, remove that blocker first and then reconsider the original target from
            // the changed world. This prevents the old behaviour of mining ore straight through a
            // wall merely because Euclidean distance was under 4.5 blocks.
            BlockPos blocker = Perception.firstBlockingBlock(this.bot, next);
            if (blocker != null) {
                if (!job.allowHomeSurface && this.isProtectedHomeSurface(blocker)) {
                    job.failed("PROTECTED_HOME_SURFACE");
                    job.unreachable++;
                    LOG.info("Bot {} abandoned mining target {}: blocker {} would scar home terrain",
                            this.bot.getName().getString(), next.toShortString(),
                            blocker.toShortString());
                    continue;
                }
                String refusedBlocker = PlayerStructure.protectionReason(
                        this.bot.serverLevel(), this.bot.blockPosition(), blocker);
                if (refusedBlocker != null) {
                    job.failed("PROTECTED_STRUCTURE");
                    job.unreachable++;
                    LOG.info("Bot {} abandoned mining target {}: the block in the way {} is {}",
                            this.bot.getName().getString(), next.toShortString(),
                            blocker.toShortString(), refusedBlocker);
                    continue;
                }
                BlockState blockerState = this.bot.level().getBlockState(blocker);
                boolean unsafe = !blockerState.getFluidState().isEmpty()
                        || blockerState.getBlock() instanceof FallingBlock
                        || this.bot.level().getBlockEntity(blocker) != null
                        || isEscapeHazard(blockerState)
                        || Actions.ticksToBreak(this.bot, blocker) == Integer.MAX_VALUE;
                if (unsafe || !job.clearanceTargets.add(blocker)) {
                    job.failed(unsafe ? "OCCLUDED_UNSAFE" : "OCCLUSION_NOT_CLEARED");
                    job.unreachable++;
                    LOG.info("Bot {} abandoned mining target {}: blocker {} was {}",
                            this.bot.getName().getString(), next.toShortString(),
                            blocker.toShortString(), unsafe ? "unsafe/unbreakable" : "still present after an attempt");
                    continue;
                }
                // Revisit the intended block after its nearest obstruction. The blocker is an
                // ordinary timed mining target, so protection, tool speed and drops still apply.
                job.pending.addFirst(next);
                job.pending.addFirst(blocker);
                continue;
            }
            if (!Actions.canReach(this.bot, next)) {
                // Count time without reaching the interaction envelope even while MovementDriver
                // still owns a target. The previous code counted only failed path *creation*, so a
                // path which existed on paper but made no physical progress kept MineJob alive
                // forever and relied on the minute-long decision watchdog to notice.
                if (++job.approachTicks > MINE_APPROACH_TIMEOUT_TICKS) {
                    this.stopMoving();
                    job.approachTicks = 0;
                    job.unreachable++;
                    job.failed("APPROACH_NO_PROGRESS");
                    continue;
                }
                MovementDriver.Plan approach = this.approachForMining(next);
                if (approach == MovementDriver.Plan.BLOCKED) {
                    job.approachTicks = 0;
                    job.unreachable++;
                    job.failed("NO_REACHABLE_STAND");
                    continue;
                }
                if (approach == MovementDriver.Plan.TOO_FAR) {
                    job.approachTicks = 0;
                    job.unreachable++;
                    job.failed("TARGET_OUTSIDE_ACCESS_RANGE");
                    continue;
                }
                job.pending.addFirst(next);
                return true;
            }

            BlockState nextState = this.bot.level().getBlockState(next);
            // Clearance blocks can require a different tool than the requested resource, and a
            // radius job can reveal targets after the initial tool dispatch. Re-equip every block.
            Actions.equipForMining(this.bot, nextState, "");
            int ticks = Actions.ticksToBreak(this.bot, next);
            if (ticks == Integer.MAX_VALUE) {
                job.failed("UNBREAKABLE_WITH_HELD_TOOL");
                continue; // not breakable with what we are holding
            }
            Actions.Result started = Actions.startBreak(this.bot, next, Actions.faceToward(this.bot, next));
            if (!started.success()) {
                job.failed("BREAK_START_REJECTED");
                continue;
            }
            job.current = next;
            job.currentType = nextState.getBlock();
            job.face = Actions.faceToward(this.bot, next);
            job.ticksRemaining = Math.min(ticks, 20 * 60);
            job.finishSent = false;
            job.verifyTicksRemaining = 0;
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
    private MovementDriver.Plan approachForMining(BlockPos target) {
        var manager = com.melody.mcagent.rt.Agent.botManager();
        var handle = manager == null ? null : manager.get(this.bot.getName().getString());
        if (handle == null) {
            return MovementDriver.Plan.BLOCKED;
        }
        if (handle.movement().hasTarget()) {
            return MovementDriver.Plan.FOUND; // already on our way
        }
        // Search all reachable interaction positions at once. Picking one arbitrary cell below the
        // target repeatedly chose a sealed side even when the opposite side was open.
        return handle.movement().setPathWithinReach(target, 24, Actions.REACH);
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
        if (job.clearanceBroken > 0) {
            report += " (including " + job.clearanceBroken + " access-clearance block(s))";
        }
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
        if (!job.failures.isEmpty()) {
            report += "; mining failures=" + job.failures;
        }
        boolean requestedTargetCleared = job.originCompleted
                || (job.originType != null
                        && !this.bot.level().getBlockState(job.origin).is(job.originType));
        if (requestedTargetCleared) {
            // The requested target itself is gone, so a later block at this coordinate is a new
            // incident. Breaking only an access block must not reset the one-retry guard.
            this.jevRetriedTargets.remove(job.origin);
            this.mineFailureCounts.remove(job.origin);
            this.clearJevSkippedTarget(job);
        }
        this.actionReports.addLast(report);
        this.requestJevMiningRecovery(job, report);
        if (job.originType != null && !requestedTargetCleared
                && job.unreachable > 0 && !this.queue.isEmpty()) {
            int discarded = this.queue.size();
            this.queue.clear();
            this.authorisedTunnelClearance.clear();
            this.abortQueueIfMovementFails = false;
            this.cooldownTicks = 0;
            this.actionReports.addLast("the requested block at " + job.origin.toShortString()
                    + " was not cleared; discarded " + discarded
                    + " dependent step(s) instead of walking into the remaining obstruction");
        }
    }

    /** Ask Jev for one bounded recovery after a mining failure. */
    private void requestJevMiningRecovery(MineJob job, String report) {
        JevClient adviser = this.jevClient;
        if (adviser == null || job.failures.isEmpty()) {
            return;
        }
        // TARGET_BECAME_AIR is a race, not a failure: the block was already gone when the job reached
        // it. It accounted for 232 of the 250 answered production calls and never once produced a
        // physical choice worth acting on - while spending the same rate-limited quota the speech
        // gate needs to answer a player. Only a real failure is worth a decision.
        if (job.failures.keySet().stream().allMatch(code -> "TARGET_BECAME_AIR".equals(code))) {
            return;
        }
        String botName = this.bot.getName().getString();
        if (this.mineFailureCounts.size() > 256) {
            var oldest = this.mineFailureCounts.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        boolean alreadySkipped = this.isJevSkippedTarget(job.origin);
        int failuresForTarget = this.mineFailureCounts.merge(job.origin, 1, Integer::sum);
        // A third failure of the same unchanged block has already had two chances to recover.
        // Production once bought 118 mining-recovery evaluations in five minutes, repeatedly for
        // the same target. A short local skip is safer than asking Jev the identical question again.
        if (failuresForTarget >= 3 || alreadySkipped) {
            this.markJevSkippedTarget(job);
            this.actionReports.addLast("repeated mining failure at " + job.origin.toShortString()
                    + "; temporarily skipped this target and will try another candidate");
            LOG.info("Bot {} skipped repeated mining failure at {} after {} attempt(s) without Jev",
                    botName, job.origin.toShortString(), failuresForTarget);
            return;
        }
        if (this.jevRecoveryRequestedAt >= 0) {
            // The adviser is already deciding how to recover. An overlapping failure must not
            // start another paid request before that answer has even arrived.
            LOG.debug("Bot {} suppressed overlapping Jev mining recovery for {}",
                    botName, job.origin.toShortString());
            return;
        }
        // Read the route once: it is world data on disk, and both the state and the candidate list
        // need to know whether BACKTRACK is legal here.
        MineRoute route = this.loadMineRoute();
        boolean routeHere = route != null && route.dimension().equals(
                this.bot.level().dimension().location().toString());
        String state = this.miningRecoveryState(job, report, failuresForTarget, routeHere);
        Map<String, String> candidates = new LinkedHashMap<>();
        // A rejected break is not an access problem: the block itself refuses to change (protected by
        // another mod, or unbreakable). The access planner cannot help, so retrying is not offered -
        // measured: asked anyway, the model chose RETRY for a protected bookshelf.
        boolean breakWasRejected = job.failures.containsKey("BREAK_REJECTED");
        if (!this.jevRetriedTargets.contains(job.origin)
                && !breakWasRejected
                && !this.bot.level().getBlockState(job.origin).isAir()
                && this.policy.canBreakBlocks()) {
            candidates.put("RETRY_DIFFERENT_ACCESS",
                    "Retry this target once from the changed world using the safe access planner");
        }
        candidates.put("SKIP_TARGET",
                "Mark this exact target temporarily unreachable and continue with another candidate");
        if (routeHere) {
            candidates.put("BACKTRACK",
                    "Return along known mine breadcrumbs toward the established entrance");
        }
        candidates.put("GATHER_PERCEPTION",
                "Inspect local geometry and blockers before selecting another physical action");
        candidates.put("ESCALATE_LLM",
                "The finite recovery choices are insufficient; ask the planning LLM");

        this.jevRecoveryRequestedAt = this.bot.level().getGameTime();
        this.statsJevRequests++;
        CompletableFuture.supplyAsync(() -> adviser.choose(
                state, "mining_recovery",
                com.melody.mcagent.rt.llm.JevPrompts.MINING_RECOVERY,
                candidates), executor()).thenAccept(choice -> {
                    this.jevRecoveryRequestedAt = -1L;
                    if (choice.failed()) {
                        LOG.info("JEV {} bot={} event=MINING_RECOVERY unavailable={}",
                                adviser.settings().shadowMode() ? "SHADOW" : "ACTIVE",
                                botName, choice.error());
                    } else {
                        LOG.info("JEV {} bot={} event=MINING_RECOVERY choice={} confidence={} "
                                + "probabilities={} deterministic_outcome={} state={}",
                                adviser.settings().shadowMode() ? "SHADOW" : "ACTIVE",
                                botName, choice.choice(), String.format("%.3f", choice.confidence()),
                                choice.probabilities(), firstLine(report),
                                oneLineState(state));
                        if (!adviser.settings().shadowMode()) {
                            this.bot.server.execute(() -> this.applyJevMiningRecovery(
                                    adviser, job, choice));
                        }
                    }
                });
    }

    /**
     * Test seam: ask Jev for a recovery as if this target had just failed, without making the mining
     * loop fail for real.
     *
     * <p>Needed because the interesting failure codes cannot be produced in the dev world: there is no
     * protection mod there, so {@code BREAK_REJECTED} is unreachable by injection, and the harness
     * hook ticks after the brain, so a block cannot be kept alive across the break either. The
     * decision path itself - whitelist, one-retry cap, confidence floor, staleness guard, fail-open -
     * is what the harness has to drive, and this is the smallest door that allows it. The real loop
     * still calls {@link #requestJevMiningRecovery} directly.
     */
    public void simulateMiningFailureForTest(BlockPos origin, int radius,
                                             Map<String, Integer> failures, int broken,
                                             int unreachable) {
        MineJob job = new MineJob(origin, this.currentDimension(), radius, null, null,
                this.bot.level().getGameTime(), false);
        job.failures.putAll(failures);
        job.broken = broken;
        job.unreachable = unreachable;
        this.requestJevMiningRecovery(job, "broke " + broken + " block(s); mining failures=" + failures);
    }

    /**
     * Everything a recovery decision needs, and nothing it does not.
     *
     * <p>The first version of this state carried only the failure counts, and the model answered with
     * a coin flip between two options that were both no-ops. What was missing is the evidence a
     * player would use: what the target is, whether it is even still there, whether a retry has
     * already been spent on it, how many times it has failed, what the bot is holding, what it is
     * trying to achieve, what just happened, and what the local space looks like.
     */
    private String miningRecoveryState(MineJob job, String report, int failuresForTarget,
                                       boolean routeHere) {
        BlockState target = this.bot.level().getBlockState(job.origin);
        StringBuilder recent = new StringBuilder();
        int shown = 0;
        for (String line : this.actionReports) {
            if (shown++ >= 2) {
                break;
            }
            if (recent.length() > 0) {
                recent.append(" | ");
            }
            recent.append(line);
        }
        return "Minecraft mining event. bot=" + this.bot.getName().getString()
                + "; dimension=" + this.bot.level().dimension().location()
                + "; position=" + this.bot.blockPosition().toShortString()
                + "; requested_origin=" + job.origin.toShortString()
                + "; target_block=" + (target.isAir() ? "gone(air)" : describeTarget(target))
                + "; radius=" + job.radius
                + "; broken=" + job.broken
                + "; clearance_broken=" + job.clearanceBroken
                + "; unreachable=" + job.unreachable
                + "; failures=" + job.failures
                + "; failures_for_this_exact_target=" + failuresForTarget
                + "; retry_already_used_for_this_target=" + this.jevRetriedTargets.contains(job.origin)
                + "; saved_mine_route_in_this_dimension=" + routeHere
                + "; holding=" + describeHeld()
                + "; goal=" + (this.standingGoal == null || this.standingGoal.isBlank()
                        ? "(none)" : this.standingGoal)
                + "; just_happened=[" + recent + "]"
                + "; result=" + report
                + "; " + com.melody.mcagent.rt.perception.SemanticScene.describe(this.bot)
                        .replace('\n', ' ').replace("  ", " ");
    }

    /** What the bot is holding in its main hand, for a recovery that may be a tool problem. */
    private String describeHeld() {
        var held = this.bot.getMainHandItem();
        return held.isEmpty() ? "empty hand" : held.getHoverName().getString()
                + " x" + held.getCount();
    }

    /** Apply only whitelisted, bounded Jev choices on the server thread. */
    private void applyJevMiningRecovery(JevClient adviser, MineJob failedJob,
                                        JevClient.Choice choice) {
        // A config reload or a newer action makes the asynchronous advice stale. Never let an old
        // response interrupt work selected by a player or the planning model in the meantime.
        if (this.jevClient != adviser || this.paused || this.bot.isRemoved()
                || this.bot.hasDisconnected()
                || !failedJob.dimension.equals(this.currentDimension())) {
            return;
        }
        if ((this.mineJob != null && this.mineJob != failedJob)
                || this.combatJob != null || this.isMoving()
                || !this.queue.isEmpty() || this.thinking.get()) {
            // Say *which* of these superseded the advice. "not_applied=newer_work" alone cannot
            // distinguish "a player or the model already gave the bot work" from "the bot happens to
            // be thinking about its next step", and those two want opposite fixes: the first is the
            // guard working, the second would mean a recovery can never land.
            LOG.info("JEV ACTIVE bot={} event=MINING_RECOVERY not_applied=newer_work "
                    + "newerMineJob={} combat={} moving={} queue={} thinking={} state={}",
                    this.bot.getName().getString(), this.mineJob != null && this.mineJob != failedJob,
                    this.combatJob != null, this.isMoving(), this.queue.size(), this.thinking.get(),
                    this.describeCurrentAction());
            return;
        }
        if (choice.confidence() < MIN_ACTIVE_JEV_CONFIDENCE) {
            this.actionReports.addLast("Jev recovery confidence was only "
                    + String.format(java.util.Locale.ROOT, "%.2f", choice.confidence())
                    + "; asking the planning model instead of taking a physical action");
            this.cooldownTicks = 0;
            return;
        }

        String applied = null;
        switch (choice.choice()) {
            case "RETRY_DIFFERENT_ACCESS" -> {
                if (this.jevRetriedTargets.size() >= 128) {
                    this.jevRetriedTargets.clear();
                }
                if (!this.jevRetriedTargets.add(failedJob.origin)) {
                    this.actionReports.addLast("Jev declined a repeated automatic retry of "
                            + failedJob.origin.toShortString() + "; asking the planning model");
                    this.cooldownTicks = 0;
                    return;
                }
                applied = this.startMine(failedJob.origin, failedJob.radius, "", false);
                this.actionReports.addLast("Jev recovery RETRY_DIFFERENT_ACCESS -> " + applied);
            }
            case "BACKTRACK" -> {
                applied = this.scheduleJevBacktrack();
                this.actionReports.addLast("Jev recovery BACKTRACK -> " + applied);
                if (applied.startsWith("failed:")) {
                    this.cooldownTicks = 0;
                }
            }
            case "SKIP_TARGET" -> {
                this.markJevSkippedTarget(failedJob);
                applied = "marked " + failedJob.origin.toShortString() + " temporarily unreachable";
                this.actionReports.addLast("Jev marked " + failedJob.origin.toShortString()
                        + " temporarily unreachable; choose another perceived resource candidate");
                this.cooldownTicks = 0;
            }
            case "GATHER_PERCEPTION" -> {
                applied = "asked for fresh perception before another physical action";
                this.actionReports.addLast("Jev requested fresh local geometry before another "
                        + "physical action");
                this.cooldownTicks = 0;
            }
            case "ESCALATE_LLM" -> {
                applied = "escalated to the planning model";
                this.actionReports.addLast("Jev escalated this mining recovery to the planning model");
                this.cooldownTicks = 0;
            }
            default -> {
                applied = "unsupported choice; asking the planning model";
                LOG.warn("JEV ACTIVE bot={} returned unknown mining recovery {}; escalating",
                        this.bot.getName().getString(), choice.choice());
                this.actionReports.addLast("Jev returned an unsupported recovery choice; asking the "
                        + "planning model");
                this.cooldownTicks = 0;
            }
        }
        // Only these two choices can start movement/mining. SKIP_TARGET mutates a bounded selector
        // marker and GATHER_PERCEPTION merely asks for another turn; logging either as a physical
        // action made production telemetry overstate Jev's autonomy.
        boolean physical = "RETRY_DIFFERENT_ACCESS".equals(choice.choice())
                || ("BACKTRACK".equals(choice.choice())
                        && applied != null && !applied.startsWith("failed:"));
        LOG.info("JEV ACTIVE bot={} event=MINING_RECOVERY applied={} choice={} confidence={} "
                + "result={}",
                this.bot.getName().getString(), physical, choice.choice(),
                String.format(java.util.Locale.ROOT, "%.2f", choice.confidence()),
                firstLine(applied == null ? "" : applied));
    }

    /** Queue the saved mine breadcrumbs in reverse, ending at the entrance. */
    private String scheduleJevBacktrack() {
        MineRoute route = this.loadMineRoute();
        String dimension = this.bot.level().dimension().location().toString();
        if (route == null || !route.dimension().equals(dimension)) {
            return "failed: no mine route is saved in this dimension";
        }
        List<BlockPos> breadcrumbs = route.waypoints();
        BlockPos here = this.bot.blockPosition();
        int nearest = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < breadcrumbs.size(); i++) {
            double distance = breadcrumbs.get(i).distSqr(here);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = i;
            }
        }
        int queued = 0;
        BlockPos last = here;
        for (int i = nearest; i >= 0; i--) {
            BlockPos point = breadcrumbs.get(i);
            if (point.distSqr(last) < 4.0D) {
                continue;
            }
            this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                    "jev_backtrack_" + (++queued), "goto", positionArgs(point)), -1, true));
            last = point;
        }
        if (route.entrance().distSqr(last) >= 4.0D) {
            this.queue.addLast(new QueuedCall(new LlmClient.ToolCall(
                    "jev_backtrack_" + (++queued), "goto", positionArgs(route.entrance())),
                    -1, true));
        }
        if (queued == 0) {
            return "already at the established mine entrance";
        }
        this.cooldownTicks = PLAN_PREFETCH_DELAY_TICKS;
        return "queued " + queued + " breadcrumb walk(s) toward "
                + route.entrance().toShortString();
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
                    String spoken = this.sayAsTool(string(args, "message", ""));
                    if (args.has("goal_complete") && args.get("goal_complete").isJsonPrimitive()
                            && args.get("goal_complete").getAsBoolean()
                            && !spoken.startsWith("failed:")
                            && (!spoken.startsWith("not sent:")
                                    || spoken.startsWith("not sent: that is word for word"))
                            && !call.id().startsWith("plan_step_")) {
                        String completed = this.completeStandingGoal();
                        return spoken + "; " + completed;
                    }
                    return spoken;

                case "complete_goal":
                    return this.completeStandingGoal();

                case "abandon_goal":
                    return this.abandonStuckGoal(string(args, "reason", ""));

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

                case "start_mining":
                    if (!miningSkillEnabled()) {
                        return "failed: the persistent mining skill is disabled on this server; "
                                + "plan the trip step by step instead";
                    }
                    return this.startMiningGoal(args);

                case "backpack":
                case "backpack_wear":
                case "backpack_sort":
                case "backpack_put":
                case "backpack_take":
                case "backpack_upgrade":
                    return this.backpackTool(name, args);

                case "find_resource":
                    return this.findResource(
                            string(args, "resource", ""),
                            (int) arg(args, "radius", this.observeRadius));

                case "farm":
                    return this.startFarmGoal(args);

                case "build_farm":
                    return this.startFarmBuild(args);

                case "mine_resource": {
                    if (!this.policy.canBreakBlocks()) {
                        return "failed: you are not allowed to break blocks";
                    }
                    String resource = string(args, "resource", "");
                    int searchRadius = (int) arg(args, "search_radius", this.observeRadius);
                    List<Perception.SeenBlock> matches = this.resourceMatches(resource, searchRadius);
                    if (matches.isEmpty()) {
                        return "failed: NO_VISIBLE_RESOURCE - no perceived block matches '" + resource
                                + "' within " + Math.max(4, Math.min(48, searchRadius))
                                + " blocks; move/explore before trying again";
                    }
                    Perception.SeenBlock chosen = matches.get(0);
                    String item = string(args, "item", "");
                    Actions.Result equip = Actions.equipForMining(this.bot, chosen.state(), item);
                    if (equip != null) {
                        return "failed: " + equip.message();
                    }
                    int veinRadius = (int) Math.max(0,
                            Math.min(MAX_MINE_RADIUS, arg(args, "vein_radius", 6)));
                    String outcome = this.startMine(chosen.pos(), veinRadius, item, false);
                    return "selected nearest " + describeTarget(chosen.state()) + " at "
                            + chosen.pos().toShortString() + " from " + matches.size()
                            + " perceived candidate(s): " + outcome;
                }

                case "dig_tunnel":
                    // Gameplay model calls can never replace the durable entrance. Extra arguments
                    // not present in the schema are ignored rather than becoming a terrain bypass.
                    return this.digTunnel(
                            string(args, "direction", ""), string(args, "mode", ""),
                            (int) arg(args, "length", 8), string(args, "item", ""), false);

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
                                    (int) arg(args, "face_z", 0)), direction,
                            decodeMineWaypoints(string(args, "waypoints", "")));
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
                    return this.startMine(pos, radius, string(args, "item", ""),
                            args.has("_access_planned")
                                    && args.get("_access_planned").isJsonPrimitive()
                                    && args.get("_access_planned").getAsBoolean());
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
                    boolean stoppedGoal = this.miningGoal != null;
                    boolean stoppedFarm = this.farmGoal != null || this.farmBuildJob != null;
                    int cancelled = this.queue.size();
                    this.abandonCurrentAction();
                    this.abandonPlan("the bot stopped to do something else");
                    LOG.info("FARMDEBUG bot={} cleared by interrupt_tool", this.bot.getName().getString());
                    this.miningGoal = null;
                    this.farmGoal = null;
                    if (this.farmBuildJob != null) {
                        this.finishFarmBuild("stopped on request");
                    }
                    this.cooldownTicks = 0;

                    // Even with nothing running there is usually something to clear: a plan whose
                    // steps are stuck behind a walk that is going nowhere. Reporting "there was
                    // nothing to stop" while the bot is plainly wedged is both untrue and useless.
                    StringBuilder reply = new StringBuilder();
                    reply.append(wasBusy ? "stopped " + what : "nothing long-running was in progress");
                    if (cancelled > 0) {
                        reply.append("; cancelled ").append(cancelled).append(" queued step(s)");
                    }
                    if (stoppedFarm) {
                        reply.append("; stopped keeping the farm");
                    }
                    if (stoppedGoal) {
                        reply.append("; cancelled the persistent mining goal");
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
                    var inHand = this.bot.getMainHandItem();
                    if (!(inHand.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                        // Production: the model tried to place an anvil while holding a pickaxe, was
                        // told only "nothing happened (the block did not accept that item)", and drew
                        // the wrong conclusion - it wrote itself a note that placing blocks is not
                        // allowed at home and put the anvil back in its backpack, where it still was
                        // hours later, with the repair it was made for never done. Name the item in
                        // the hand and the argument that fixes it.
                        return "failed: you are holding " + inHand.getHoverName().getString()
                                + ", which cannot be placed; name the block you mean with item= "
                                + "(for example item=minecraft:anvil). A block stored in your backpack "
                                + "is taken out for you";
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

                case "repair": {
                    if (!this.policy.canUseContainers()) {
                        return "failed: you are not allowed to use the machines in the world";
                    }
                    return result(Stations.repair(this.bot, blockPos(args),
                            string(args, "item", ""), string(args, "material", "")));
                }

                case "enchant": {
                    if (!this.policy.canUseContainers()) {
                        return "failed: you are not allowed to use the machines in the world";
                    }
                    return result(Stations.enchant(this.bot, blockPos(args),
                            string(args, "item", ""), (int) arg(args, "offer", 1)));
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
        if (!addressed && NARRATION_GUARD_ENABLED && looksLikeProgressNarration(line)) {
            return "not sent: this is routine progress narration. Keep working silently; only report "
                    + "completion, a decision the player must make, or a blocker you cannot solve.";
        }
        if (!addressed && sinceSpoken < UNPROMPTED_CHAT_COOLDOWN && !reportsFinishedWork(line)) {
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
    /**
     * Whether a line reports work that is actually finished.
     *
     * <p>The quiet period exists to stop narration, not to stop the one unprompted message a player
     * is waiting for: "钻石挖到了，一组放你箱子里了" is the whole point of the task. The narration
     * rule above is checked first, so a promise ("挖到就喊你") is still refused before this can
     * exempt it. This only decides whether the quiet period applies.
     */
    private static boolean reportsFinishedWork(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        for (String marker : new String[] {
                "挖到了", "拿到了", "做好了", "放好了", "找到了", "完成了", "搞定了", "建好了",
                "收好了", "送到了", "done", "finished", "gotthe", "foundthe", "isready",
                "allset", "itisin"
        }) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

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
        // A promise to report later carries no information now. This is the shape production kept
        // sending - six lines in two hours, every one of them "挖到钻石就喊你" in different words:
        // "挖到就给你留着", "挖到钻石立刻喊你", "挖到钻石先给你留着". A phrase list alone missed them,
        // so the rule is stated as the intent: announcing a future report is narration.
        for (String promise : new String[] {
                "就喊你", "立刻喊你", "马上喊你", "就告诉你", "就通知你", "先给你留着",
                "就给你留着", "给你留着", "稍等", "等我消息", "回头告诉你",
                "letyouknow", "letuknow", "tellyouwhen", "keepyouposted", "willreport",
                "assoonasifind", "onceifind", "whenifind", "illshout"
        }) {
            if (lower.contains(promise)) {
                return true;
            }
        }
        // Still working on the same thing, no result yet: the other half of the same six lines.
        for (String ongoing : new String[] {
                "继续往下", "接着往下", "往下挖", "往下打", "还在挖", "重新找路", "重开一条",
                "绕个方向", "接着挖", "stilldigging", "stillmining", "keepdigging",
                "backtodigging", "workingonit"
        }) {
            if (lower.contains(ongoing)) {
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

    /** Exact perceived candidates for a semantic resource query, nearest first. */
    private List<Perception.SeenBlock> resourceMatches(String requested, int requestedRadius) {
        int radius = Math.max(4, Math.min(48, requestedRadius));
        return matchesIn(Perception.visibleBlocks(this.bot, radius), requested);
    }

    /**
     * The same matching over an already-taken perception pass.
     *
     * <p>Exposed as a separate step because one scan can serve every priority: the persistent mining
     * skill asks about up to eight resources per decision, and a fresh {@code visibleBlocks} walk for
     * each of them re-raycasts the same world up to eight times in a single tick.
     */
    private List<Perception.SeenBlock> matchesIn(List<Perception.SeenBlock> visible, String requested) {
        String query = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (query.startsWith("minecraft:")) {
            query = query.substring("minecraft:".length());
        }
        if (query.isBlank()) {
            return List.of();
        }
        List<Perception.SeenBlock> matches = new ArrayList<>();
        for (Perception.SeenBlock seen : visible) {
            if (this.isJevSkippedTarget(seen.pos())) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(seen.state().getBlock()).toString().toLowerCase(java.util.Locale.ROOT);
            String path = id.substring(id.indexOf(':') + 1);
            String display = seen.state().getBlock().getName().getString()
                    .toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            if (id.equals(query) || path.equals(query) || id.contains(query)
                    || path.contains(query) || display.contains(query)) {
                matches.add(seen);
            }
        }
        matches.sort(java.util.Comparator.comparingDouble(Perception.SeenBlock::distance));
        return matches;
    }

    private String findResource(String requested, int requestedRadius) {
        List<Perception.SeenBlock> matches = this.resourceMatches(requested, requestedRadius);
        int radius = Math.max(4, Math.min(48, requestedRadius));
        if (matches.isEmpty()) {
            return "No perceived block matches '" + requested + "' within " + radius
                    + " blocks. This is not proof that none exists; move or explore and search again.";
        }
        StringBuilder result = new StringBuilder("Perceived ").append(matches.size())
                .append(" candidate(s) matching '").append(requested).append("':\n");
        for (int i = 0; i < Math.min(8, matches.size()); i++) {
            Perception.SeenBlock seen = matches.get(i);
            int blockers = Perception.blockerCount(
                    this.bot, seen.pos(), Perception.SEE_THROUGH_BLOCKS + 1);
            result.append("  - ").append(describeTarget(seen.state())).append(" at ")
                  .append(seen.pos().toShortString()).append(String.format(" (%.1f blocks, ",
                          seen.distance()))
                  .append(blockers == 0 ? "EXPOSED" : "OCCLUDED by " + blockers + " block(s)")
                  .append(")\n");
        }
        result.append("Use mine_resource with the same resource name; it will choose the nearest "
                + "candidate and solve physical access rather than guessing a goto coordinate.");
        return result.toString();
    }

    /**
     * Start breaking a block, or a connected region of the same kind of block.
     *
     * @param radius 0 for just that block, otherwise how far from it the job may spread
     */
    /**
     * Clearance cells of a mining access route that must not be dug: everything inside the
     * player-built structure around the bot, plus any fixture. The bounded structure scan runs once
     * here so that the access search stays O(1) per node.
     */
    private java.util.function.Predicate<BlockPos> forbiddenForAccess() {
        net.minecraft.server.level.ServerLevel level = this.bot.serverLevel();
        PlayerStructure.Region region = PlayerStructure.detectCached(level, this.bot.blockPosition());
        return cell -> {
            if (this.isProtectedHomeSurface(cell)) {
                return true;
            }
            if (region != null && region.contains(cell)) {
                return true;
            }
            return PlayerStructure.isFixture(level.getBlockState(cell));
        };
    }

    /** The bot's durable notion of home: its bed/anchor, in the dimension where it was set. */
    @Nullable
    private BlockPos homePosition() {
        BlockPos home = this.bot.getRespawnPosition();
        if (home == null || !this.bot.getRespawnDimension().equals(this.bot.level().dimension())) {
            return null;
        }
        return home;
    }

    /** Whether a position lies in the horizontal base neighbourhood protected from surface scars. */
    private boolean isNearHome(BlockPos pos) {
        BlockPos home = this.homePosition();
        if (home == null) {
            return false;
        }
        long dx = (long) pos.getX() - home.getX();
        long dz = (long) pos.getZ() - home.getZ();
        return dx * dx + dz * dz <= (long) HOME_TERRAIN_RADIUS * HOME_TERRAIN_RADIUS;
    }

    /**
     * Protect visible ground and its supporting layers around home.
     *
     * <p>The height map follows already-damaged terrain too, so digging at the bottom of an old pit
     * cannot evade the rule and deepen it. The only exemption is issued internally by dig_tunnel for
     * cells in the one established entrance/corridor.
     */
    private boolean isProtectedHomeSurface(BlockPos pos) {
        if (!this.isNearHome(pos)) {
            return false;
        }
        // A tree, a crop or a tuft of grass is not the ground. Breaking one leaves the terrain exactly
        // as it was, which is the thing this rule exists to protect - and refusing them was expensive.
        // With a 96-block radius around home, every oak and spruce in sight was off limits, so
        // `mine_resource(oak_wood)` failed on the spot, the plan aborted, the queue drained, and the
        // planning model was asked again a couple of seconds later: production spent 25 planning turns
        // and 71 refusals in eight minutes moving a few blocks and changing nothing. The same rule
        // refused the harvest of the bot's own field twenty-one times a second, because that field is
        // twenty blocks from the bed it calls home.
        if (isHarvestableVegetation(this.bot.level().getBlockState(pos))) {
            return false;
        }
        int surface = this.bot.serverLevel().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        return pos.getY() >= surface - HOME_SURFACE_DEPTH;
    }

    /**
     * Whether breaking this block is harvesting rather than excavation.
     *
     * <p>Tags first, so modded logs, leaves, crops, saplings and flowers come along for free; the
     * class checks then cover what vanilla has no tag for (bamboo, sugar cane, cactus, vines, kelp,
     * cocoa, melon and pumpkin stems). Farmland, dirt and stone are deliberately not on this list:
     * they are the ground, and the ground stays protected.
     */
    private static boolean isHarvestableVegetation(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CAVE_VINES)) {
            return true;
        }
        var block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.BushBlock
                || block instanceof net.minecraft.world.level.block.LeavesBlock
                || block instanceof net.minecraft.world.level.block.BambooStalkBlock
                || block instanceof net.minecraft.world.level.block.BambooSaplingBlock
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                || block instanceof net.minecraft.world.level.block.CactusBlock
                || block instanceof net.minecraft.world.level.block.VineBlock
                || block instanceof net.minecraft.world.level.block.GrowingPlantBlock
                || block instanceof net.minecraft.world.level.block.CocoaBlock;
    }

    private String homeSurfaceProtectionReason(BlockPos pos) {
        BlockPos home = this.homePosition();
        return "the surface at " + pos.toShortString() + " is within " + HOME_TERRAIN_RADIUS
                + " blocks of home" + (home == null ? "" : " at " + home.toShortString())
                + "; ordinary mine/mine_resource/escape excavation may not scar or deepen the "
                + "ground here. Use the one established dig_tunnel entrance instead";
    }

    private String currentDimension() {
        return this.bot.level().dimension().location().toString();
    }

    private static String miningTargetKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** True while an unchanged exact block is under a bounded Jev SKIP_TARGET marker. */
    private boolean isJevSkippedTarget(BlockPos pos) {
        String key = miningTargetKey(this.currentDimension(), pos);
        SkippedMiningTarget skipped = this.jevSkippedTargets.get(key);
        if (skipped == null) {
            return false;
        }
        long now = this.bot.level().getGameTime();
        if (now >= skipped.expiresAt()
                || this.bot.level().getBlockState(pos).getBlock() != skipped.block()) {
            this.jevSkippedTargets.remove(key);
            this.mineFailureCounts.remove(pos);
            this.jevRetriedTargets.remove(pos);
            return false;
        }
        return true;
    }

    /** Persist a SKIP_TARGET result so the next resource scan cannot immediately pick it again. */
    private void markJevSkippedTarget(MineJob job) {
        if (!job.dimension.equals(this.currentDimension())) {
            return;
        }
        if (this.jevSkippedTargets.size() >= 256) {
            var oldest = this.jevSkippedTargets.keySet().iterator();
            if (oldest.hasNext()) {
                this.jevSkippedTargets.remove(oldest.next());
            }
        }
        this.jevSkippedTargets.put(miningTargetKey(job.dimension, job.origin),
                new SkippedMiningTarget(this.bot.level().getBlockState(job.origin).getBlock(),
                        this.bot.level().getGameTime() + JEV_SKIP_TARGET_TICKS));
    }

    private void clearJevSkippedTarget(MineJob job) {
        this.jevSkippedTargets.remove(miningTargetKey(job.dimension, job.origin));
    }

    /** Record a block this bot removed, so a queued step naming it is a no-op instead of a failure. */
    private void rememberSelfCleared(BlockPos pos) {
        long now = this.bot.level().getGameTime();
        this.selfCleared.put(pos.immutable(), now);
        // Pruned on write, so the map cannot grow with the world; the window outlives a queued plan.
        this.selfCleared.entrySet().removeIf(entry -> now - entry.getValue() > SELF_CLEARED_TICKS);
    }

    /** Whether this bot itself removed {@code pos} within {@link #SELF_CLEARED_TICKS}. */
    private boolean wasSelfCleared(BlockPos pos) {
        Long when = this.selfCleared.get(pos);
        if (when == null) {
            return false;
        }
        if (this.bot.level().getGameTime() - when > SELF_CLEARED_TICKS) {
            this.selfCleared.remove(pos);
            return false;
        }
        return true;
    }

    private String startMine(BlockPos pos, int radius, String requestedItem,
                             boolean accessAlreadyPlanned) {
        boolean tunnelClearance = this.authorisedTunnelClearance.remove(pos);
        var state = this.bot.level().getBlockState(pos);
        if (state.isAir()) {
            if (SELF_CLEARED_ENABLED && this.wasSelfCleared(pos)) {
                // Not a failure: the intended end state - this block gone - already holds. The tunnel
                // macro and the mining access route both queue the cells they mean to clear, and a
                // MineJob clears its own approach blocker while removing an occluded target, so the
                // two name the same cell sooner or later. Returning "failed:" here aborted the whole
                // plan (every macro step aborts on failure) and bought a planning turn to re-derive
                // the same tunnel: production showed a 12-block tunnel dying after two blocks, every
                // 40 seconds, descending 2.4 blocks a minute.
                long age = this.bot.level().getGameTime() - this.selfCleared.getOrDefault(pos, 0L);
                LOG.info("Bot {} treats {} as already done: this bot removed it {} tick(s) ago as "
                                + "clearance for an earlier step of this plan",
                        this.bot.getName().getString(), pos.toShortString(), age);
                return "already clear: " + pos.toShortString() + " is gone - this bot removed it "
                        + age + " tick(s) ago as clearance for an earlier step, so there is nothing "
                        + "left to break here";
            }
            return "failed: there is no block at " + pos.toShortString()
                    + " - it is open air. Your block list shows only what you can actually see; "
                    + "observe again and use a coordinate from it rather than guessing.";
        }
        if (!tunnelClearance && this.isProtectedHomeSurface(pos)) {
            String refused = this.homeSurfaceProtectionReason(pos);
            LOG.info("Bot {} refused surface excavation at {}: {}", this.bot.getName().getString(),
                    pos.toShortString(), refused);
            return "failed: " + refused;
        }
        if (!tunnelClearance && this.isJevSkippedTarget(pos)) {
            return "failed: " + pos.toShortString() + " is temporarily marked unreachable by "
                    + "mining recovery; choose another perceived resource candidate";
        }
        int ticks = Actions.ticksToBreak(this.bot, pos);
        if (ticks == Integer.MAX_VALUE) {
            return "failed: that block cannot be broken with what you are holding";
        }

        // The hard rule. Every breaking path in this class funnels through here - the mine and
        // mine_resource tools, tunnel steps, escape stairs and access clearance alike - so a
        // player's building, its furniture and the ground under it are refused once, centrally.
        String refused = PlayerStructure.protectionReason(
                this.bot.serverLevel(), this.bot.blockPosition(), pos);
        if (refused != null) {
            LOG.info("Bot {} refused to break {}: {}", this.bot.getName().getString(),
                    pos.toShortString(), refused);
            return "failed: " + refused + ". Player-built structures are protected: no tunnels through "
                    + "a building, no holes in its floor, no mining the ground underneath it. Walk out "
                    + "through its own door or opening, and mine natural ground away from the base.";
        }

        if (!Actions.canReach(this.bot, pos)) {
            MovementDriver.Plan walking = this.approachForMining(pos);
            if (walking == MovementDriver.Plan.FOUND) {
                // Start the job and let the multi-goal path take it to whichever side is actually
                // reachable. MineJob's own timeout verifies physical progress, not just a path on
                // paper.
                this.mineJob = new MineJob(pos, this.currentDimension(), radius,
                        radius > 0 ? state.getBlock() : null, state.getBlock(),
                        this.bot.level().getGameTime(), tunnelClearance);
                return "walking to a reachable mining position for " + pos.toShortString()
                        + " before breaking it (" + describeTarget(state) + ")";
            }

            if (accessAlreadyPlanned) {
                return "failed: NO_ACCESS_ROUTE_AFTER_CLEARANCE - the prepared route still does not "
                        + "put " + pos.toShortString() + " within reach; do not retry the same target";
            }
            MiningAccessPlanner.Route access = MiningAccessPlanner.find(
                    this.bot, pos, MINE_ACCESS_RANGE, this.forbiddenForAccess());
            if (access == null || access.steps().isEmpty()) {
                return "failed: NO_SAFE_MINING_ACCESS - no short two-block-high route can reach "
                        + pos.toShortString() + " without crossing fluids, falling blocks, block "
                        + "entities, hazards, unbreakable terrain or a player-built structure";
            }

            List<QueuedCall> route = new ArrayList<>();
            java.util.Set<BlockPos> scheduledClearance = new java.util.HashSet<>();
            int sequence = 0;
            for (MiningAccessPlanner.Step step : access.steps()) {
                for (BlockPos clear : step.clear()) {
                    if (!scheduledClearance.add(clear)) {
                        continue;
                    }
                    JsonObject mine = positionArgs(clear);
                    mine.addProperty("_access_planned", true);
                    route.add(new QueuedCall(new LlmClient.ToolCall(
                            "mine_access_clear_" + (++sequence), "mine", mine), -1, true));
                }
                JsonObject walk = positionArgs(step.feet());
                route.add(new QueuedCall(new LlmClient.ToolCall(
                        "mine_access_walk_" + (++sequence), "goto", walk), -1, true));
            }
            JsonObject target = positionArgs(pos);
            target.addProperty("radius", radius);
            target.addProperty("_access_planned", true);
            if (requestedItem != null && !requestedItem.isBlank()) {
                target.addProperty("item", requestedItem);
            }
            route.add(new QueuedCall(new LlmClient.ToolCall(
                    "mine_access_target_" + (++sequence), "mine", target), -1, true));
            this.prependOrAppendMacro(route);
            LOG.info("Bot {} planned mining access to {}: {} movement step(s), {} clearance "
                    + "block(s), {} search node(s)", this.bot.getName().getString(),
                    pos.toShortString(), access.steps().size(), access.blocksToClear(),
                    access.expandedNodes());
            return "creating a real access route to occluded/unreachable target "
                    + pos.toShortString() + ": " + access.steps().size() + " movement step(s), "
                    + access.blocksToClear() + " clearance block(s), then the requested mining job";
        }

        BlockPos blocker = Perception.firstBlockingBlock(this.bot, pos);
        if (blocker != null) {
            // In range is not the same as exposed. Let MineJob remove the nearest blocker with
            // normal timing, re-raycast, and repeat until the intended block is genuinely open.
            this.mineJob = new MineJob(pos, this.currentDimension(), radius,
                    radius > 0 ? state.getBlock() : null, state.getBlock(),
                    this.bot.level().getGameTime(), tunnelClearance);
            return "target " + pos.toShortString() + " was perceived through solid terrain; "
                    + "clearing the real approach starting with " + blocker.toShortString()
                    + " before mining " + describeTarget(state);
        }

        Actions.Result started = Actions.startBreak(this.bot, pos, Actions.faceToward(this.bot, pos));
        if (!started.success()) {
            return "failed: " + started.message();
        }
        // Completion is driven from tick() so the break takes real mining time.
        this.mineJob = new MineJob(pos, this.currentDimension(), radius,
                radius > 0 ? state.getBlock() : null, state.getBlock(),
                this.bot.level().getGameTime(), tunnelClearance);
        // This target is already the active break. Leaving the constructor's copy in pending made
        // every successful direct mine rediscover its now-air origin and report a fake
        // TARGET_BECAME_AIR failure.
        this.mineJob.pending.remove(pos);
        this.mineJob.current = pos;
        this.mineJob.currentType = state.getBlock();
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
        this.mineJob = new MineJob(this.bot.blockPosition(), this.currentDimension(),
                Math.max(4, radius), null, null, -1L, false);
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
        MiningGoal goal = this.miningGoal;
        if (goal != null) {
            int gained = this.matchingResourceCount(goal.primary) - goal.startingAmount;
            return (goal.returning ? "returning from" : "running")
                    + " persistent mining goal (priorities=" + goal.priorities
                    + ", primary_progress=" + gained + "/"
                    + (goal.requestedAmount > 0 ? goal.requestedAmount : "trip")
                    + ", tunnel_chunks=" + goal.tunnelChunks + "/" + goal.maxTunnelChunks + ")";
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
    /**
     * A state string flattened onto one line and capped generously.
     *
     * <p>{@link #firstLine} caps at 160 characters, which is right for an error message and wrong for a
     * calibration sample: it cuts the goal, the queued steps and the recent reports off the end, and
     * {@code tools/jev-replay} would then re-score a state the model never saw.
     */
    private static String oneLineState(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace("  ", " ").strip();
        return flat.length() > 700 ? flat.substring(0, 700) + "..." : flat;
    }

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
     * How long an interrupted model call is given to unwind before its class loader is closed.
     *
     * <p>Measured, not guessed: an interrupt aborts a blocking {@code HttpClient.send} in about
     * 8 ms. This is that with two orders of magnitude of headroom, and it is a bound rather than a
     * wait - the common case returns as soon as the last call unwinds.
     */
    private static final long DRAIN_GRACE_MILLIS = 1000L;

    /**
     * Shut the shared executor down, on server stop or on reload.
     *
     * <p>An in-flight model call holds this class loader on its stack, and on a reload that loader
     * is about to be closed: a request allowed to run to its own timeout would finish against
     * classes that no longer exist. Interrupt it instead of waiting the request out.
     *
     * <p>"Instead of waiting" is meant literally, and this method is called on the server thread by
     * the reload. It used to shut down gracefully and wait two seconds <em>before</em> interrupting,
     * and that wait could never succeed: a model call is a blocking HTTP request whose own timeout
     * is tens of seconds, so a call in flight when the reload began was certain to still be in
     * flight two seconds later. The reload therefore parked the server thread for exactly 2.0 s
     * every time a bot was mid-thought - production logged 18 of 51 reloads at 2005 +/- 5 ms, each
     * one followed by "Can't keep up! ... 50 ticks behind" - and then interrupted the request
     * anyway. Interrupting first removes the wait without changing what happens to the request.
     *
     * <p>Positive control: {@code MCAGENT_RELOAD_INTERRUPT=off} restores the old wait-then-interrupt
     * order, which is how {@code MCAGENT_RELOAD_TEST} proves it can see the stall at all.
     */
    public static synchronized void shutdown() {
        ExecutorService current = executor;
        if (current == null) {
            return;
        }
        long start = System.nanoTime();
        if (interruptFirst()) {
            // shutdownNow() also drops calls that were still queued. Starting one only to interrupt
            // it two seconds later is the wait this exists to remove, and its answer would have been
            // handed to bots that no longer exist.
            int dropped = current.shutdownNow().size();
            if (dropped > 0) {
                LOG.info("Dropped {} queued model call(s) that had not started", dropped);
            }
        } else {
            // The positive control: exactly what this method did before, wait included.
            current.shutdown();
            try {
                if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (!current.awaitTermination(DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                // The loader is closed the moment this returns, so a call still running here is
                // about to lose its classes. Say so: the wait this replaced gave up silently after
                // 4 s and the only symptom was a NoClassDefFoundError much later.
                LOG.warn("A model call was still running {} ms after being interrupted; the runtime "
                        + "class loader is being closed under it", DRAIN_GRACE_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Model executor stopped in {} ms", (System.nanoTime() - start) / 1_000_000L);
        // Clear the field so a later server start rebuilds it (see executor()).
        executor = null;
    }

    /** Whether to interrupt in-flight model calls before waiting on them. See {@link #shutdown()}. */
    private static boolean interruptFirst() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_RELOAD_INTERRUPT"));
    }

    /** Exposed for diagnostics: a summary of the conversation so far. */
    public Map<String, Object> debugState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot", this.bot.getName().getString());
        out.put("thinking", this.thinking.get());
        out.put("historyMessages", this.history.size());
        out.put("cooldownTicks", this.cooldownTicks);
        out.put("mining", this.mineJob != null);
        // What the routing guard actually reads, plus the job's own progress counter, so a test (or
        // an operator) can tell "carrying on with work" from "standing still" without guessing.
        out.put("queueSize", this.queue.size());
        out.put("longActionRunning", this.isLongActionRunning());
        out.put("mineBroken", this.mineJob == null ? -1 : this.mineJob.broken);
        out.put("jevSkippedMiningTargets", this.jevSkippedTargets.size());
        out.put("miningGoal", this.miningGoal == null ? "(none)" : this.describeCurrentAction());
        out.put("farmBuild", this.farmBuildJob == null ? "(none)"
                : this.farmBuildJob.site.centre().toShortString()
                        + " tilled=" + this.farmBuildJob.tilled
                        + " planted=" + this.farmBuildJob.planted);
        out.put("farm", this.farmGoal == null ? "(none)"
                : this.farmGoal.centre.toShortString() + " r=" + this.farmGoal.radius
                        + " harvested=" + this.farmGoal.harvested
                        + " replanted=" + this.farmGoal.replanted);
        out.put("lastMiningGoalOutcome",
                this.lastMiningGoalOutcome == null ? "(none)" : this.lastMiningGoalOutcome);
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
        out.put("todoSize", this.todo.size());
        if (!this.todo.isEmpty()) {
            out.put("todoNext", this.todo.peekFirst().description());
        }
        FarmGoal field = this.farmGoal;
        if (field != null) {
            out.put("farmRipePingPending", field.ripePingPending);
            out.put("farmLastHarvestTick", field.lastHarvestTick);
            out.put("farmHarvested", field.harvested);
        }
        BranchMineJob branch = this.branchMine;
        out.put("branchMining", branch != null);
        if (branch != null) {
            out.put("branchTargetY", branch.targetY);
            out.put("branchBranchesDug", branch.branchesDug);
            out.put("branchMainBlocks", branch.mainBlocksDug);
            out.put("branchOreJobs", branch.resourcesStarted);
            out.put("branchBridges", this.branchBridges);
            out.put("branchInterruptsAsked", branch.interruptsAsked);
            out.put("branchInterruptsApplied", branch.interruptsApplied);
            out.put("branchLastInterrupt", branch.lastInterrupt);
            out.put("branchReturning", branch.returning);
        }
        out.put("paused", this.paused);
        // What the survival reflex is doing: whether the bot has broken off work to recover, and
        // what it last said about it. Read by the danger harness and by anyone debugging a bot that
        // "suddenly stopped".
        out.put("retreating", this.retreating);
        out.put("lastReflex", this.lastReflexReport.isEmpty() ? "(none)" : this.lastReflexReport);
        // What the cheap layer is doing: which trigger the next decision will be labelled with, and
        // per-trigger asked/continued/escalated/failed tallies. Read by /mcagent status and by the
        // gated routing test, which asserts on the counters rather than on log text.
        out.put("pendingTrigger", this.pendingTrigger.name());
        out.put("jevRouting", this.routingCounters());
        out.put("routeLeaseActive", this.routeLeaseKey != null
                && this.bot.level().getGameTime() < this.routeLeaseExpiresAt);
        out.put("statsPlannerRequests", this.statsPlannerRequests);
        out.put("noActionStreak", this.noActionStreak);
        out.put("noProgressStreak", this.noProgressStreak);
        out.put("noProgressRung", this.noProgressRung);
        out.put("statsJevRequests", this.statsJevRequests);
        out.put("statsLeaseContinuations", this.statsLeaseContinuations);
        out.put("statsBackoffAvoided", this.statsBackoffAvoided);
        return out;
    }
}
