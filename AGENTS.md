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
MCAGENT_CHAT_INVOKE_TEST=true MCAGENT_CHAT_KEEP=off ... runServer --offline
                                                      # its second-phase control: a watchdog-forced
                                                      # turn is allowed to consume the message
MCAGENT_CHAT_INVOKE_TEST=true MCAGENT_CHAT_FAR=off ... runServer --offline
                                                      # its third-phase control: a message from
                                                      # outside earshot is not treated as addressed
MCAGENT_PERSIST_TEST=write|verify ... runServer --offline   # persistence across a real restart
MCAGENT_BRAIN_TEST=true ... runServer --offline       # full LLM loop — needs a live endpoint
MCAGENT_SABLE_TEST=true ... runServer --offline
MCAGENT_TUNNEL_TEST=true ... runServer --offline      # local descending tunnel macro
MCAGENT_ESCAPE_TEST=true ... runServer --offline      # staircase escape + economical tool fallback
MCAGENT_SELFCLEAR_TEST=true ... runServer --offline   # a queued step naming a block the bot just cleared
                                                      # must not abort the plan (the tunnel-death bug)
MCAGENT_SELFCLEAR_TEST=true MCAGENT_SELFCLEARED=off ... runServer --offline   # its positive control
MCAGENT_MINING_GOAL_TEST=true ... runServer --offline  # the persistent mining skill finishes a whole
                                                       # trip on one planning call; interrupt cancels it
MCAGENT_MINING_GOAL_TEST=true MCAGENT_MINING_GOAL=off ... runServer --offline  # its positive control
MCAGENT_PERF=true       ... runServer --offline       # visibility-scan timing
MCAGENT_STRUCTURE_TEST=true ... runServer --offline   # player-built structure guard (mine/tunnel/escape)
MCAGENT_STRUCTURE_TEST=true MCAGENT_STRUCTURE_GUARD=off ... runServer --offline   # its positive control
MCAGENT_JEV_MINE_TEST=true ... runServer --offline    # Jev mining recovery, confidence and race guards
MCAGENT_SPEECH_TEST=true    ... runServer --offline   # Jev speech gate, low-confidence silence,
                                                      # narration guard + repetition guard (stub System One)
MCAGENT_SPEECH_TEST=true MCAGENT_NARRATION_GUARD=off ... runServer --offline  # its positive control
MCAGENT_CMD_UX_TEST=true    ... runServer --offline   # spawn resumes at the logout position; tab completion
MCAGENT_RELOAD_TEST=true    ... runServer --offline   # a reload must not wait on an in-flight model call
MCAGENT_RELOAD_TEST=true MCAGENT_RELOAD_INTERRUPT=off ... runServer --offline  # its positive control
MCAGENT_ANVIL_TEST=true     ... runServer --offline   # anvil repair + enchanting table through the
                                                      # real tool loop, and the refusals that must be
                                                      # honest (no durability / nothing to repair with)
MCAGENT_FARM_TEST=true      ... runServer --offline   # ripe crops harvested and replanted with zero
                                                      # planning turns; unripe crops left alone
MCAGENT_ARMOR_TEST=true     ... runServer --offline   # hold() puts armour on the body, not in the hand
MCAGENT_FARMBUILD_TEST=true ... runServer --offline   # chooses its own ground, then tills, waters,
                                                      # lights and sows a field with no planning turns
MCAGENT_DANGER_TEST=true    ... runServer --offline   # a phantom attack is refused and a wounded bot
                                                      # drops its job, eats and heads home by itself
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
- **Never `cp` over the live runtime jar - rename onto it.** `deploy-hot.sh` writes a `.new` file and
  `mv`s it. The running generation still holds that jar open, and overwriting the same inode leaves
  its class loader reading a file whose central directory has moved: every class it has not loaded
  yet then dies with `ZipException: ZipFile invalid LOC header` and `NoClassDefFoundError`, mid-tick.
  A rename leaves the old inode alone and gives the new generation a complete file.
- **`/mcagent reload` deleting itself is a real failure mode.** The core registers `runtime`/`reload`
  and *then* delegates to the runtime, so a runtime that replaces the whole `/mcagent` node removes
  the command used to load it. `BotCommands.refreshSubcommands` therefore deletes only the children
  the runtime itself is about to register. If it ever happens again: `tools/restore-core-commands.sh`
  re-registers the core's commands on the live dispatcher (no restart). It renames its agent class on
  every run, because HotSpot memoises the class loader of the **first** attached agent jar - same
  class name, stale bytes, silently.
- **Brigadier merges command trees; it never replaces a node.** Same-named children keep the old
  node's argument type and suggestion provider, and only the executor and grandchildren are merged
  in. Adding `.suggests()` to a command therefore does nothing on a server that already registered
  it: production kept 9月20日's argument nodes for two days. `/mcagent commands` reads the **live**
  dispatcher and reports which bot-name arguments actually complete; the dev-server test cannot catch
  this because its dispatcher is always fresh.
