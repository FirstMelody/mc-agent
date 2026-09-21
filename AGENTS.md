# AGENTS.md — working on mc-agent

Server-side LLM-driven virtual players for a NeoForge 21.1.248 / Minecraft 1.21.1 dedicated server.
The bots are real `ServerPlayer` entities with no client: they move by vanilla physics, perceive by
server-side raycasts, and act through a whitelisted tool API driven by an OpenAI-compatible model.

Read this before touching anything. Most of it is here because getting it wrong cost real debugging
time.

---

## 1. Architecture: core + runtime (hot-reloadable)

Two artifacts, and the split is load-bearing:

| | package | artifact | goes in |
|---|---|---|---|
| **core** | `com.melody.mcagent.*` | `mcagent-0.1.0+neoforge.1.21.1.jar` | `mods/` |
| **runtime** | `com.melody.mcagent.rt.*` | `mcagent-runtime.jar` | `<server>/mcagent-runtime/` — **never `mods/`** |

The core is a permanent `@Mod`: entrypoint, config spec, 5 game-bus listeners, `RuntimeHost`, and the
`/mcagent runtime` + `/mcagent reload` commands. The runtime holds everything else (brain, perception,
pathfinding, actions, LLM client, memory, knowledge, bot lifecycle, the rest of the commands) and is
loaded from its own jar by a child `ClassLoader`, so it can be replaced while the server runs.

### The hard rule

> **Core must not reference any `com.melody.mcagent.rt` type. Runtime may freely reference core and Minecraft.**

