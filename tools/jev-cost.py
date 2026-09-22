#!/usr/bin/env python3
"""What Jev costs per day, measured rather than guessed.

Method, in order:

1. Pull the real `state=...` strings out of the production log, spread across the whole file.
2. Re-send a sample of them to the gateway exactly as `JevClient.choose` builds the body, and read
   `usage.inputTokens` back. That is the provider's own count, not a character estimate.
3. Fit `tokens = a + b * bodyChars` per event type, then apply the fit to *every* call in the log to
   get today's real token total.
4. Project 24 h at the observed rate and price it at the list price from the gateway's
   `/v1/models` (`pricing.input`, in dollars per token; Jev's output is free).

Only calls that actually left the machine are counted: a log line with neither `choice=` nor
`unavailable=` is a guard skip, and the guard skipping is the whole point of the design - it costs
nothing.

Costs about $0.001 of credit per run (the sample calls). Usage:

    tools/jev-cost.py [--samples 12] [--server <path>]
"""

import argparse
import json
import statistics
import subprocess
import time
import sys
from pathlib import Path

DEFAULT_SERVER = "/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
GATEWAY = "https://ai-gateway.vercel.sh/v1/evaluate"

# Copied from rt/llm/JevPrompts.java and the AgentBrain.choose call sites. The state dominates the
# token count, but the fixed overhead is real (~870 chars for routing, ~1320 for mining) - if the
# wording changes, the printed overhead moves and this table is stale.
SPEECH_GATE = (
    "You decide whether this Minecraft bot speaks in chat now. Another acknowledgement of an "
    "instruction it is already carrying out is worse than silence. SPEAK when: the player "
    "asked something, corrected the bot, gave an instruction that current_action does not "
    "already cover, or is waiting for a result or a blocker. STAY_SILENT when: the same line "
    "already arrived before, the message only repeats what the bot is already doing, or it is "
    "chatter needing no answer."
)
MINING_RECOVERY = (
    "A Minecraft bot's mining job just failed. Choose the best currently legal recovery. "
    "RETRY_DIFFERENT_ACCESS only when the target is still present and no retry has been "
    "spent on it yet - the world has changed since the first attempt, so the safe access "
    "planner may find another way in. A target whose break was rejected, or that is "
    "protected, can never be retried into working: choose SKIP_TARGET for it. "
    "BACKTRACK only when a saved mine route exists in "
    "this dimension. SKIP_TARGET when this exact target is not worth more attempts and "
    "another candidate is cheaper. GATHER_PERCEPTION when the failure looks like stale "
    "or wrong geometry rather than a genuinely unreachable block. ESCALATE_LLM when the "
    "situation is not covered by these options."
)
ROUTING = (
    "Decide whether this Minecraft bot needs a new plan right now. CONTINUE means: keep "
    "executing, no planning turn. ESCALATE_LLM means: stop and think, because the current "
    "work cannot get the bot any further. Rules, in order: (1) if long_action_running is "
    "true or queue_size is greater than zero, and recent_reports shows no failure or "
    "blockage, choose CONTINUE - the bot is mid-task and a new plan would duplicate what it "
    "is already doing. (2) if the bot is idle with nothing queued, choose ESCALATE_LLM. "
    "(3) if the last action failed, was blocked, or the inventory is full, choose "
    "ESCALATE_LLM."
)

EVENTS = {
    "routing": ("ROUTING", "routing", ROUTING, {
        "CONTINUE": "Carry on with the work already queued; no new plan is needed",
        "ESCALATE_LLM": "Think again before acting: this situation needs a new plan",
    }),
    "mining": ("MINING_RECOVERY", "mining_recovery", MINING_RECOVERY, {
        "RETRY_DIFFERENT_ACCESS": "Find a different approach to the same target and try again",
        "SKIP_TARGET": "Mark this exact target unreachable for now and move to another candidate",
        "BACKTRACK": "Return along known mine breadcrumbs toward the established entrance",
        "GATHER_PERCEPTION": "Inspect local geometry and blockers before selecting another physical action",
        "ESCALATE_LLM": "The finite recovery choices are insufficient; ask the planning LLM",
    }),
    "speech": ("SPEECH_GATE", "speech_gate", SPEECH_GATE, {
        "SPEAK": "Answer now: something new needs a reply, or this has not been answered yet",
        "STAY_SILENT": "Say nothing: background chatter, or the bot already dealt with this",
    }),
}