- **A tool-schema type that is not a JSON Schema type kills every request, not one tool.**
  `LlmClient.schema` splits `"type: description"` on the first colon, so `"array of strings: ..."`
  became `"type": "array of strings"` and the provider answered every planning call with
  `11129 invalid function call parameters`. Arrays have to be hand-built with an `items` type;
  `LlmClient` now degrades an unknown type to `string` and warns, and `MINEGOALTEST` asserts the types
  in the real outgoing request. No dev test can see this through the scripted model, which ignores
  tool schemas - check the request, not the model's behaviour.
- **Chat is recorded for every bot in the dimension; distance only decides who is addressed.**
  `ChatLog` used to *record* only within 64 blocks, so a message aimed at the bot could vanish with
  no trace: production had a player answer the bot's own question from just outside earshot and the
  bot looked like it was ignoring them - no chat turn, no gate decision, nothing in the log. Now
  every message is recorded and `directed` is what uses range. A **language request** additionally
  bypasses the silence gate: it is a message about how to talk, and a gate looking for "does this
  need an action" reads it as chatter. Ordinary task instructions still go through the gate, because
  that is where the calibrated silence and repetition rules live.
- **The cheap layer's saving is only visible in `JEV STATS`.** A call that did not happen leaves no
  log line, so the absolute call rate says nothing about whether routing works; one line per 5
  minutes reports calls, intercepted, idle_continuation, idle_skipped and avoided_percent. Read that
  before claiming a reduction. The measured shape: routing intercepts about half of the decisions it
  is asked about, and the other half of the calls are idle re-plans (a one-to-three step plan drains,
  the bot is idle, and it buys a 12k-token planning turn).
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
- **The anvil and the enchanting table are not containers** (`rt/action/Stations.java`). Neither is
  a `Container` block entity, so `open_container` cannot see either of them, and a bare `use` only
  installs the vanilla menu on a player who has no client to click it - which is why `use` now says
  so and names the tool that can. `repair` and `enchant` drive the menus directly: put the item in,
  press the button or take the result, then put everything back. Which material repairs what is
  decided by vanilla's `Item.isValidRepairItem`, so a modded tool that implements it works unchanged.
  `repair` also merges two worn copies of the same item, which is what a player does with no
  material. An anvil is the one machine here with **no block entity**, so `Perception.isLandmark`
  names it explicitly; otherwise it would never appear as something worth walking to.
- **The knowledge index builds on a worker thread** (`mcagent-knowledge`), not on the server thread.
  Only the recipe-list snapshot happens on the caller's thread, because reading the recipe manager is
  world state; the indexing is computation over immutable holders. That took 107-591 ms out of every
  reload (the answer never changes when the jar is swapped, but the object cannot survive the class
  loader being closed, so each generation builds its own). Until the worker publishes, the item and
  recipe tools answer "the item/recipe index is not ready yet; try again shortly" - about 200 ms in
  production. `KnowledgeManager.clear()` interrupts and joins that worker before the loader closes.
- **Farming is a runtime-owned skill** (`FarmGoal` in `AgentBrain`, `rt/action/Farming.java`,
  `rt/perception/Crops.java`). Ripe crops are listed in the observation with coordinates, and the
  `farm` tool adopts a field (centre + radius). From then on the skill harvests every ripe crop and
  replants it with the seeds the bot carries, with **no planning turn at all** - the whole point, and
  what `MCAGENT_FARM_TEST` asserts by counting model requests (one for the whole field). It differs
  from the mining goal in one way: it only claims a tick when something is ripe, so a growing field
  never swallows the bot. Crop maturity is type-driven (`CropBlock.isMaxAge`, nether wart age), and
  the replant item comes from `Block.getCloneItemStack`, so modded crops work unmodified.
- **Logs go to their own file, not the console** (`rt/Logging.java`). Everything this mod logs lands
  in `logs/mcagent.log` (override with `MCAGENT_LOG_FILE`, or `MCAGENT_LOG_CONSOLE=true` for both) -
  the bot talks constantly and on a server with players on it that buries every other mod's warnings.
  This means **`latest.log` no longer contains mcagent lines**, so a forensics grep has to read
  `logs/mcagent.log` (and its rolled `.gz` files). Two things to know if you touch it: log4j2 splits
  logger names on '.', so `mcagent/brain` is *not* a child of `mcagent` and one logger config cannot
  cover the mod - hence the generated list of names in the jar (the `mcagentLoggerNames` Gradle task),
  which means a new logger cannot quietly stay in the console; and the appender is added from the
  runtime, so routing is hot-deployable rather than a `log4j2.xml` that would change how every other
  mod logs.