Enforced at build time: `checkRuntimeBoundary` byte-scans core `.class` files for the slash-form
constant `com/melody/mcagent/rt` and fails the build. (It deliberately does not match the dot-form
string literal, so `RuntimeHost`'s `Class.forName("com.melody.mcagent.rt.RuntimeEntry")` is fine.)

Why it matters: if core holds a static field, a live thread, or an event-bus lambda pointing at a
runtime object, the old `ClassLoader` can never be collected and **every reload leaks the whole
classloader**. The production server already runs ~9 GB heap with a leak detector complaining.

Corollaries worth remembering:
- Runtime state (BotManager, BrainManager, knowledge) lives in `rt.Agent`, not on the core.
- Anything long-lived that runtime code starts (thread pools) must be shut down on unload;
  `AgentBrain.shutdown()` escalates to `shutdownNow()` because an in-flight LLM request otherwise
  outlives the closed loader and dies with `NoClassDefFoundError`.
- The classloader is **child-first for `rt.*` only**, parent-first for everything else. That is
  deliberate: in a dev launch the rt classes are also on the launch classpath, so strict parent-first
  would silently keep running stale bytes and every reload would be a no-op.

### Why this mod can be hot-reloaded at all

It registers **no** game objects (`DeferredRegister`/`Registry.register` — none) and has **no mixins**
(0 entries in the jar). Those two are the usual blockers; neither applies here. Keep it that way — a
new registry object or mixin belongs in core and must be treated as permanent.

---

## 2. Build, test, run

```bash
export GRADLE_USER_HOME=/ymtc/Repos/.gradle-home
GRADLE=/root/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle

$GRADLE build --offline                 # compiles, jars, runs checkRuntimeBoundary
$GRADLE compileJava --offline -q        # fast compile-only check
$GRADLE deploySmokeServer --offline     # both jars -> build/smoke-server
```

Dev server and its env-gated test harnesses — **every one of these is off unless its variable is set,
so none can ever run on a production server**:

```bash
MCAGENT_SMOKETEST=true  $GRADLE runServer --offline   # spawn/walk/A*/doors/stuck/crafting/x-ray
MCAGENT_CMD_TEST=true   $GRADLE runServer --offline   # command tree, pause/resume, goal, think
MCAGENT_INVENTORY_TEST=true ... runServer --offline   # opens and edits a bot pack through command GUI
MCAGENT_MINEDROP_TEST=true  ... runServer --offline   # radius mining + drops into the pack
MCAGENT_CHAT_TEST=true      ... runServer --offline   # chat throttle (uses a stub LLM server)
MCAGENT_CHAT_INVOKE_TEST=true ... runServer --offline # any chat -> immediate full-state optional-silence turn
MCAGENT_PERSIST_TEST=write|verify ... runServer --offline   # persistence across a real restart
MCAGENT_BRAIN_TEST=true ... runServer --offline       # full LLM loop — needs a live endpoint
MCAGENT_SABLE_TEST=true ... runServer --offline
MCAGENT_TUNNEL_TEST=true ... runServer --offline      # local descending tunnel macro
MCAGENT_PERF=true       ... runServer --offline       # visibility-scan timing
```

Decompiled Minecraft/NeoForge sources for API reference: `/ymtc/Repos/.mcai-scratch/mcsrc/`

---

## 3. The hot-reload development loop

```bash
$GRADLE build --offline
cp build/libs/mcagent-runtime.jar <server>/mcagent-runtime/mcagent-runtime.jar
# in game / console:
/mcagent reload          # swap the jar. Removes all bots (their saved data survives).
/mcagent reloadconfig    # re-read config files only. Bots stay.
/mcagent runtime         # what is loaded, from which path, and its hash
```

**No restart is needed for runtime changes.** Only core changes need one.

---

## 4. Gotchas that have already bitten

- **Never run `gradle clean`.** It deletes `build/smoke-server`, the dev game directory. It has been
  destroyed once this way.
- The runtime jar must **not** sit in `mods/` — NeoForge would try to load it as a mod. `RuntimeHost`
  resolves `mcagent-runtime/mcagent-runtime.jar` relative to the server's working directory.
- `/mcagent reload` re-registers the runtime's commands on the live dispatcher. Brigadier merges and
  never removes, so a subcommand *deleted* by a new jar lingers and keeps pinning that generation.
- Config keys are a compatibility surface: changing a name silently wipes the operator's value.
  `extraHeaders` in particular carries the `x-opencode-session` header that the OpenCode Go endpoint
  requires — losing it makes every request fail with `MissingSessionID`.
- A bot's `playerdata/<uuid>.dat` holds its **inventory and respawn point**. Anything that deletes it
  destroys the bot's belongings; persistence is the default and wiping must be explicit (`spawn ...
  fresh`).
- `MCAGENT_BRAIN_TEST` needs a working endpoint. A `429`/quota error means the LLM-in-the-loop paths
  are simply unverified — say so rather than implying they passed.

---

## 5. Deployment (production)

Server root: `/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server`
Managed by MCSM; the instance runs 259 mods and takes minutes to start, so restarts are expensive.

```bash
cp build/libs/mcagent-0.1.0+neoforge.1.21.1.jar <server>/mods/mcagent-0.1.0.jar
cp build/libs/mcagent-runtime.jar            <server>/mcagent-runtime/mcagent-runtime.jar
```

- **Both files must be present.** Core without runtime loads, but `/mcagent` is reduced to
  `runtime`/`reload` and there is no bot functionality.
- Runtime-only changes: copy the runtime jar and `/mcagent reload`. **Ask the user before restarting
  the server** — it is shared and has players on it.
- After any restart, check the log for `MC Agent runtime loaded from …` and confirm the hash matches
  the jar you deployed.

---

## 6. How to verify (read this before claiming anything is fixed)

The most expensive mistakes in this project's history were **confident claims that were not checked**.

- **Do not conclude from reading code.** Run it and paste the log.
- **Read call sites, not occurrence counts.** A grep for `deletePersistedData` showing two hits was
  read as "persistence not implemented"; in fact both were legitimate (explicit wipe, and removal).
  Twice now, a shallow check produced a wrong conclusion.
- **A fix is not done until you have seen the failure it prevents.** Where practical, disable your own
  change first and watch the test fail — a positive control proves the test can detect the bug at all.
- **Test the neighbouring behaviour you could plausibly have broken.** Rate-limiting chat must not
  block replies to players; repairing a dead bot must not lose its inventory; collecting drops must
  not delete what will not fit, nor take items another player dropped.
- **Persistence bugs need a real stop/start**, two separate `runServer` invocations. `/mcagent reload`
  exercises a different removal path and will happily pass while a restart still loses data.
- Log lines are evidence only if they are legible: say *what* was repaired, *which* target was
  abandoned, *why* a message was not sent. Silent state changes have cost hours here.

---

## 7. Domain notes

- **Perception** is occlusion-based with a deliberate allowance: line of sight passes through up to
  `Perception.SEE_THROUGH_BLOCKS` (5) solid blocks, because a strictly exact test made a bot in a
  birch grove report that there were no trees. Scanning is distance-ordered so a time-budget cut keeps
  the *nearest* blocks; landmark types (logs, leaves, fluids, block entities) are reported with
  coordinates and a compass bearing.
- **Pathfinding** treats doors and fence gates as passable and the bot opens closed ones itself
  (`DoorBlock` never returns an empty collision shape, so a naive "is the collision box empty" test
  rejects every door in the game). A walk gives up after `NO_PROGRESS_TICKS` without getting
  measurably closer — not merely when the bot fails to move at all, because flowing water nudges a
  standing bot enough to defeat that test forever.
- **Tool calls** run in the order the model lists them, up to 12 sibling calls per turn. For real
  work, prefer the `plan` tool: one call buffers up to 24 strictly sequential actions, stops dependent
  work on failure, and starts a lookahead request when fewer than 6 queued steps remain. Concurrent tools (`say`,
  `look_at`, `eat`, `remember`, `forget`, `recall`, `find_item`, `find_uses`, `craftable_now`,
  `interrupt`) execute even while a long action runs; everything else queues. Queued calls are
  answered immediately (`queued: …`) — a tool call left unanswered makes the whole turn malformed and
  the provider rejects it.
- **Memory** is per-bot JSON under `<world>/mcagent-memory/`, pinned into the system prompt. Container
  contents are recorded automatically; everything else is the model's choice.
- `maxTokens` must clear the model's *thinking* budget, not just its answer — too low and a reasoning
  model spends the whole turn thinking and emits no tool call at all.