def api_key(server: Path) -> str:
    for line in (server / "config/mcagent-jev.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("apiKey="):
            return line.split("=", 1)[1].strip()
    sys.exit("no apiKey= in config/mcagent-jev.properties")


def list_price(key: str, model: str) -> float:
    out = subprocess.run(
        ["curl", "-s", "-H", f"Authorization: Bearer {key}",
         "https://ai-gateway.vercel.sh/v1/models"],
        capture_output=True, text=True, check=True).stdout
    for m in json.loads(out).get("data", []):
        if m.get("id") == model:
            return float(m["pricing"]["input"])
    sys.exit(f"{model} not in the gateway's model list")


def body_for(name: str, state: str) -> str:
    _, qid, instructions, criteria = EVENTS[name]
    return json.dumps({"model": "typesafe-ai/jev", "state": state,
                       "questions": {qid: {"type": "choice", "instructions": instructions,
                                           "criteria": criteria}}})


def measure(key: str, name: str, states: list[str]) -> list[tuple[int, int]]:
    """One real call per state, retried: the gateway answers 429 under a burst and a dropped
    sample would quietly shrink the fit."""
    pairs = []
    failures = 0
    for state in states:
        Path("/tmp/jev-cost-q.json").write_text(body_for(name, state), encoding="utf-8")
        time.sleep(0.35)  # production shares this gateway; do not burst into its rate limit
        for attempt in range(3):
            out = subprocess.run(
                ["curl", "-s", "--max-time", "20", "-H", f"Authorization: Bearer {key}",
                 "-H", "Content-Type: application/json", "-X", "POST", GATEWAY,
                 "-d", "@/tmp/jev-cost-q.json"], capture_output=True, text=True).stdout
            try:
                usage = json.loads(out)["usage"]
            except Exception:
                time.sleep(1.5 * (attempt + 1))
                continue
            pairs.append((len(body_for(name, state)), usage["inputTokens"]))
            break
        else:
            failures += 1
    if failures:
        print(f"{name}: {failures} sample call(s) did not answer and were left out")
    return pairs


def fit(pairs: list[tuple[int, int]]) -> tuple[float, float, float]:
    n = len(pairs)
    if n < 2:
        # One usable point: no slope to fit, so price it proportionally (no intercept).
        chars, tokens = pairs[0]
        return 0.0, tokens / chars, 1.0
    sx = sum(c for c, _ in pairs)
    sy = sum(t for _, t in pairs)
    sxx = sum(c * c for c, _ in pairs)
    sxy = sum(c * t for c, t in pairs)
    b = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    a = (sy - b * sx) / n
    mean = sy / n
    ss_res = sum((t - (a + b * c)) ** 2 for c, t in pairs)
    ss_tot = sum((t - mean) ** 2 for _, t in pairs)
    return a, b, (1 - ss_res / ss_tot) if ss_tot else 1.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=12, help="real calls per event type")
    ap.add_argument("--server", default=DEFAULT_SERVER)
    args = ap.parse_args()

    server = Path(args.server)
    key = api_key(server)
    price = list_price(key, "typesafe-ai/jev")
    lines = (server / "logs/latest.log").read_text(encoding="utf-8", errors="replace").splitlines()

    print(f"list price: ${price:.9f}/input token = ${price * 1e6:.3f}/M, output free")
    print(f"log: {len(lines)} lines, {server}/logs/latest.log\n")

    total_tokens = 0.0
    total_calls = 0
    for name, (event, _, _, _) in EVENTS.items():
        # Every call that left the machine: a guard skip logs neither of these.
        calls = [l for l in lines
                 if f"event={event}" in l and ("choice=" in l or "unavailable=" in l)]
        states = [l.split(" state=", 1)[1] for l in calls if " state=" in l]
        note = ""
        if not states:
            # The speech gate does not log its state, so a representative one stands in.
            states = ["x" * 200]
            note = "  (no state logged; 200-char placeholder)"
        sample = [states[i] for i in range(0, len(states), max(1, len(states) // args.samples))][:args.samples]
        pairs = measure(key, name, sample)
        if not pairs:
            print(f"{name}: no usable sample")
            continue
        a, b, r2 = fit(pairs)
        overhead = len(body_for(name, ""))
        # A call whose line carries no state is priced at the median length of those that do.
        median = statistics.median(len(s) for s in states)
        tokens = sum(a + b * ((len(l.split(" state=", 1)[1]) if " state=" in l else median) + overhead)
                     for l in calls)
        total_tokens += tokens
        total_calls += len(calls)
        print(f"{name:<8} calls={len(calls):6d}  sample n={len(pairs):3d}  "
              f"tokens/call={statistics.mean(t for _, t in pairs):6.0f}  "
              f"fit: {a:.0f} + {b:.4f}*chars (R2={r2:.3f})  overhead={overhead} chars{note}")
        print(f"{'':8} -> {tokens:12,.0f} input tokens  ${tokens * price:.4f}")

    hours = observed_hours(lines)
    per_day = 24 / hours
    day_tokens = total_tokens * per_day
    print(f"\nwindow: {hours:.2f} h   calls={total_calls}   tokens={total_tokens:,.0f}   "
          f"${total_tokens * price:.4f}")
    print(f"24 h:   {day_tokens:,.0f} tokens  ->  ${day_tokens * price:.3f}/day  "
          f"({day_tokens * price * 30:.2f}/month)")
    if total_calls:
        print(f"per call: {total_tokens / total_calls:.0f} tokens  ${total_tokens / total_calls * price:.7f}")


def observed_hours(lines: list[str]) -> float:
    """Span of the log in hours, so the projection uses the window actually measured."""
    import datetime
    import re
    stamps = []
    for line in lines:
        m = re.match(r"\[(\d+)(\w{3})(\d{4}) ([\d:.]+)\]", line)
        if m:
            stamps.append(datetime.datetime.strptime(
                f"{m.group(1)}{m.group(2)}{m.group(3)} {m.group(4).split('.')[0]}",
                "%d%b%Y %H:%M:%S"))
    if len(stamps) < 2:
        return 24.0
    return max(0.1, (stamps[-1] - stamps[0]).total_seconds() / 3600)


if __name__ == "__main__":
    main()