- **Survival reflexes** (`tickDangerReflex`). Two rules, both of them things a player does without
  deciding to, and both from production: **phantoms are never fought** (they fly, three attacks came
  back `target is out of reach`, and four deaths - the bot goes home and sleeps, which is the actual
  mechanic), and **low health breaks off work** (the job is abandoned, the bot eats, and it walks
  home; it stays in that state until it has recovered, so a model that re-issues "mine" at 5 health
  is refused rather than obeyed into its own grave). The harness caught a real gap in the first
  version: the phantom branch returned before the eating branch ran, so a bot hiding from phantoms at
  food 4 never regenerated.
- **Falling blocks are checked above the dig, not just in it.** The excavation macros already refused
  to *break* gravel or sand; they did not check the block *above the space being cleared*, which is
  the one a dig drops. Six of production's deaths were "suffocated in a wall" in self-dug shafts, so
  `ceilingWouldFall` now gates both the escape stair and the tunnel.
- **Harnesses must clean up their sites.** The dev world is shared by every harness, and a test that
  leaves an enchanting table and a ring of bookshelves behind makes the next one fail with "you are
  standing inside the player-built structure" - which is exactly what happened to the tunnel test.
- **Building a field has an order, and the order is the feature** (`build_farm`, `FarmBuildJob`,
  `rt/perception/FarmSite.java`). Site first, and never on a whim: level ground a hoe will turn, air
  above every cell, no fluid through it, and not inside anything a player built - the guard is asked,
  so the two can never disagree, and every rejection carries its reason. Then the build itself:
  torches on the ring *outside* the field, then the water hole, then till, then sow - **without
  walking over the field again**. Farmland that has been fallen on is dirt again, so a bot that walks
  across its own half-finished field destroys it; that is why the bot works from the middle and why
  the field is capped at 5x5, which is what it can reach from there. It also digs the water hole
  under its own feet *before* tilling, so the one fall it takes lands on grass.
- **A bucket cannot be placed with `use`.** `Actions.useOnBlock` goes through
  `ItemStack.useOn(UseOnContext)` and a bucket does not implement it: vanilla places a bucket in
  `BucketItem.use(Level, Player, InteractionHand)`, which only the *item* path reaches. So
  right-clicking a block with a water bucket did nothing and said nothing. `Farming.placeFluid` uses
  `gameMode.useItem` instead, after looking at the block the fluid should land on, because that path
  raycasts from the player's own eyes.
- **Being "busy" still buys planning turns.** The lookahead path deliberately requests a new decision
  every couple of seconds while a long action runs, so a runtime-owned skill that merely keeps the bot
  busy costs turns - a single 5x5 field cost five of them before `tick()` returned early for
  `farmBuildJob`, the way it already did for `miningGoal`.
- **Armour is worn, not held.** `hold` used to fill the main hand for everything, so a bot that
  crafted a full iron set fought zombies holding iron boots - 27 Zombie deaths, and a self-written
  note that armour "cannot be equipped with my tools". `Actions.holdItem` now asks vanilla
  (`Equipable.getEquipmentSlot`) where a piece belongs and wears it, shields go to the offhand, and
  `MCAGENT_ARMOR_TEST` scripts exactly the calls production made.
- **Player-built structures are protected** (`rt/perception/PlayerStructure.java`): fixtures (beds,
  storage, workstations, anything with a block entity) are never breakable, and a cluster of building
  blocks around a fixture protects its whole box plus 6 blocks of foundation. Every breaking path
  funnels through `AgentBrain.startMine`, which is where the refusal is enforced; the planners refuse
  earlier so the bot gets an honest message instead of a cascade of failed steps. `escape_up` inside a
  building walks out through the door instead of digging. Classify blocks by id *tokens*, never
  `Set.of(path.split("_"))` — mods ship ids like `chipped:bricks_bricks` and `Set.of` throws on
  duplicates, which escapes the classifier entirely.
- `/mcagent spawn <name>` with no coordinates resumes at the position recorded in the bot's own
  playerdata (`BotManager.savedLogout`), in the dimension it logged out in: vanilla restores a
  returning player's dimension from that file but never their coordinates, because those normally
  come from the client. Every bot-name argument completes to the online bots; `spawn` completes to
  the names that still have saved data, read from the server's `usercache.json` (vanilla's
  `GameProfileCache` keeps its entry type package-private, so its `load()` is unusable here).
- The dev server loads the runtime from **`build/smoke-server/mcagent-runtime/mcagent-runtime.jar`**,
  not from `build/classes`. Run `deploySmokeServer` before `runServer`, or you will be testing the
  previous build and conclude your change did nothing. Also note the env vars must be visible to the
  forked server JVM: a long-lived Gradle daemon started without them will not pass them on.
