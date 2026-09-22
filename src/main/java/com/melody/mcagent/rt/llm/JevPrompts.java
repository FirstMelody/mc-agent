package com.melody.mcagent.rt.llm;

/**
 * The instructions Jev is asked with, in one place.
 *
 * <p>Not a style preference: an offline replay that re-scores recorded states with a *different*
 * wording measures nothing. That happened - the same state answered RETRY_DIFFERENT_ACCESS at 0.73
 * under the production wording and GATHER_PERCEPTION at 0.22 under a paraphrase - so the wording is
 * shared by the runtime and by {@code tools/jev-replay} rather than copied.
 *
 * <p>Each string is a contract, not a hint: it names the candidates, says when each is legal, and
 * states the cost asymmetry the threshold encodes.
 */
public final class JevPrompts {

    private JevPrompts() {
    }

    /** Chat: should the bot speak at all? Calibrated floor 0.85 (silence answers 0.89-0.99). */
    public static final String SPEECH_GATE =
            "You decide whether this Minecraft bot speaks in chat now. Another acknowledgement of an "
            + "instruction it is already carrying out is worse than silence. SPEAK when: the player "
            + "asked something, corrected the bot, gave an instruction that current_action does not "
            + "already cover, or is waiting for a result or a blocker. STAY_SILENT when: the same line "
            + "already arrived before, the message only repeats what the bot is already doing, or it is "
            + "chatter needing no answer.";

    /** Mining failure: which bounded recovery to take. Calibrated floor 0.6. */
    public static final String MINING_RECOVERY =
            "A Minecraft bot's mining job just failed. Choose the best currently legal recovery. "
            + "RETRY_DIFFERENT_ACCESS only when the target is still present and no retry has been "
            + "spent on it yet - the world has changed since the first attempt, so the safe access "
            + "planner may find another way in. A target whose break was rejected, or that is "
            + "protected, can never be retried into working: choose SKIP_TARGET for it. "
            + "BACKTRACK only when a saved mine route exists in "
            + "this dimension. SKIP_TARGET when this exact target is not worth more attempts and "
            + "another candidate is cheaper. GATHER_PERCEPTION when the failure looks like stale "
            + "or wrong geometry rather than a genuinely unreachable block. ESCALATE_LLM when the "
            + "situation is not covered by these options.";

    /**
     * Whether an ordinary decision tick needs the planning model at all.
     *
     * <p>This is the cheapest decision in the system and the one that saves the most: a 12k-token
     * planning turn is the default answer to "the cooldown expired", and most of the time the bot
     * already has work queued that a model turn would only re-derive.
     *
     * <p>Wording calibrated, not guessed. The first version - "choose CONTINUE when current_action
     * already covers what the goal needs" - made the model answer ESCALATE_LLM for everything
     * (0.07-0.18 on the carry-on cases), so {@code routing=active} would have saved nothing at all.
     * This version states the rule as an ordered procedure over the state fields, and separates the
     * two answers cleanly: CONTINUE 0.95-1.00 on three in-flight cases, ESCALATE_LLM 1.00 on the
     * three that need a new plan (idle, last action failed, inventory full).
     */
    public static final String ROUTING =
            "Decide whether this Minecraft bot needs a new plan right now. CONTINUE means: keep "
            + "executing, no planning turn. ESCALATE_LLM means: stop and think, because the current "
            + "work cannot get the bot any further. Rules, in order: (1) if long_action_running is "
            + "true or queue_size is greater than zero, and recent_reports shows no failure or "
            + "blockage, choose CONTINUE - the bot is mid-task and a new plan would duplicate what it "
            + "is already doing. (2) if the bot is idle with nothing queued, choose ESCALATE_LLM. "
            + "(3) if the last action failed, was blocked, or the inventory is full, choose "
            + "ESCALATE_LLM.";
}
