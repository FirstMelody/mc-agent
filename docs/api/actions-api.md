# Server-Side Player ACTIONS — Exact API Contract

**Target:** Minecraft **1.21.1** + **NeoForge 21.1.248**
**Sources:** `/ymtc/Repos/.mcai-scratch/mcsrc/` (decompiled). All paths below are relative to that root; prefix with `/ymtc/Repos/.mcai-scratch/mcsrc/` for absolute paths.
**Method:** static source reading only. No build was run and no runtime verification was performed (a concurrent build was in progress).
**Rule applied throughout:** every signature below was **copied verbatim** from the decompiled source with a `path:line` reference. Anything not found is marked **NOT FOUND** rather than guessed. NeoForge patches are marked `// Neo:`.

---

## 0. THE #1 FINDING — a fake connection must drive the player tick

**`ServerPlayerGameMode.tick()`, attack-cooldown ticking, item-cooldown ticking and the container validity check are only reachable through the packet listener's `tick()`.**

Exact chain (`ServerPlayer.tick()` **does not** call `super.tick()`; `doTick()` does):

```
Connection.tick()                                    Connection.java:409
  -> packetListener.tick()                           Connection.java:411
     -> ServerGamePacketListenerImpl.tick()          ServerGamePacketListenerImpl.java:253
        -> this.player.doTick()                      ServerGamePacketListenerImpl.java:260
           -> super.tick()   (= ServerPlayer.tick()) ServerPlayer.java:556
              -> this.gameMode.tick()                ServerPlayer.java:496
              -> containerMenu.broadcastChanges()    ServerPlayer.java:503
              -> containerMenu.stillValid(this)      ServerPlayer.java:504
                 -> Player.tick()                    ServerPlayer.java (via super.tick())
                    -> attackStrengthTicker++        Player.java:316
                    -> this.cooldowns.tick()         Player.java:327
```

Verbatim anchors:

```java
@Override
public void tick() {
    this.gameMode.tick();
    this.wardenSpawnTracker.tick();
    this.spawnInvulnerableTime--;
    if (this.invulnerableTime > 0) {
        this.invulnerableTime--;
    }

    this.containerMenu.broadcastChanges();
    if (!this.level().isClientSide && !this.containerMenu.stillValid(this)) {
        this.closeContainer();
        this.containerMenu = this.inventoryMenu;
    }
```
— `net/minecraft/server/level/ServerPlayer.java:494-507`

```java
public void doTick() {
    try {
        if (!this.isSpectator() || !this.touchingUnloadedChunk()) {
            super.tick();
        }
```
— `net/minecraft/server/level/ServerPlayer.java:553-556`

```java
public void tick() {
    this.flushQueue();
    if (this.packetListener instanceof TickablePacketListener tickablepacketlistener) {
        tickablepacketlistener.tick();
    }
```
— `net/minecraft/network/Connection.java:409-413`

```java
this.resetPosition();
this.player.xo = this.player.getX();
this.player.yo = this.player.getY();
this.player.zo = this.player.getZ();
this.player.doTick();
this.player.absMoveTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
```
— `net/minecraft/server/network/ServerGamePacketListenerImpl.java:256-261`

### Consequences — all confirmed

| Capability | Works without a driven `doTick()`? |
|---|---|
| Timed block breaking via `gameMode.tick()` | **NO** |
| `destroyBlock()` direct call | Yes (does not need tick) |
| Attack at full charge | **NO** — `attackStrengthTicker` stays 0, so `getAttackStrengthScale` ≈ 0.07 |
| Item cooldowns (`player.getCooldowns()`) | **NO** |
| Container `stillValid` auto-close | **NO** (but also: never auto-closes — see §3) |
| `PlayerContainerEvent` / menu broadcast | Partial |

> **HOW TO IMPLEMENT (mandatory):** the fake connection must implement `TickablePacketListener.tick()` and call `player.doTick()`, and it must either be registered in `ServerConnectionListener`'s connection list or be ticked manually from a server-tick hook. Mirror the vanilla ordering — copy `xo/yo/zo` **before** `doTick()`, because `getEyePosition(float)`/`getViewVector(float)` interpolate against them, and `absMoveTo(firstGoodX…)` afterwards.
>
> **CAUTION (uncertainty flagged):** `ServerCommonPacketListenerImpl.keepConnectionAlive()` will disconnect a listener that never answers keep-alives. If you write your own listener, override the keep-alive path or do not register it with `ServerConnectionListener`. I did **not** verify this at runtime.

---

## 1. Block breaking

### 1.1 Exact signatures — `net/minecraft/server/level/ServerPlayerGameMode`

```java
public void handleBlockBreakAction(BlockPos p_215120_, ServerboundPlayerActionPacket.Action p_215121_, Direction p_215122_, int p_215123_, int p_215124_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:138`

```java
public void destroyAndAck(BlockPos p_215117_, int p_215118_, String p_215119_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:238`

```java
public boolean destroyBlock(BlockPos p_9281_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:247`

```java
public void tick()
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:98`

```java
public InteractionResult useItem(ServerPlayer p_9262_, Level p_9263_, ItemStack p_9264_, InteractionHand p_9265_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:303`

```java
public InteractionResult useItemOn(ServerPlayer p_9266_, Level p_9267_, ItemStack p_9268_, InteractionHand p_9269_, BlockHitResult p_9270_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:337`

```java
public void setLevel(ServerLevel p_9261_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:400`

**NOT FOUND on `ServerPlayerGameMode`:** `startDestroyBlock`, `continueDestroyBlock`, `stopDestroyBlock`, and any `destroyProgress` field.

`startDestroyBlock` / `continueDestroyBlock` / `stopDestroyBlock` exist **only client-side**, on `net/minecraft/client/multiplayer/MultiPlayerGameMode` (`MultiPlayerGameMode.java:136`, `:188`, `:201`). See §1.5.

### 1.2 Fields — verbatim

```java
public class ServerPlayerGameMode {
    private static final Logger LOGGER = LogUtils.getLogger();
    protected ServerLevel level;
    protected final ServerPlayer player;
    private GameType gameModeForPlayer = GameType.DEFAULT_MODE;
    @Nullable
    private GameType previousGameModeForPlayer;
    private boolean isDestroyingBlock;
    private int destroyProgressStart;
    private BlockPos destroyPos = BlockPos.ZERO;
    private int gameTicks;
    private boolean hasDelayedDestroy;
    private BlockPos delayedDestroyPos = BlockPos.ZERO;
    private int delayedTickStart;
    private int lastSentState = -1;
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:32-46`

> `destroyProgress` is **NOT** a server field. It only exists client-side: `private float destroyProgress;` — `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:70`.
> `isDestroyingBlock` and `destroyPos` are **private** — not readable from outside the class. Drive mining through the packet-action API instead.

### 1.3 The accessor on the player

```java
public final ServerPlayerGameMode gameMode;
```
— `net/minecraft/server/level/ServerPlayer.java:179`

```java
public GameType getGameModeForPlayer() {
    return this.gameModeForPlayer;
}
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:81`

### 1.4 How mining PROGRESS works server-side — and the critical caveat

Verbatim `tick()`:

```java
public void tick() {
    this.gameTicks++;
    if (this.hasDelayedDestroy) {
        BlockState blockstate = this.level.getBlockState(this.delayedDestroyPos);
        if (blockstate.isAir()) {
            this.hasDelayedDestroy = false;
        } else {
            float f = this.incrementDestroyProgress(blockstate, this.delayedDestroyPos, this.delayedTickStart);
            if (f >= 1.0F) {
                this.hasDelayedDestroy = false;
                this.destroyBlock(this.delayedDestroyPos);
            }
        }
    } else if (this.isDestroyingBlock) {
        BlockState blockstate1 = this.level.getBlockState(this.destroyPos);
        if (blockstate1.isAir()) {
            this.level.destroyBlockProgress(this.player.getId(), this.destroyPos, -1);
            this.lastSentState = -1;
            this.isDestroyingBlock = false;
        } else {
            this.incrementDestroyProgress(blockstate1, this.destroyPos, this.destroyProgressStart);
        }
    }
}
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:98-121`

```java
private float incrementDestroyProgress(BlockState p_9277_, BlockPos p_9278_, int p_9279_) {
    int i = this.gameTicks - p_9279_;
    float f = p_9277_.getDestroyProgress(this.player, this.player.level(), p_9278_) * (float)(i + 1);
    int j = (int)(f * 10.0F);
    if (j != this.lastSentState) {
        this.level.destroyBlockProgress(this.player.getId(), p_9278_, j);
        this.lastSentState = j;
    }

    return f;
}
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:123-133`

#### ⚠ ANSWER TO THE EXPLICIT QUESTION

> *"Does calling `handleBlockBreakAction` with `Action.START_DESTROY_BLOCK` then letting `tick()` run produce correct timed breaking?"*

**NO — not by itself.** In the `isDestroyingBlock` branch, `tick()` calls `incrementDestroyProgress(...)` and **discards its return value**. It only broadcasts crack particles (`level.destroyBlockProgress`) and, if the block turned to air, clears the flag. `destroyBlock()` is **never** called from that branch.

`destroyBlock()` is reached from exactly three places:
1. **Insta-mine on start** — `handleBlockBreakAction` START branch, `if (!blockstate.isAir() && f >= 1.0F) destroyAndAck(...)` — `ServerPlayerGameMode.java:186-187`.
2. **STOP_DESTROY_BLOCK** — `if (f1 >= 0.7F) { … destroyAndAck(p_215120_, p_215124_, "destroyed"); return; }` — `ServerPlayerGameMode.java:207-211`.
3. **`hasDelayedDestroy`** — a *private* flag set **only** by the STOP branch when `f1 < 0.7F` — `ServerPlayerGameMode.java:214-219`.

So the vanilla server relies on the **client** tracking `destroyProgress` locally, and sending `STOP_DESTROY_BLOCK` when its own accumulated progress reaches `1.0F`. Server-side, the STOP branch recomputes `f1 = getDestroyProgress(...) * (j + 1)` where `j = gameTicks - destroyProgressStart`, and accepts at `>= 0.7F` (a 30 % fudge factor for latency).

Exact STOP branch, verbatim:

```java
} else if (p_215121_ == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
    if (p_215120_.equals(this.destroyPos)) {
        int j = this.gameTicks - this.destroyProgressStart;
        BlockState blockstate1 = this.level.getBlockState(p_215120_);
        if (!blockstate1.isAir()) {
            float f1 = blockstate1.getDestroyProgress(this.player, this.player.level(), p_215120_) * (float)(j + 1);
            if (f1 >= 0.7F) {
                this.isDestroyingBlock = false;
                this.level.destroyBlockProgress(this.player.getId(), p_215120_, -1);
                this.destroyAndAck(p_215120_, p_215124_, "destroyed");
                return;
            }

            if (!this.hasDelayedDestroy) {
                this.isDestroyingBlock = false;
                this.hasDelayedDestroy = true;
                this.delayedDestroyPos = p_215120_;
                this.delayedTickStart = this.destroyProgressStart;
            }
        }
    }
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:201-221`

#### Timing formula

`getDestroyProgress` (Neo-patched):

```java
protected float getDestroyProgress(BlockState p_60466_, Player p_60467_, BlockGetter p_60468_, BlockPos p_60469_) {
    float f = p_60466_.getDestroySpeed(p_60468_, p_60469_);
    if (f == -1.0F) {
        return 0.0F;
    } else {
        int i = net.neoforged.neoforge.event.EventHooks.doPlayerHarvestCheck(p_60467_, p_60466_, p_60468_, p_60469_) ? 30 : 100;
        return p_60467_.getDigSpeed(p_60466_, p_60469_) / f / (float)i;
    }
}
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:343-350` (**Neo patch on line 348**)

Ticks to break = `ceil(1.0F / getDestroyProgress(state, player, level, pos))`. Note `ticks * progress` must reach `1.0F` server-side, and STOP is accepted at `>= 0.7F`.

`getDigSpeed` (Neo-patched, encodes tool + efficiency + haste + mining fatigue):

```java
public float getDigSpeed(BlockState p_36282_, @Nullable BlockPos pos) {
```
— `net/minecraft/world/entity/player/Player.java:751` (body 751-779, `EventHooks.getBreakSpeed(...)` at 778)

Related harvest helpers:

```java
public boolean hasCorrectToolForDrops(BlockState state, Level level, BlockPos pos) {
    return net.neoforged.neoforge.event.EventHooks.doPlayerHarvestCheck(this, state, level, pos);
}
```
— `net/minecraft/world/entity/player/Player.java:788-790` (**Neo**)

```java
default boolean canHarvestBlock(BlockGetter level, BlockPos pos, Player player) {
    return self().getBlock().canHarvestBlock(self(), level, pos, player);
}
```
— `net/neoforged/neoforge/common/extensions/IBlockStateExtension.java:126-128` (**Neo**)

> **Correct tool matters automatically.** `getDestroyProgress` calls `doPlayerHarvestCheck` and uses `/30` vs `/100`, and `getDigSpeed` reads `player.getInventory().getDestroySpeed(state)` → the selected hotbar item. Putting the right tool in the selected hotbar slot is sufficient; no manual tool handling is needed.

### 1.5 Client-side reference protocol (what a real client sends)

```java
public boolean startDestroyBlock(BlockPos p_105270_, Direction p_105271_)
```
— `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:136`

```java
public boolean continueDestroyBlock(BlockPos p_105284_, Direction p_105285_)
```
— `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:201`

```java
public void stopDestroyBlock()
```
— `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:188`

The client accumulates `this.destroyProgress = this.destroyProgress + blockstate.getDestroyProgress(...)` each tick (`MultiPlayerGameMode.java:222`) and when `destroyProgress >= 1.0F` sends `STOP_DESTROY_BLOCK` (`MultiPlayerGameMode.java:242-247`). In creative it sends `START_DESTROY_BLOCK` on a 5-tick `destroyDelay` cadence (`MultiPlayerGameMode.java:206-215`).

**This is exactly the protocol the bot must emulate.**

### 1.6 Progress broadcast

```java
public void destroyBlockProgress(int p_8612_, BlockPos p_8613_, int p_8614_)
```
— `net/minecraft/server/level/ServerLevel.java:963`

It excludes the breaking player (`serverplayer.getId() != p_8612_`) and only sends within `d0*d0+d1*d1+d2*d2 < 1024.0` (32 blocks) — `ServerLevel.java:964-971`.

### 1.7 Drops

```java
public static List<ItemStack> getDrops(BlockState p_49870_, ServerLevel p_49871_, BlockPos p_49872_, @Nullable BlockEntity p_49873_)
```
— `net/minecraft/world/level/block/Block.java:265`

```java
public static List<ItemStack> getDrops(
    BlockState p_49875_, ServerLevel p_49876_, BlockPos p_49877_, @Nullable BlockEntity p_49878_, @Nullable Entity p_49879_, ItemStack p_49880_
)
```
— `net/minecraft/world/level/block/Block.java:273-275`

```java
public static void dropResources(BlockState p_49951_, Level p_49952_, BlockPos p_49953_)
```
— `net/minecraft/world/level/block/Block.java:284`

```java
public static void dropResources(BlockState p_49893_, LevelAccessor p_49894_, BlockPos p_49895_, @Nullable BlockEntity p_49896_)
```
— `net/minecraft/world/level/block/Block.java:293`

```java
public static void dropResources(
        BlockState p_49882_, Level p_49883_, BlockPos p_49884_, @Nullable BlockEntity p_49885_, @Nullable Entity p_49886_, ItemStack p_49887_
)
```
— `net/minecraft/world/level/block/Block.java:302-304`

```java
public void playerDestroy(Level p_49827_, Player p_49828_, BlockPos p_49829_, BlockState p_49830_, @Nullable BlockEntity p_49831_, ItemStack p_49832_) {
    p_49828_.awardStat(Stats.BLOCK_MINED.get(this));
    p_49828_.causeFoodExhaustion(0.005F);
    dropResources(p_49830_, p_49827_, p_49829_, p_49831_, p_49828_, p_49832_);
}
```
— `net/minecraft/world/level/block/Block.java:372-376`

All three `dropResources` overloads are **Neo-patched** to fire `CommonHooks.handleBlockDrops(...)` (`Block.java:288-289`, `:297-298`, `:306-307`). `popResource(...)` (`Block.java:311`) spawns the `ItemEntity`.

**Pickup** — `net/minecraft/world/entity/item/ItemEntity.java:364`:

```java
public void playerTouch(Player p_32040_) {
    if (!this.level().isClientSide) {
        ItemStack itemstack = this.getItem();
        Item item = itemstack.getItem();
        int i = itemstack.getCount();

        // Neo: Fire item pickup pre/post and adjust handling logic to adhere to the event result.
        var result = net.neoforged.neoforge.event.EventHooks.fireItemPickupPre(this, p_32040_).canPickup();
        if (result.isFalse()) {
            return;
        }
        …
        if ((result.isTrue() || this.pickupDelay == 0 && (this.target == null || this.target.equals(p_32040_.getUUID()))) && p_32040_.getInventory().add(itemstack)) {
```
— `net/minecraft/world/entity/item/ItemEntity.java:364-379`

> **There is no entity→player magnet API.** Drops are only picked up when the `ItemEntity` collides with the player, is outside its `pickupDelay` (40 ticks after `drop`, or 10 normally), and `result.isTrue()` or `pickupDelay == 0`.

### HOW TO IMPLEMENT SERVER-SIDE — block breaking

```java
// 1. Must be on the server thread, and the fake connection must be driving doTick().
// 2. Put the tool in the SELECTED hotbar slot (index 0-8) or no drops/ slow mining:
player.getInventory().setItem(player.getInventory().selected, toolStack.copy());

// 3. Start the break — this fires PlayerInteractEvent.LeftClickBlock and returns early if canceled.
gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                direction, player.level().getMaxBuildHeight(), /* sequence */ 0);

// 4. Compute the exact vanilla tick count and wait that many ticks.
BlockState state = level.getBlockState(pos);
float perTick = state.getDestroyProgress(player, level, pos);   // BlockBehaviour.java:614-616
int ticks = (perTick <= 0.0F) ? Integer.MAX_VALUE : (int) Math.ceil(1.0F / perTick);

// 5. After `ticks` ticks, STOP. Server accepts at f1 >= 0.7F and otherwise arms
//    `hasDelayedDestroy`, which tick() will complete for us.
gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                                direction, player.level().getMaxBuildHeight(), /* sequence */ 0);

// 6. To abort (e.g. target changed):
gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                                direction, player.level().getMaxBuildHeight(), 0);
```

**Simpler alternative** when you do not need the mining animation or `LeftClickBlock`/`onClientMineHold` nuance:

```java
gameMode.destroyBlock(pos);   // fires CommonHooks.fireBlockBreak + Block.playerDestroy -> correct drops
```

> **Recommendation:** use the `START` → wait → `STOP` sequence, because it routes through `PlayerInteractEvent.LeftClickBlock` (`ServerPlayerGameMode.java:139`) and `fireBlockBreak` (`:249`), so mods observe exactly what they would for a real player. Use `destroyBlock` directly only for instant/creative-style breaks.
>
> **Uncertainty flagged:** the `p_215123_` argument must be at least `pos.getY()` or the method bails with `"too high"` (`:145-147`). I used `level.getMaxBuildHeight()`; the client sends the server-provided build height. Also note `handleBlockBreakAction` requires `player.canInteractWithBlock(pos, 1.0)` (`:143`), i.e. within `blockInteractionRange + 1.0`.

---

## 2. Block placing / item use

### 2.1 Exact signatures

```java
public InteractionResult interactOn(Entity p_36158_, InteractionHand p_36159_)
```
— `net/minecraft/world/entity/player/Player.java:1050` (entity interaction — see §4)

**NOT FOUND on `Player`:** `useItemOn(ItemStack, InteractionHand, BlockHitResult)` and `useItem(...)`. In 1.21.1 those live on **`ServerPlayerGameMode`** (see §1.1):

```java
public InteractionResult useItemOn(ServerPlayer p_9266_, Level p_9267_, ItemStack p_9268_, InteractionHand p_9269_, BlockHitResult p_9270_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:337`

```java
public InteractionResult useItem(ServerPlayer p_9262_, Level p_9263_, ItemStack p_9264_, InteractionHand p_9265_)
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:303`

> ⚠ **This is a significant 1.21.1 correction:** `ServerPlayer.useItemOn(...)` in `ServerGamePacketListenerImpl` is a **`private`** helper, not a `Player` method. Any plan written against `player.useItemOn(...)` must be rewritten as `player.gameMode.useItemOn(player, level, stack, hand, hitResult)`.

```java
public ItemInteractionResult useItemOn(ItemStack p_316374_, Level p_316651_, Player p_316623_, InteractionHand p_316469_, BlockHitResult p_316877_)
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:751` (`BlockState`'s public form; delegates at `:755`)

```java
protected ItemInteractionResult useItemOn(
    ItemStack p_316304_, BlockState p_316362_, Level p_316459_, BlockPos p_316366_, Player p_316132_, InteractionHand p_316595_, BlockHitResult p_316140_
)
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:205-207` (**protected** — the block-level override point)

```java
public InteractionResult useWithoutItem(Level p_316368_, Player p_316500_, BlockHitResult p_316346_)
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:758` (delegates at `:759`)

```java
protected InteractionResult useWithoutItem(BlockState p_60503_, Level p_60504_, BlockPos p_60505_, Player p_60506_, BlockHitResult p_60508_)
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:201-203` (**protected**; returns `InteractionResult.PASS` by default)

```java
public InteractionResult useOn(UseOnContext p_41662_)
```
— `net/minecraft/world/item/ItemStack.java:357` (Neo-patched: posts `UseItemOnBlockEvent(ITEM_AFTER_BLOCK)` then, server-side, `CommonHooks.onPlaceItemIntoWorld(...)`)

```java
public InteractionResult onItemUseFirst(UseOnContext p_41662_)
```
— `net/minecraft/world/item/ItemStack.java:364` (**Neo**: `UseItemOnBlockEvent(ITEM_BEFORE_BLOCK)`)

```java
public InteractionResultHolder<ItemStack> use(Level p_41683_, Player p_41684_, InteractionHand p_41685_)
```
— `net/minecraft/world/item/ItemStack.java:390` (delegates to `Item.use`)

```java
public InteractionResultHolder<ItemStack> use(Level p_41432_, Player p_41433_, InteractionHand p_41434_)
```
— `net/minecraft/world/item/Item.java:152`

```java
public ItemStack finishUsingItem(Level p_41672_, LivingEntity p_41673_)
```
— `net/minecraft/world/item/ItemStack.java:~394`

### 2.2 `InteractionResult` variants — the exact enum

```java
public enum InteractionResult {
    SUCCESS,
    SUCCESS_NO_ITEM_USED,
    CONSUME,
    CONSUME_PARTIAL,
    PASS,
    FAIL;

    public boolean consumesAction() {
        return this == SUCCESS || this == CONSUME || this == CONSUME_PARTIAL || this == SUCCESS_NO_ITEM_USED;
    }

    public boolean shouldSwing() {
        return this == SUCCESS || this == SUCCESS_NO_ITEM_USED;
    }

    public boolean indicateItemUse() {
        return this == SUCCESS || this == CONSUME;
    }

    public static InteractionResult sidedSuccess(boolean p_19079_) {
        return p_19079_ ? SUCCESS : CONSUME;
    }
}
```
— `net/minecraft/world/InteractionResult.java:3-23`

> **`SUCCESS_SERVER` does NOT exist** in this version — it was a 1.21.2+ addition. The 1.21.1 set is exactly `SUCCESS, SUCCESS_NO_ITEM_USED, CONSUME, CONSUME_PARTIAL, PASS, FAIL`.

**How to interpret:**
- `consumesAction()` == true → the interaction happened; **do not** fall through to another handler, and the arm should swing (`shouldSwing()`).
- `PASS` → nothing handled; fall through (vanilla then tries `useWithoutItem`, then the item's `useOn`).
- `FAIL` → explicitly refused; do **not** fall through.

### 2.3 `ItemInteractionResult` — a separate, block-only enum

`ItemInteractionResult` **exists** in this tree and is used by `BlockState.useItemOn`. Its distinguishing constant (referenced in `ServerPlayerGameMode`):

```java
if (iteminteractionresult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION && p_9269_ == InteractionHand.MAIN_HAND) {
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:369`

It also exposes `.consumesAction()` and `.result()` (converting to an `InteractionResult`), used at `:364-366`.

> **Caution (uncertainty flagged):** I read the usage sites but did not enumerate every `ItemInteractionResult` constant. Treat the enum's full member list as unverified; the only constant I confirmed verbatim is `PASS_TO_DEFAULT_BLOCK_INTERACTION`.

### 2.4 `BlockHitResult` construction

```java
public BlockHitResult(Vec3 p_82415_, Direction p_82416_, BlockPos p_82417_, boolean p_82418_) {
```
— `net/minecraft/world/phys/BlockHitResult.java:16`

```java
public static BlockHitResult miss(Vec3 p_82427_, Direction p_82428_, BlockPos p_82429_) {
```
— `net/minecraft/world/phys/BlockHitResult.java:12`

```java
public BlockHitResult withDirection(Direction p_82433_)
```
— `net/minecraft/world/phys/BlockHitResult.java:28`

```java
public BlockHitResult withPosition(BlockPos p_82431_)
```
— `net/minecraft/world/phys/BlockHitResult.java:32`

Field meaning, from the superclass `HitResult`:
- `p_82415_` (`Vec3`) — the **exact hit location**, in world coordinates (e.g. `Vec3.atCenterOf(pos)` or the clipped point).
- `p_82416_` (`Direction`) — the **face clicked**; this is the face the block is placed **against**.
- `p_82417_` (`BlockPos`) — the block that was hit (the **support** block, *not* the placement position).
- `p_82418_` (`boolean inside`) — whether the hit originated **inside** the block. Pass `false` for normal interactions; `true` makes blocks like slabs/stairs treat the click as coming from within.

```java
public enum Type {
    MISS,
    BLOCK,
    ENTITY;
```
— `net/minecraft/world/phys/HitResult.java:26-28`

```java
public double distanceTo(Entity p_82449_)
```
— `net/minecraft/world/phys/HitResult.java:12`

> **NOT FOUND:** a 5-argument `BlockHitResult` constructor (`…, boolean inside, boolean miss`). Only the 4-arg canonical constructor plus the `miss(...)` factory exist in this tree.

### 2.5 Hand / swing API

```java
public void setItemInHand(InteractionHand p_21009_, ItemStack p_21010_)
```
— `net/minecraft/world/entity/LivingEntity.java:2002`

```java
public ItemStack getItemInHand(InteractionHand p_21121_)
```
— `net/minecraft/world/entity/LivingEntity.java:1992`

```java
public ItemStack getMainHandItem()
```
— `net/minecraft/world/entity/LivingEntity.java:1970`

```java
public ItemStack getOffhandItem()
```
— `net/minecraft/world/entity/LivingEntity.java:1974`

Both `getItemInHand`/`setItemInHand` throw `IllegalArgumentException("Invalid hand " + hand)` on a null hand. `setItemInHand(MAIN_HAND, …)` writes through the `Inventory`'s selected slot.

```java
public void swing(InteractionHand p_21007_) {
    this.swing(p_21007_, false);
}
```
— `net/minecraft/world/entity/LivingEntity.java:1808-1810`

```java
public void swing(InteractionHand p_21012_, boolean p_21013_) {
```
— `net/minecraft/world/entity/LivingEntity.java:1812`

The `boolean` means **"also send the swing animation to the swinging player"** — it selects `broadcastAndSend` (includes the source chunk) vs `broadcast`:

```java
if (p_21013_) {
    serverchunkcache.broadcastAndSend(this, clientboundanimatepacket);
} else {
    serverchunkcache.broadcast(this, clientboundanimatepacket);
}
```
— `net/minecraft/world/entity/LivingEntity.java:1821-1825`

> **Use `player.swing(hand, true)`** — that is what vanilla's server uses at the three real interaction sites (`ServerGamePacketListenerImpl.java:1135`, `:1174`, `:1574`).
>
> **IMPORTANT INTERACTION WITH ATTACK CHARGE:** `ServerPlayer` overrides only the **1-arg** form:
> ```java
> public void swing(InteractionHand p_9031_) {
>     super.swing(p_9031_);
>     this.resetAttackStrengthTicker();
> }
> ```
> — `net/minecraft/server/level/ServerPlayer.java:1747-1750`
>
> So `swing(hand, true)` does **not** reset the attack ticker, while `swing(hand)` **does**. Choose deliberately — for a block-item right-click use `swing(hand, true)`.

### 2.6 Verbatim ordering inside `useItemOn` (so you know what gets fired)

```java
UseOnContext useoncontext = new UseOnContext(p_9266_, p_9269_, p_9270_);
if (event.getUseItem() != net.neoforged.neoforge.common.util.TriState.FALSE) {
    InteractionResult result = p_9268_.onItemUseFirst(useoncontext);
    if (result != InteractionResult.PASS) return result;
}
boolean flag = !p_9266_.getMainHandItem().isEmpty() || !p_9266_.getOffhandItem().isEmpty();
boolean flag1 = (p_9266_.isSecondaryUseActive() && flag) && !(p_9266_.getMainHandItem().doesSneakBypassUse(p_9267_, blockpos, p_9266_) && p_9266_.getOffhandItem().doesSneakBypassUse(p_9267_, blockpos, p_9266_));
ItemStack itemstack = p_9268_.copy();
if (event.getUseBlock().isTrue() || (event.getUseBlock().isDefault() && !flag1)) {
    ItemInteractionResult iteminteractionresult = blockstate.useItemOn(p_9266_.getItemInHand(p_9269_), p_9267_, p_9266_, p_9269_, p_9270_);
    if (iteminteractionresult.consumesAction()) {
        CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(p_9266_, blockpos, itemstack);
        return iteminteractionresult.result();
    }

    if (iteminteractionresult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION && p_9269_ == InteractionHand.MAIN_HAND) {
        InteractionResult interactionresult = blockstate.useWithoutItem(p_9267_, p_9266_, p_9270_);
        if (interactionresult.consumesAction()) {
            CriteriaTriggers.DEFAULT_BLOCK_USE.trigger(p_9266_, blockpos);
            return interactionresult;
        }
    }
}
```
— `net/minecraft/server/level/ServerPlayerGameMode.java:354-376`

This is the **complete vanilla right-click-on-block pipeline**, including the chest-opening path (a chest's `useWithoutItem` calls `player.openMenu(menuprovider)` — `ChestBlock.java:233-247`).

### HOW TO IMPLEMENT SERVER-SIDE — placing a block

```java
// Right-click a block face with the item currently in MAIN_HAND.
BlockPos support = hitPos;                              // the block the click landed ON
Direction face   = Direction.UP;                        // the face clicked
Vec3 hitVec      = Vec3.atCenterOf(support).add(
                      face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
BlockHitResult hit = new BlockHitResult(hitVec, face, support, /* inside = */ false);

ItemStack stack = player.getMainHandItem();
InteractionResult res = player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit);

if (res.shouldSwing()) {
    player.swing(InteractionHand.MAIN_HAND, true);   // true = also broadcast to self
}
// res.consumesAction() -> done. res == PASS -> try useItem (air click) instead:
if (res == InteractionResult.PASS) {
    player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
}
```

To use an item in the air (bow, ender pearl, food):

```java
player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
```

To place a block with **precise reach**, mirror the client: clip from the eyes and build the hit from the clip result (see §9).

---

## 3. Containers / inventories

> Much of this section is corroborated by a dedicated sub-agent pass; the load-bearing claims (openMenu body, stillValid chain, slot ranges) were independently confirmed.

### 3.1 Opening a container server-side — exact signatures

```java
public OptionalInt openMenu(@Nullable MenuProvider p_9033_) {
    return openMenu(p_9033_, (java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf>) null);
}
```
— `net/minecraft/server/level/ServerPlayer.java:1114-1117`

```java
@Override
public OptionalInt openMenu(@Nullable MenuProvider p_9033_, @Nullable java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> extraDataWriter) {
```
— `net/minecraft/server/level/ServerPlayer.java:1119-1120` (body 1120-1169)

Base no-op (client-side `Player`):

```java
public OptionalInt openMenu(@Nullable MenuProvider p_36150_) {
    return OptionalInt.empty();
}
```
— `net/minecraft/world/entity/player/Player.java:1040-1042`

> **NOT FOUND:** `openMenu(MenuProvider, Consumer<FriendlyByteBuf>)`. In 1.21.1 the NeoForge overload uses **`RegistryFriendlyByteBuf`**, not `FriendlyByteBuf`.

```java
public void closeContainer()
```
— `net/minecraft/server/level/ServerPlayer.java:1206-1210` (override) and `net/minecraft/world/entity/player/Player.java:500-502` (base)

```java
public void doCloseContainer()
```
— `net/minecraft/server/level/ServerPlayer.java:1212-1218` (override); `Player.java:504-505` (base, empty)

```java
private void nextContainerCounter() {
    this.containerCounter = this.containerCounter % 100 + 1;
}
```
— `net/minecraft/server/level/ServerPlayer.java:1110-1112` (**private**)

```java
private void initMenu(AbstractContainerMenu p_143400_) {
    p_143400_.addSlotListener(this.containerListener);
    p_143400_.setSynchronizer(this.containerSynchronizer);
}
```
— `net/minecraft/server/level/ServerPlayer.java:463-466` (**private**)

```java
public void initInventoryMenu() {
    this.initMenu(this.inventoryMenu);
}
```
— `net/minecraft/server/level/ServerPlayer.java:468-470` (**public** — call this on a hand-built bot; normally done by `PlayerList` on join)

The send calls inside `openMenu` (all to `this.connection`):

```java
this.connection.send(new ClientboundOpenScreenPacket(abstractcontainermenu.containerId, abstractcontainermenu.getType(), p_9033_.getDisplayName()));
this.initMenu(abstractcontainermenu);
this.containerMenu = abstractcontainermenu;
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(this, this.containerMenu));
return OptionalInt.of(this.containerCounter);
```
— `net/minecraft/server/level/ServerPlayer.java:1160-1166`

### 3.2 Can a fake connection hold a menu open? — **YES**

**There is no handshake, no ack and no timeout.** Confirmed by reading every container packet handler:

- `ServerGamePacketListenerImpl.handleContainerClose(...)` — only entered from `ServerboundContainerClosePacket` (`ServerGamePacketListenerImpl.java:1649-1653`).
- `handleContainerClick(...)` — only entered from `ServerboundContainerClickPacket` (`:1655-1689`). Its `stateId` mismatch only selects `broadcastFullState()` vs `broadcastChanges()`; it never kicks or closes.
- **`handleContainerAction` — NOT FOUND** anywhere in the tree. That packet does not exist in 1.21.1.
- `handleSetCreativeModeSlot`, `handleContainerButtonClick`, `handleContainerSlotStateChanged` — all client-packet-entry only.

`Connection.send(...)` on a channel-less connection **queues** instead of throwing:

```java
public void send(Packet<?> p_295839_, @Nullable PacketSendListener p_294866_, boolean p_294265_) {
    if (this.isConnected()) {
        this.flushQueue();
        this.sendPacket(p_295839_, p_294866_, p_294265_);
    } else {
        this.pendingActions.add(p_293706_ -> p_293706_.sendPacket(p_295839_, p_294866_, p_294265_));
    }
}
```
— `net/minecraft/network/Connection.java:336-343`

```java
public boolean isConnected() {
    return this.channel != null && this.channel.isOpen();
}
```
— `net/minecraft/network/Connection.java:583-585`

So `ClientboundOpenScreenPacket` and `ClientboundContainerSetContentPacket` are simply queued and dropped.

**The only thing that closes a menu** is the per-tick `stillValid` check at `ServerPlayer.java:504` (quoted in §0), which **is** driven by the level entity tick loop independently of the connection:

```java
this.containerMenu.broadcastChanges();
if (!this.level().isClientSide && !this.containerMenu.stillValid(this)) {
    this.closeContainer();
    this.containerMenu = this.inventoryMenu;
}
```
— `net/minecraft/server/level/ServerPlayer.java:503-507`

#### ⚠ WARNING — NeoForge's own `FakePlayer` cannot open containers

`net/neoforged/neoforge/common/util/FakePlayer.java` overrides `openMenu(...)` to `return OptionalInt.empty();` (≈`:129-131`) and blanks `tick()` (≈`:123`). **Do not extend it for container work** — either subclass `ServerPlayer` yourself, or override `openMenu` back to `super.openMenu(...)`.

### 3.3 `MenuProvider` and `Container`

```java
public interface MenuProvider extends MenuConstructor, net.neoforged.neoforge.client.extensions.IMenuProviderExtension {
    Component getDisplayName();
}
```
— `net/minecraft/world/MenuProvider.java:6-8` (the Neo extension is on the **common** interface)

```java
public interface MenuConstructor {
    @Nullable
    AbstractContainerMenu createMenu(int p_39954_, Inventory p_39955_, Player p_39956_);
}
```
— `net/minecraft/world/inventory/MenuConstructor.java:7-11`

```java
public interface Container extends Clearable {
    float DEFAULT_DISTANCE_BUFFER = 4.0F;

    int getContainerSize();
    boolean isEmpty();
    ItemStack getItem(int p_18941_);
    ItemStack removeItem(int p_18942_, int p_18943_);
    ItemStack removeItemNoUpdate(int p_18951_);
    void setItem(int p_18944_, ItemStack p_18945_);

    default int getMaxStackSize() { return 99; }
    default int getMaxStackSize(ItemStack p_335963_) { return Math.min(this.getMaxStackSize(), p_335963_.getMaxStackSize()); }

    void setChanged();
    boolean stillValid(Player p_18946_);

    default void startOpen(Player p_18955_) {}
    default void stopOpen(Player p_18954_) {}
    default boolean canPlaceItem(int p_18952_, ItemStack p_18953_) { return true; }
    default boolean canTakeItem(Container p_273520_, int p_272681_, ItemStack p_273702_) { return true; }
    default int countItem(Item p_18948_) { … }
    default boolean hasAnyOf(Set<Item> p_18950_) { … }
    default boolean hasAnyMatching(Predicate<ItemStack> p_216875_) { … }

    static boolean stillValidBlockEntity(BlockEntity p_273154_, Player p_273222_) {
        return stillValidBlockEntity(p_273154_, p_273222_, 4.0F);
    }

    static boolean stillValidBlockEntity(BlockEntity p_272877_, Player p_272670_, float p_320837_) {
        Level level = p_272877_.getLevel();
        BlockPos blockpos = p_272877_.getBlockPos();
        if (level == null) {
            return false;
        } else {
            return level.getBlockEntity(blockpos) != p_272877_ ? false : p_272670_.canInteractWithBlock(blockpos, (double)p_320837_);
        }
    }
}
```
— `net/minecraft/world/Container.java:12-94`

> **NOT FOUND:** `Container.removeItem(ItemStack)` as a default. The only such method is `Inventory.java:324` (an identity-based helper, not a `Container` contract member). `clearContent()` comes from `Clearable` (`net/minecraft/world/Clearable.java:5-12`).

### 3.4 `AbstractContainerMenu` — exact signatures

```java
public abstract class AbstractContainerMenu {
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:36`

```java
public static final int SLOT_CLICKED_OUTSIDE = -999;
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:38`

> **NOT FOUND:** a `SUCCESS` constant on `AbstractContainerMenu` in 1.21.1.

```java
public final int containerId;
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:56`

```java
public final NonNullList<Slot> slots = NonNullList.create();
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:47`

```java
public abstract ItemStack quickMoveStack(Player p_38941_, int p_38942_);
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:282`

```java
public Slot getSlot(int p_38854_) {
    return this.slots.get(p_38854_);
}
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:278-280`

```java
protected Slot addSlot(Slot p_38898_) {
    p_38898_.index = this.slots.size();
    this.slots.add(p_38898_);
    this.lastSlots.add(ItemStack.EMPTY);
    this.remoteSlots.add(ItemStack.EMPTY);
    return p_38898_;
}
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:102-108`

```java
protected boolean moveItemStackTo(ItemStack p_38904_, int p_38905_, int p_38906_, boolean p_38907_)
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:600` (**protected**)

```java
public abstract boolean stillValid(Player p_38874_);
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:598`

```java
public void broadcastChanges() {
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:167-186` (iterates `slots`, calls `triggerSlotListeners` + `synchronizeSlotToRemote`, then `synchronizeCarriedToRemote`, then data slots)

```java
public void sendAllDataToRemote() {
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:134-151`

```java
public void setItem(int p_182407_, int p_182408_, ItemStack p_182409_) {
    this.getSlot(p_182407_).set(p_182409_);
    this.stateId = p_182408_;
}
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:580-583`

```java
public void clicked(int p_150400_, int p_150401_, ClickType p_150402_, Player p_150403_)
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:284`

```java
public void removed(Player p_38940_)
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:546-559`

```java
public void slotsChanged(Container p_38868_)
```
— `net/minecraft/world/inventory/AbstractContainerMenu.java:576-578`

> **NOT FOUND:** `updateData(...)`. In 1.21.1 it is `public void setData(int p_38855_, int p_38856_)` — `AbstractContainerMenu.java:594-596`.
> **NOT FOUND:** `AbstractContainerMenu.getContainerSlot(...)`. That exists only on `Slot` (`Slot.java:183-185`).

Every synchronizer call inside `sendAllDataToRemote`/`synchronizeSlotToRemote`/`synchronizeCarriedToRemote` is **null-guarded** (`AbstractContainerMenu.java:148`, `:228`, `:240`, `:251`), so a menu with no synchronizer cannot throw.

### 3.5 `Slot` — exact signatures

```java
public class Slot {
    private final int slot;
    public final Container container;
    public int index;
    public final int x;
    public final int y;
```
— `net/minecraft/world/inventory/Slot.java:11-16`

```java
public Slot(Container p_40223_, int p_40224_, int p_40225_, int p_40226_)
```
— `net/minecraft/world/inventory/Slot.java:18`

```java
public ItemStack getItem() { return this.container.getItem(this.slot); }
public boolean hasItem() { return !this.getItem().isEmpty(); }
public void setByPlayer(ItemStack p_270152_) { this.setByPlayer(p_270152_, this.getItem()); }
public void setByPlayer(ItemStack p_299990_, ItemStack p_299965_) { this.set(p_299990_); }
public void set(ItemStack p_40240_) {
    this.container.setItem(this.slot, p_40240_);
    this.setChanged();
}
public void setChanged() { this.container.setChanged(); }
public int getMaxStackSize() { return this.container.getMaxStackSize(); }
public int getMaxStackSize(ItemStack p_40238_) { return Math.min(this.getMaxStackSize(), p_40238_.getMaxStackSize()); }
public ItemStack remove(int p_40227_) { return this.container.removeItem(this.slot, p_40227_); }
public boolean mayPlace(ItemStack p_40231_) { return true; }
public boolean mayPickup(Player p_40228_) { return true; }
public boolean isActive() { return true; }
public int getContainerSlot() { return this.slot; }
```
— `net/minecraft/world/inventory/Slot.java:45-193` (individual lines: 45, 49, 53, 57, 61, 65, 70, 74, 78, 87, 91, 95, 183)

> **Both `setByPlayer` and `setChanged` exist** in 1.21.1. `setByPlayer` is the player-caused hook (overridden by `ArmorSlot` and the offhand slot for equip sounds); `set`/`setChanged` are the raw primitives. `setChanged(ItemStack)` does **not** exist.

### 3.6 Chest access

```java
@Nullable
public static Container getContainer(ChestBlock p_51512_, BlockState p_51513_, Level p_51514_, BlockPos p_51515_, boolean p_51516_) {
    return p_51512_.combine(p_51513_, p_51514_, p_51515_, p_51516_).apply(CHEST_COMBINER).orElse(null);
}
```
— `net/minecraft/world/level/block/ChestBlock.java:257-260`

`CHEST_COMBINER` returns `new CompoundContainer(left, right)` for a double chest, the `ChestBlockEntity` for a single chest, `Optional.empty()` otherwise — `ChestBlock.java:67-79`.

```java
@Nullable
@Override
protected MenuProvider getMenuProvider(BlockState p_51574_, Level p_51575_, BlockPos p_51576_)
```
— `net/minecraft/world/level/block/ChestBlock.java:278-282` (**`protected`** — call it via `blockState.getMenuProvider(level, pos)` instead)

```java
@Nullable
public MenuProvider getMenuProvider(Level p_60751_, BlockPos p_60752_) {
    return this.getBlock().getMenuProvider(this.asState(), p_60751_, p_60752_);
}
```
— `net/minecraft/world/level/block/state/BlockBehaviour.java:802-805`

```java
public static boolean isChestBlockedAt(LevelAccessor p_51509_, BlockPos p_51510_) {
    return isBlockedChestByBlock(p_51509_, p_51510_) || isCatSittingOnChest(p_51509_, p_51510_);
}
```
— `net/minecraft/world/level/block/ChestBlock.java:311-313`

```java
@Nullable
@Override
public BlockEntity getBlockEntity(BlockPos p_46716_)
```
— `net/minecraft/world/level/Level.java:769-779`. **Returns `null` if called off the server thread** (`Level.java:775-777`) — always call from the server thread.

```java
@Override
public boolean stillValid(Player p_332791_) {
    return Container.stillValidBlockEntity(this, p_332791_);
}
```
— `net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java:126-129`

**Effective chest reach:** `Container.stillValidBlockEntity(be, player)` → `…, 4.0F` → `player.canInteractWithBlock(pos, 4.0)` → `new AABB(pos).distanceToSqr(getEyePosition()) < (blockInteractionRange() + 4.0)^2`. With the default `blockInteractionRange = 4.5`, that is **≈ 8.5 blocks** from eye to block AABB. For a **double** chest, `CompoundContainer.stillValid` requires **both** halves in range (`CompoundContainer.java:70-73`).

Class declarations:

```java
public class ChestBlockEntity extends RandomizableContainerBlockEntity implements LidBlockEntity
```
— `net/minecraft/world/level/block/entity/ChestBlockEntity.java:27`

```java
public abstract class BaseContainerBlockEntity extends BlockEntity implements Container, MenuProvider, Nameable
```
— `net/minecraft/world/level/block/entity/BaseContainerBlockEntity.java:25`

> `ChestBlockEntity` does **NOT** implement `WorldlyContainer` in 1.21.1, and its `getItems()` is `protected` (not a NeoForge addition).

```java
public interface WorldlyContainer extends Container {
    int[] getSlotsForFace(Direction p_19238_);
    boolean canPlaceItemThroughFace(int p_19235_, ItemStack p_19236_, @Nullable Direction p_19237_);
    boolean canTakeItemThroughFace(int p_19239_, ItemStack p_19240_, Direction p_19241_);
}
```
— `net/minecraft/world/WorldlyContainer.java:7-13`

### 3.7 `ChestMenu` — constructor and transfer semantics

> **CORRECTION:** the constructor `ChestMenu(int, Inventory, Container, int)` does **NOT** exist. The public constructor takes `MenuType<?>` first.

```java
public ChestMenu(MenuType<?> p_39229_, int p_39230_, Inventory p_39231_, Container p_39232_, int p_39233_)
```
— `net/minecraft/world/inventory/ChestMenu.java:50`

```java
public static ChestMenu threeRows(int p_39238_, Inventory p_39239_, Container p_39240_)
```
— `net/minecraft/world/inventory/ChestMenu.java:42-44`

```java
public static ChestMenu sixRows(int p_39238_…)   // exact signature
```
— `net/minecraft/world/inventory/ChestMenu.java:46-48` — `public static ChestMenu sixRows(int p_39247_, Inventory p_39248_, Container p_39249_)`

> **NOT FOUND:** `nineRows`. Available factories are `oneRow` (18), `twoRows` (22), `threeRows` (26/42), `fourRows` (30), `fiveRows` (34), `sixRows` (38/46).

```java
@Override
public boolean stillValid(Player p_39242_) {
    return this.container.stillValid(p_39242_);
}
```
— `net/minecraft/world/inventory/ChestMenu.java:75-78`

```java
@Override
public ItemStack quickMoveStack(Player p_39253_, int p_39254_) {
    ItemStack itemstack = ItemStack.EMPTY;
    Slot slot = this.slots.get(p_39254_);
    if (slot != null && slot.hasItem()) {
        ItemStack itemstack1 = slot.getItem();
        itemstack = itemstack1.copy();
        if (p_39254_ < this.containerRows * 9) {
            if (!this.moveItemStackTo(itemstack1, this.containerRows * 9, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(itemstack1, 0, this.containerRows * 9, false)) {
            return ItemStack.EMPTY;
        }

        if (itemstack1.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
    }

    return itemstack;
}
```
— `net/minecraft/world/inventory/ChestMenu.java:80-103`

`quickMoveStack` returns the **original stack copy** (non-empty ⇒ something moved; `ItemStack.EMPTY` ⇒ destination full), and it does **not** call `broadcastChanges()` — the tick loop picks the change up on the next tick.

**Slot ranges** for `ChestMenu` with `n = rows`:
`[0, 9n)` = chest · `[9n, 9n+27)` = player main inventory · `[9n+27, 9n+36)` = player hotbar · `slots.size() == 9n + 36`.

### 3.8 Inheritance pitfalls

`ServerPlayer.getInventory()` and `Player.inventoryMenu`/`containerMenu`:

```java
public final InventoryMenu inventoryMenu;
public AbstractContainerMenu containerMenu;
```
— `net/minecraft/world/entity/player/Player.java:161-162`

```java
final Inventory inventory = new Inventory(this);
```
— `net/minecraft/world/entity/player/Player.java:159` (**package-private** — use `getInventory()`)

```java
public Inventory getInventory() {
    return this.inventory;
}
```
— `net/minecraft/world/entity/player/Player.java:1429-1431`

### HOW TO IMPLEMENT SERVER-SIDE — opening and using a chest

```java
// Server thread only (Level.getBlockEntity returns null off-thread).
BlockState state = level.getBlockState(chestPos);

// --- grab the Container (single OR double chest) ---
Container container = null;
if (state.getBlock() instanceof ChestBlock chestBlock) {
    // final `true` => ignore chest-blocked state, matching NeoForge's own capability hook.
    container = ChestBlock.getContainer(chestBlock, state, level, chestPos, true);
}
if (container == null) return;                                  // not a chest / no block entity

// --- verify access (optional but recommended; menu auto-closes otherwise) ---
if (!container.stillValid(player)) {
    // move the bot within ~8 blocks of the chest first
    return;
}

// --- open it (headless-safe) ---
MenuProvider provider = state.getMenuProvider(level, chestPos);
if (provider == null) return;                                   // e.g. chest locked
OptionalInt windowId = player.openMenu(provider);
if (windowId.isEmpty()) return;                                 // createMenu returned null
AbstractContainerMenu menu = player.containerMenu;              // menu != inventoryMenu now

// --- shift-click an item from the player inventory INTO the chest ---
int chestSlotCount = 9 * menu.getRowCount();                    // getRowCount() is public
int chosen = -1;
for (int i = chestSlotCount; i < menu.slots.size(); i++) {      // player inv (main then hotbar)
    Slot s = menu.getSlot(i);
    if (s.hasItem() && ItemStack.isSameItemSameComponents(s.getItem(), wanted)) { chosen = i; break; }
}
if (chosen >= 0) {
    // Full-fidelity: goes through doClick -> QUICK_MOVE branch (checks mayPickup, loops).
    menu.clicked(chosen, 0, ClickType.QUICK_MOVE, player);
}

// --- or direct single transfer (bypasses mayPickup; ChestMenu does the bookkeeping) ---
// menu.quickMoveStack(player, chosen);

// --- close ---
player.closeContainer();   // or player.doCloseContainer() to skip the (queued) packet
```

> **`level.getCapability` note (uncertainty flagged):** NeoForge's `Capabilities.ItemHandler.BLOCK` path is an alternative for modded inventories. I did **not** verify the exact `Level.getCapability` signature in this pass — treat that as unverified.

### 3.9 `InventoryMenu` — the player's own menu

```java
public static final int CONTAINER_ID = 0;
public static final int RESULT_SLOT = 0;
public static final int CRAFT_SLOT_START = 1;
public static final int CRAFT_SLOT_COUNT = 4;
public static final int CRAFT_SLOT_END = 5;
public static final int ARMOR_SLOT_START = 5;
public static final int ARMOR_SLOT_COUNT = 4;
public static final int ARMOR_SLOT_END = 9;
public static final int INV_SLOT_START = 9;
public static final int INV_SLOT_END = 36;
public static final int USE_ROW_SLOT_START = 36;
public static final int USE_ROW_SLOT_END = 45;
public static final int SHIELD_SLOT = 45;
```
— `net/minecraft/world/inventory/InventoryMenu.java:17-29`

```java
public InventoryMenu(Inventory p_39706_, boolean p_39707_, final Player p_39708_)
```
— `net/minecraft/world/inventory/InventoryMenu.java:52`

```java
public static boolean isHotbarSlot(int p_150593_) {
    return p_150593_ >= 36 && p_150593_ < 45 || p_150593_ == 45;
}
```
— `net/minecraft/world/inventory/InventoryMenu.java:94-96`

### 3.10 THE INDEX CONVENTION TABLE — `Inventory` vs `InventoryMenu`

These are **different index spaces**. Mixing them up is the single most likely bug.

| Container | `Inventory.getItem(int)` space | `InventoryMenu.getSlot(int)` space |
|---|---|---|
| Hotbar | `0` … `8` (`Inventory.selected` range) | `36` … `44` |
| Main inventory (rows 1-3) | `9` … `35` | `9` … `35` |
| Armor | `36` … `39` | `5` … `8` |
| Offhand | `40` | `45` |
| Crafting result | — | `0` |
| Crafting grid | — | `1` … `4` |

Derivation, verbatim:

```java
public static final int INVENTORY_SIZE = 36;
private static final int SELECTION_SIZE = 9;
public static final int SLOT_OFFHAND = 40;
public static final int NOT_FOUND_INDEX = -1;
public static final int[] ALL_ARMOR_SLOTS = new int[]{0, 1, 2, 3};
public static final int[] HELMET_SLOT_ONLY = new int[]{3};
public final NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
public final NonNullList<ItemStack> armor = NonNullList.withSize(4, ItemStack.EMPTY);
public final NonNullList<ItemStack> offhand = NonNullList.withSize(1, ItemStack.EMPTY);
private final List<NonNullList<ItemStack>> compartments = ImmutableList.of(this.items, this.armor, this.offhand);
public int selected;
```
— `net/minecraft/world/entity/player/Inventory.java:26-36`

```java
@Override
public int getContainerSize() {
    return this.items.size() + this.armor.size() + this.offhand.size();   // = 41
}
```
— `net/minecraft/world/entity/player/Inventory.java:426-429`

```java
@Override
public ItemStack getItem(int p_35991_) {
    List<ItemStack> list = null;

    for (NonNullList<ItemStack> nonnulllist : this.compartments) {
        if (p_35991_ < nonnulllist.size()) {
            list = nonnulllist;
            break;
        }

        p_35991_ -= nonnulllist.size();
    }

    return list == null ? ItemStack.EMPTY : list.get(p_35991_);
}
```
— `net/minecraft/world/entity/player/Inventory.java:454-468` — proves the `0-35 / 36-39 / 40` compartment chaining.

`InventoryMenu` puts armor at menu slots `5..8`, mapping to **inventory indices `39-k`** (i.e. `39, 38, 37, 36`):

```java
this.addSlot(new ArmorSlot(p_39706_, p_39708_, equipmentslot, 39 - k, 8, 8 + k * 18, resourcelocation));
```
— `net/minecraft/world/inventory/InventoryMenu.java:64-68`

Main inventory `j1 + (l + 1) * 9` (`:70-74`) and hotbar `i1` (`:76-78`) are identity-mapped; the offhand slot uses inventory index `40` (`:80`).

### 3.11 `Inventory` API — exact signatures

```java
public ItemStack getSelected()
public static int getSelectionSize()          // returns 9
public static boolean isHotbarSlot(int p_36046_)   // 0 <= i < 9
public int getFreeSlot()
public int findSlotMatchingItem(ItemStack p_36031_)
public int getSlotWithRemainingSpace(ItemStack p_36051_)
public void tick()
public boolean add(ItemStack p_36055_)
public boolean add(int p_36041_, ItemStack p_36042_)
public void placeItemBackInInventory(ItemStack p_150080_)
public void placeItemBackInInventory(ItemStack p_150077_, boolean p_150078_)
public ItemStack removeItem(int p_35993_, int p_35994_)
public ItemStack removeItemNoUpdate(int p_36029_)
public void setItem(int p_35999_, ItemStack p_36000_)
public int getContainerSize()
public ItemStack getItem(int p_35991_)
public boolean isEmpty()
public void dropAll()
public boolean contains(ItemStack p_36064_)
public boolean contains(TagKey<Item> p_204076_)
public boolean contains(Predicate<ItemStack> p_316260_)
public void replaceWith(Inventory p_36007_)
public void clearContent()
public void setChanged()
public ItemStack getArmor(int p_36053_)
```
— `net/minecraft/world/entity/player/Inventory.java:44, 50, 97, 59, 101, 200, 216, 230, 234, 285, 289, 309, 336, 358, 427, 455, 432, 479, 505, 517, 529, 541, 550, ~485, ~492`

`Inventory` is declared:

```java
public class Inventory implements Container, Nameable {
```
— `net/minecraft/world/entity/player/Inventory.java:24`

#### ⚠ `getSelectedSlot()` / `setSelectedSlot()` DO NOT EXIST

Exhaustive grep over `net/minecraft/` and `net/neoforged/`: the only `getSelectedSlot()` hits are **client-side spectator-menu** classes (`SpectatorMenu.java:102`, `SpectatorPage.java:27`, `SpectatorGui.java:69`). **`setSelectedSlot` — NOT FOUND anywhere.**

The server-side equivalents are the **public field** and the getter/setter pair:

```java
public int selected;
```
— `net/minecraft/world/entity/player/Inventory.java:36`

```java
public ItemStack getSelected() {
    return isHotbarSlot(this.selected) ? this.items.get(this.selected) : ItemStack.EMPTY;
}
```
— `net/minecraft/world/entity/player/Inventory.java:44-46`

> **Use `player.getInventory().selected = slot;` (0–8) to change the held hotbar slot.** There is also no public `setSelectedItem`; use `inventory.setItem(inventory.selected, stack)` or `player.setItemInHand(InteractionHand.MAIN_HAND, stack)`.

### 3.12 `ItemStack` operations

```java
public static final ItemStack EMPTY = new ItemStack((Void)null);
```
— `net/minecraft/world/item/ItemStack.java:180`

```java
public boolean isEmpty()          // :300
public ItemStack split(int p_41621_)        // :308
public ItemStack copyAndClear()             // :315
public int getMaxStackSize()                // :420
public ItemStack copy()                     // :552
public ItemStack copyWithCount(int p_256354_)   // :562
public int getCount()                       // :1039
public void setCount(int p_41765_)          // :1043
public void grow(int p_41770_)              // :1053
public void shrink(int p_41775_)            // :1057
```
— all in `net/minecraft/world/item/ItemStack.java`

### 3.13 Dropping an item

```java
@Nullable
public ItemEntity drop(ItemStack p_36177_, boolean p_36178_) {
    return net.neoforged.neoforge.common.CommonHooks.onPlayerTossEvent(this, p_36177_, p_36178_);
}
```
— `net/minecraft/world/entity/player/Player.java:702-705` (**Neo-wrapped**)

```java
@Nullable
public ItemEntity drop(ItemStack p_36179_, boolean p_36180_, boolean p_36181_)
```
— `net/minecraft/world/entity/player/Player.java:708` (body 708-744)

Parameter meanings (from the body):
- `p_36180_` (`dropAround`/`retainOwnership` semantics) — `true` scatters the item randomly around the player; `false` throws it along the look vector.
- `p_36181_` (`includeName`/thrower) — `true` calls `itementity.setThrower(this)`, so the item is owned briefly by the player and cannot be instantly re-picked.

Both paths set `itementity.setPickUpDelay(40)`. **Crucially, the 2-arg form returns `null` if the mod-cancellable `ItemTossEvent` is canceled** — `CommonHooks.onPlayerTossEvent` returns `event.getEntity()` only if not canceled (`CommonHooks.java:413-428`). The item is added to the level inside that hook (`player.getCommandSenderWorld().addFreshEntity(event.getEntity())`), **not** by `drop` itself.

> **Note:** `Inventory.dropAll()` calls `this.player.drop(itemstack, true, false)` — `Inventory.java:480-491`.

---

## 4. Entity interaction & combat

> Core combat path corroborated by a dedicated sub-agent pass; the headline items were independently verified against source.

### 4.1 `Player.attack(Entity)`

```java
public void attack(Entity p_36347_)
```
— `net/minecraft/world/entity/player/Player.java:1183` (body 1183-1378)

Key internals: `getAttributeValue(Attributes.ATTACK_DAMAGE)` → `getWeaponItem()` → `damageSources().playerAttack(this)` → `getEnchantedDamage(...)` → `getAttackStrengthScale(0.5F)` → `f *= 0.2F + f2*f2*0.8F` → **`p_36347_.hurt(damagesource, f3)`** (line 1251) → knockback → sweep loop → `crit(...)`/`magicCrit(...)` → `itemstack.hurtEnemy(...)`.

```java
public void crit(Entity p_36156_) {
}
```
— `net/minecraft/world/entity/player/Player.java:1395-1396` — **empty on `Player`**

```java
public void crit(Entity p_9045_) {
    this.serverLevel().getChunkSource().broadcastAndSend(this, new ClientboundAnimatePacket(p_9045_, 4));
}
```
— `net/minecraft/server/level/ServerPlayer.java:1529`

`ServerPlayer.attack` only adds the spectator-camera branch:

```java
@Override
public void attack(Entity p_9220_) {
    if (this.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
        this.setCamera(p_9220_);
    } else {
        super.attack(p_9220_);
    }
}
```
— `net/minecraft/server/level/ServerPlayer.java:1725-1732`

**✅ This is fully server-clean.** `crit`/`magicCrit` are empty on `Player` and only broadcast packets on `ServerPlayer`; `sweepAttack()` only spawns particles. No client is required.

#### Attack cooldown

```java
public float getCurrentItemAttackStrengthDelay() {
    return (float)(1.0 / this.getAttributeValue(Attributes.ATTACK_SPEED) * 20.0);
}

public float getAttackStrengthScale(float p_36404_) {
    return Mth.clamp(((float)this.attackStrengthTicker + p_36404_) / this.getCurrentItemAttackStrengthDelay(), 0.0F, 1.0F);
}

public void resetAttackStrengthTicker() {
    this.attackStrengthTicker = 0;
}
```
— `net/minecraft/world/entity/player/Player.java:2038-2048`

```java
protected int attackStrengthTicker;
```
— `net/minecraft/world/entity/LivingEntity.java:196`

```java
this.attackStrengthTicker++;
```
— `net/minecraft/world/entity/player/Player.java:316` (inside `Player.tick()`)

#### ⚠ `Player.hurt` / `Entity.hurt` — `hurtServer` DOES NOT EXIST

**`hurtServer` is NOT FOUND** anywhere in this tree (0 matches, case-insensitive, across all 6317 `.java` files). It is a **1.21.2+** API. In 1.21.1 the only damage entry point is:

```java
public boolean hurt(DamageSource p_19946_, float p_19947_)
```
— `net/minecraft/world/entity/Entity.java:1534` (base; only marks the hurt flag and **returns `false`**)

```java
@Override
public boolean hurt(DamageSource p_21016_, float p_21017_)
```
— `net/minecraft/world/entity/LivingEntity.java:1114` (the real implementation: invulnerability, shield block, i-frames via `invulnerableTime`, `actuallyHurt`, knockback, death, totem)

```java
@Override
public boolean hurt(DamageSource p_36154_, float p_36155_)
```
— `net/minecraft/world/entity/player/Player.java:889`

```java
@Override
public boolean hurt(DamageSource p_9037_, float p_9038_)
```
— `net/minecraft/server/level/ServerPlayer.java:768` (adds `spawnInvulnerableTime` and PvP checks)

> **`isCrit` — NOT FOUND** either. The only crit API is NeoForge's `CriticalHitEvent.isCriticalHit()`, produced by `CommonHooks.fireCriticalHit(...)` inside `attack()`.

Knockback:

```java
public void knockback(double p_147241_, double p_147242_, double p_147243_)
```
— `net/minecraft/world/entity/LivingEntity.java:1492`

**Sweeping** is automatic inside `attack()` — it requires a sword-like `ItemAbilities.SWORD_SWEEP` item, `flag4` (charge > 0.9), being on the ground, **and `walkDist != walkDistO`** (i.e. the player must be *moving*). Exposed as:

```java
public void sweepAttack()
```
— `net/minecraft/world/entity/player/Player.java:1401`

### 4.2 `Player.interactOn(Entity, InteractionHand)`

```java
public InteractionResult interactOn(Entity p_36158_, InteractionHand p_36159_)
```
— `net/minecraft/world/entity/player/Player.java:1050` (body 1050-1093)

Dispatch (line 1062):

```java
InteractionResult interactionresult = p_36158_.interact(this, p_36159_);
```

Fallback for living entities (line ~1075): `itemstack.interactLivingEntity(this, (LivingEntity)p_36158_, p_36159_)` — this is the path that handles **feeding animals, villager trading, shearing, milking**.

```java
public InteractionResult interact(Player p_19978_, InteractionHand p_19979_)
```
— `net/minecraft/world/entity/Entity.java:1923` (base: leash logic only, else `InteractionResult.PASS`)

> **IMPORTANT:** `interactAt` is **not** called by `interactOn`. Add it explicitly if you need hit-position-sensitive interaction.

### 4.3 Mounting

```java
public boolean startRiding(Entity p_20330_) {
    return this.startRiding(p_20330_, false);
}
```
— `net/minecraft/world/entity/Entity.java:2000-2002`

```java
public boolean startRiding(Entity p_19966_, boolean p_19967_)
```
— `net/minecraft/world/entity/Entity.java:2008`

```java
public void stopRiding() {
    this.removeVehicle();
}
```
— `net/minecraft/world/entity/Entity.java:2058-2060`

The `boolean` is **force** — `true` skips `canRide`/`canAddPassenger` checks.

### HOW TO IMPLEMENT SERVER-SIDE — attacking and interacting

```java
// --- ATTACK ---
// Requires an actively-ticked connection so attackStrengthTicker advances.
if (player.getAttackStrengthScale(0.5F) >= 1.0F
        && player.canInteractWithEntity(target, 1.0)) {   // 1.0 = vanilla verification buffer
    player.attack(target);
    player.swing(InteractionHand.MAIN_HAND, true);        // arm animation
    // attack() already called resetAttackStrengthTicker() internally — do NOT call it yourself.
}

// --- RIGHT-CLICK ENTITY (villager trade, feed, mount prompt, shear) ---
InteractionResult res = player.interactOn(target, InteractionHand.MAIN_HAND);
if (res.shouldSwing()) player.swing(InteractionHand.MAIN_HAND, true);

// --- MOUNT ---
player.startRiding(vehicle, /* force = */ true);
```

> **⚠ Do NOT call `resetAttackStrengthTicker()` after `attack()`** — `Player.attack` already calls it at line 1375. Confirmed as an explicit FORGE-moved comment in the source.
>
> **Sweep caveat:** a stationary bot will never sweep, because the sweep gate requires `walkDist - walkDistO < getSpeed()` while `walkDist` only advances with movement. If you want sweeping, drive real movement.

---

## 5. Inventory management

See §3.10 for the **index convention table** and §3.11/§3.12 for `Inventory`/`ItemStack` signatures, and §3.13 for `Player.drop`.

The single most important correction:

```java
public int selected;                     // Inventory.java:36   <- USE THIS
public ItemStack getSelected()           // Inventory.java:44-46
```

**`Inventory.getSelectedSlot()` / `setSelectedSlot(int)` — NOT FOUND.** `getSelectedSlot()` exists only on **client** spectator classes; `setSelectedSlot` exists nowhere.

### HOW TO IMPLEMENT SERVER-SIDE — inventory manipulation

```java
Inventory inv = player.getInventory();

// select a hotbar slot (0-8)
inv.selected = 3;

// what is held
ItemStack held = inv.getSelected();               // == player.getMainHandItem()

// add / count / find
if (inv.add(stack.copy())) { /* added */ }
int n = inv.countItem(Items.DIAMOND);
int slot = inv.findSlotMatchingItem(wanted);

// read/write ANY compartment with the raw index space:
ItemStack helm = inv.getItem(39);                 // armor helmet slot
ItemStack off  = inv.getItem(40);                 // offhand
inv.removeItem(0, 1);                             // one from hotbar slot 0
inv.removeItemNoUpdate(9);
inv.setItem(9, ItemStack.EMPTY);
inv.clearContent();
inv.dropAll();                                    // Player.drop(stack, true, false) per stack

// drop a single item in front of the bot
ItemStack toDrop = inv.removeItem(inv.selected, 1);
player.drop(toDrop, false, true);                 // false = throw along look vector, true = set thrower
// NOTE: returns null if a mod cancels ItemTossEvent.
```

Armor specifically: `inv.getItem(36..39)` map to `Inventory.armor` indices `0..3` in the order **helmet, chestplate, leggings, boots** — proven by:

```java
private static final EquipmentSlot[] SLOT_IDS = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
```
— `net/minecraft/world/inventory/InventoryMenu.java:46`

```java
this.addSlot(new ArmorSlot(p_39706_, p_39708_, equipmentslot, 39 - k, 8, 8 + k * 18, resourcelocation));
```
— `net/minecraft/world/inventory/InventoryMenu.java:67`

With `k = 0..3`, `SLOT_IDS[k]` and inventory index `39 - k`:
| `Inventory` index | `EquipmentSlot` |
|---|---|
| `36` | `FEET` (boots) |
| `37` | `LEGS` |
| `38` | `CHEST` |
| `39` | `HEAD` (helmet) |

---

## 6. Chat & communication

### 6.1 The vanilla player-chat broadcast path

```java
private void broadcastChatMessage(PlayerChatMessage p_243277_) {
    this.server.getPlayerList().broadcastChatMessage(p_243277_, this.player, ChatType.bind(ChatType.CHAT, this.player));
    this.detectRateSpam();
}
```
— `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1412-1415`

**This is the exact, reusable call.** Note it takes a `ServerPlayer` (not a source stack) and a `ChatType.Bound`.

`handleChat` builds the message via signing, then:

```java
PlayerChatMessage playerchatmessage1 = playerchatmessage.withUnsignedContent(component).filter(p_300785_.mask());
this.broadcastChatMessage(playerchatmessage1);
```
— `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1259-1260`

The signing itself is only for **creating** the message:

```java
playerchatmessage = this.getSignedMessage(p_9841_, optional.get());
```
— `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1249`, with `getSignedMessage` at `:1407-1410` using `SignedMessageChain.Decoder` and `LastSeenMessages`.

### 6.2 `PlayerList` broadcast API — exact signatures

```java
public void broadcastAll(Packet<?> p_11269_)
```
— `net/minecraft/server/players/PlayerList.java:543`

```java
public void broadcastAll(Packet<?> p_11271_, ResourceKey<Level> p_11272_)
```
— `net/minecraft/server/players/PlayerList.java:549`

```java
public void broadcastSystemMessage(Component p_240618_, boolean p_240644_) {
    this.broadcastSystemMessage(p_240618_, p_215639_ -> p_240618_, p_240644_);
}
```
— `net/minecraft/server/players/PlayerList.java:783-785`

```java
public void broadcastSystemMessage(Component p_240526_, Function<ServerPlayer, Component> p_240594_, boolean p_240648_)
```
— `net/minecraft/server/players/PlayerList.java:787-797`

```java
public void broadcastChatMessage(PlayerChatMessage p_243229_, CommandSourceStack p_243254_, ChatType.Bound p_243255_) {
    this.broadcastChatMessage(p_243229_, p_243254_::shouldFilterMessageTo, p_243254_.getPlayer(), p_243255_);
}
```
— `net/minecraft/server/players/PlayerList.java:798-800`

```java
public void broadcastChatMessage(PlayerChatMessage p_243264_, ServerPlayer p_243234_, ChatType.Bound p_243204_) {
    this.broadcastChatMessage(p_243264_, p_243234_::shouldFilterMessageTo, p_243234_, p_243204_);
}
```
— `net/minecraft/server/players/PlayerList.java:802-804`

```java
private void broadcastChatMessage(
    PlayerChatMessage p_249952_, Predicate<ServerPlayer> p_250784_, @Nullable ServerPlayer p_249623_, ChatType.Bound p_250276_
)
```
— `net/minecraft/server/players/PlayerList.java:806-808` (**private** — the real implementation)

```java
private boolean verifyChatTrusted(PlayerChatMessage p_251384_) {
    return p_251384_.hasSignature() && !p_251384_.hasExpiredServer(Instant.now());
}
```
— `net/minecraft/server/players/PlayerList.java:825-827`

> **This is the key finding for unsigned chat.** `verifyChatTrusted` returns `false` for an unsigned message, and the only consequence is `this.server.logChatMessage(..., "Not Secure")` (`:810`). **The broadcast itself is NOT blocked.** There is no signature gate on delivery.

### 6.3 `PlayerChatMessage` — factories

```java
public record PlayerChatMessage(
    SignedMessageLink link, @Nullable MessageSignature signature, SignedMessageBody signedBody, @Nullable Component unsignedContent, FilterMask filterMask
) {
    private static final UUID SYSTEM_SENDER = Util.NIL_UUID;
```
— `net/minecraft/network/chat/PlayerChatMessage.java:18-20, 36`

```java
public static PlayerChatMessage system(String p_249209_) {
    return unsigned(SYSTEM_SENDER, p_249209_);
}
```
— `net/minecraft/network/chat/PlayerChatMessage.java:40-42`

```java
public static PlayerChatMessage unsigned(UUID p_251783_, String p_251615_) {
    SignedMessageBody signedmessagebody = SignedMessageBody.unsigned(p_251615_);
    SignedMessageLink signedmessagelink = SignedMessageLink.unsigned(p_251783_);
    return new PlayerChatMessage(signedmessagelink, null, signedmessagebody, null, FilterMask.PASS_THROUGH);
}
```
— `net/minecraft/network/chat/PlayerChatMessage.java:44-48`

```java
public PlayerChatMessage withUnsignedContent(Component p_242164_)
```
— `net/minecraft/network/chat/PlayerChatMessage.java:50-53`

> **These are the only two public factories** (`system` and `unsigned(UUID, String)`). There is no `unsigned(String, Component, Instant, long)` overload in 1.21.1. **Uncertainty flagged:** I read lines 1-70 of the file and did not exhaustively scan 70-126; treat the "only two" claim as high-confidence but not proven.

### 6.4 `ChatType` / `ChatType.Bound`

```java
public record ChatType(ChatTypeDecoration chat, ChatTypeDecoration narration) {
```
— `net/minecraft/network/chat/ChatType.java:20`

```java
public static final ResourceKey<ChatType> CHAT = create("chat");
public static final ResourceKey<ChatType> SAY_COMMAND = create("say_command");
public static final ResourceKey<ChatType> MSG_COMMAND_INCOMING = create("msg_command_incoming");
public static final ResourceKey<ChatType> MSG_COMMAND_OUTGOING = create("msg_command_outgoing");
public static final ResourceKey<ChatType> TEAM_MSG_COMMAND_INCOMING = create("team_msg_command_incoming");
public static final ResourceKey<ChatType> TEAM_MSG_COMMAND_OUTGOING = create("team_msg_command_outgoing");
public static final ResourceKey<ChatType> EMOTE_COMMAND = create("emote_command");
```
— `net/minecraft/network/chat/ChatType.java:33-39`

> **NOT FOUND:** `MSG_COMMAND` (the names are `MSG_COMMAND_INCOMING` / `MSG_COMMAND_OUTGOING`).

```java
public static ChatType.Bound bind(ResourceKey<ChatType> p_241279_, Entity p_241483_) {
    return bind(p_241279_, p_241483_.level().registryAccess(), p_241483_.getDisplayName());
}
```
— `net/minecraft/network/chat/ChatType.java:69-71`

```java
public static ChatType.Bound bind(ResourceKey<ChatType> p_241345_, CommandSourceStack p_241466_)
```
— `net/minecraft/network/chat/ChatType.java:73-75`

```java
public static ChatType.Bound bind(ResourceKey<ChatType> p_241284_, RegistryAccess p_241373_, Component p_241455_)
```
— `net/minecraft/network/chat/ChatType.java:77-80`

```java
public static record Bound(Holder<ChatType> chatType, Component name, Optional<Component> targetName) {
```
— `net/minecraft/network/chat/ChatType.java:82` (the 2-arg package-private constructor `Bound(Holder<ChatType>, Component)` sets `targetName = Optional.empty()` — `:92-94`)

### 6.5 `ServerPlayer` messaging

```java
public void displayClientMessage(Component p_9154_, boolean p_9155_) {
    this.sendSystemMessage(p_9154_, p_9155_);
}
```
— `net/minecraft/server/level/ServerPlayer.java:1383-1385`

The `boolean` is the **action bar** flag: `true` ⇒ action bar, `false` ⇒ chat.

```java
@Override
public void sendSystemMessage(Component p_215097_) {
    this.sendSystemMessage(p_215097_, false);
}
```
— `net/minecraft/server/level/ServerPlayer.java:1585-1588`

```java
public void sendSystemMessage(Component p_240560_, boolean p_240545_) {
    if (this.acceptsSystemMessages(p_240545_)) {
        this.connection.send(new ClientboundSystemChatPacket(p_240560_, p_240545_), PacketSendListener.exceptionallySend(...));
    }
}
```
— `net/minecraft/server/level/ServerPlayer.java:1590-1609`

```java
public void sendChatMessage(OutgoingChatMessage p_249852_, boolean p_250110_, ChatType.Bound p_252108_) {
    if (this.acceptsChatMessages()) {
        p_249852_.sendToPlayer(this, p_250110_, p_252108_);
    }
}
```
— `net/minecraft/server/level/ServerPlayer.java:1613-1617`

### HOW TO IMPLEMENT SERVER-SIDE — sending chat as the bot

**This is the recommended, unsigned, client-free path:**

```java
// 1. Build an UNSIGNED chat message whose content is the bot's text.
PlayerChatMessage msg = PlayerChatMessage.unsigned(player.getUUID(), "hello world");

// 2. Broadcast it exactly the way vanilla does, with a CHAT-type bound carrying the bot's display name.
server.getPlayerList().broadcastChatMessage(
        msg,
        player,                                        // the sending ServerPlayer
        ChatType.bind(ChatType.CHAT, player)           // ChatType.bind(ResourceKey<ChatType>, Entity)
);
```

Result: real players receive a normal `ClientboundPlayerChatPacket` decorated as `chat.type.text` with the bot's name. The message is **unsigned**, so clients will show it as not-secure — vanilla only logs `"Not Secure"` server-side (`PlayerList.java:810`) and never blocks delivery.

**For a system/plain line to a single player (not a player-chat line):**

```java
player.displayClientMessage(Component.literal("hi"), false);   // false = chat, true = action bar
```

**To broadcast a system line to everyone:**

```java
server.getPlayerList().broadcastSystemMessage(Component.literal("server notice"), false);
```

> **Uncertainty flagged:** if you want a *signed* chat message (fully trusted, no "not secure" marker), you would need to construct a `SignedMessageChain` — that requires the player's private key and is **not possible** for a headless bot. The unsigned path is the only option; it is also exactly what the server itself does for `/say`-style output. I did **not** verify client-side rendering of an unsigned `ClientboundPlayerChatPacket` at runtime.

---

## 7. Commands (whitelisted, never OP)

### 7.1 `Commands` — exact signatures

```java
public void performPrefixedCommand(CommandSourceStack p_230958_, String p_230959_) {
    p_230959_ = p_230959_.startsWith("/") ? p_230959_.substring(1) : p_230959_;
    this.performCommand(this.dispatcher.parse(p_230959_, p_230958_), p_230959_);
}
```
— `net/minecraft/commands/Commands.java:262-265`

```java
public void performCommand(ParseResults<CommandSourceStack> p_242844_, String p_242841_)
```
— `net/minecraft/commands/Commands.java:267` (body 267-320)

```java
public void sendCommands(ServerPlayer p_82096_)
```
— `net/minecraft/commands/Commands.java:372`

> **Note:** the leading `/` is stripped automatically — pass either form.

Permission levels, verbatim:

```java
public static final int LEVEL_ALL = 0;
public static final int LEVEL_MODERATORS = 1;
public static final int LEVEL_GAMEMASTERS = 2;
public static final int LEVEL_ADMINS = 3;
public static final int LEVEL_OWNERS = 4;
```
— `net/minecraft/commands/Commands.java:144-148`

### 7.2 Building the source stack from a player

```java
public CommandSourceStack createCommandSourceStack() {
    return new CommandSourceStack(
        this,
        this.position(),
        this.getRotationVector(),
        this.level() instanceof ServerLevel ? (ServerLevel)this.level() : null,
        this.getPermissionLevel(),
        this.getName().getString(),
        this.getDisplayName(),
        this.level().getServer(),
        this
    );
}
```
— `net/minecraft/world/entity/Entity.java:3092-3105`

```java
protected int getPermissionLevel() {
    return 0;
}
```
— `net/minecraft/world/entity/Entity.java:3107-3109`

`ServerPlayer` **overrides** this:

```java
@Override
protected int getPermissionLevel() {
    return this.server.getProfilePermissions(this.getGameProfile());
}
```
— `net/minecraft/server/level/ServerPlayer.java:1668-1670`

### 7.3 How OP is determined

```java
public int getProfilePermissions(GameProfile p_129945_) {
    if (this.getPlayerList().isOp(p_129945_)) {
        ServerOpListEntry serveroplistentry = this.getPlayerList().getOps().get(p_129945_);
        if (serveroplistentry != null) {
            return serveroplistentry.getLevel();
        } else if (this.isSingleplayerOwner(p_129945_)) {
            return 4;
        } else if (this.isSingleplayer()) {
            return this.getPlayerList().isAllowCommandsForAllPlayers() ? 4 : 0;
        } else {
            return this.getOperatorUserPermissionLevel();
        }
    } else {
        return 0;
    }
}
```
— `net/minecraft/server/MinecraftServer.java:1732-1748`

```java
public boolean isOp(GameProfile p_11304_) {
    return this.ops.contains(p_11304_)
        || this.server.isSingleplayerOwner(p_11304_) && this.server.getWorldData().isAllowCommands()
        || this.allowCommandsForAllPlayers;
}
```
— `net/minecraft/server/players/PlayerList.java:640-644`

> **A non-OP bot gets permission level 0** and therefore fails every `requires(src -> src.hasPermission(2))` predicate.

### 7.4 Permission enforcement

```java
@Override
public boolean hasPermission(int p_81370_) {
    return this.permissionLevel >= p_81370_;
}
```
— `net/minecraft/commands/CommandSourceStack.java:389-391`

Commands declare the requirement in their builder, e.g.:

```java
.requires(p_136318_ -> p_136318_.hasPermission(2))
```
— `net/minecraft/server/commands/AdvancementCommands.java:40` (identical pattern in `AttributeCommand:44`, `BossBarCommands:68`, `ClearInventoryCommands:31`, `CloneCommands:44`, `DamageCommand:25`, `SummonCommand:35`, `TeleportCommand:43`, `:157`, …)

`performPrefixedCommand` parses through the dispatcher, so an unsatisfied `requires` predicate yields a parse failure → `sendFailure(...)` (via `finishParsing`, `Commands.java:321-327`) and **the command never executes**. This is the same enforcement a real player gets.

### 7.5 NeoForge `CommandEvent`

`Commands.performCommand` posts a NeoForge event before executing:

```java
net.neoforged.neoforge.event.CommandEvent event = new net.neoforged.neoforge.event.CommandEvent(p_242844_);
if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event).isCanceled()) {
    …
    return;
}
p_242844_ = event.getParseResults();
```
— `net/minecraft/commands/Commands.java:269-277` (**Neo patch**)

> So **mods do observe the bot's commands** — good for compatibility. (The Neo sources live under `/ymtc/Repos/.mcai-scratch/mcsrc/net/neoforged/`; the event class is `net.neoforged.neoforge.event.CommandEvent`.)

### HOW TO IMPLEMENT SERVER-SIDE — whitelisted commands

```java
// NEVER grant OP. Permission is derived from the player's real op status.
CommandSourceStack source = player.createCommandSourceStack();

// Optional: suppress command output so it does not spam the bot's (nonexistent) client.
// source = source.withSuppressedOutput();

String cmd = "/home";
if (!WHITELIST.contains(slashless(cmd))) return;    // whitelist enforced by us, first
server.getCommands().performPrefixedCommand(source, cmd);
```

Additional guard you should add, since the bot is not OP anyway:

```java
if (player.hasPermissions(2)) {
    // Should never happen for a bot — refuse to run anything if it does.
    return;
}
```

> **Why this is safe without extra work:** `createCommandSourceStack()` embeds `getPermissionLevel()` = `server.getProfilePermissions(botProfile)` = `0` for a non-op, and every privileged command is gated on `hasPermission(2)`/`hasPermission(3)` inside the vanilla/`Brigadier` dispatcher. Adding the bot to `ops.json` is what would grant OP — simply never do that.
>
> **Uncertainty flagged:** `source.withSuppressedOutput()` and `source.withLevel(...)` exist (`CommandSourceStack.java:236`, `:320`), but I did not verify the exact `withSuppressedOutput()` signature in this pass. Additionally, the whitelist commands you named (`/home`, `/spawn`) are **mod-provided**, so their permission predicates are mod-dependent — verify per-mod, because a mod could plausibly register `/home` with `LEVEL_ALL`.

---

## 8. Player state queries

### 8.1 Live state

```java
public int getAirSupply()          // Entity.java:2362
public void setAirSupply(int p_20302_)   // Entity.java:2366
public int getMaxAirSupply()       // Entity.java:2358
```

```java
public FoodData getFoodData()      // Player.java:1748
public int getFoodLevel()          // FoodData.java:91
public boolean needsFood()         // FoodData.java:99
public float getSaturationLevel()  // FoodData.java:111
public void setFoodLevel(int p_38706_)   // FoodData.java:115
public void eat(int p_38708_, float p_38709_)   // FoodData.java:25
public void eat(FoodProperties p_347533_)       // FoodData.java:29
```

```java
public float getHealth()           // LivingEntity.java:1101  (entityData.get(DATA_HEALTH_ID))
public void setHealth(float p_21154_)    // LivingEntity.java:1105
public final float getMaxHealth()  // LivingEntity.java:1776  (FINAL - cannot override)
public boolean isDeadOrDying()     // LivingEntity.java:1109
public boolean isAlive()           // LivingEntity.java:1595
public Collection<MobEffectInstance> getActiveEffects()   // LivingEntity.java:929
public boolean hasEffect(Holder<MobEffect> p_316430_)     // LivingEntity.java:937
```

### 8.2 Position / look

```java
public final Vec3 getEyePosition()       // Entity.java:1577  (FINAL)
public final Vec3 getEyePosition(float p_20300_)   // Entity.java:1581  (FINAL)
public final Vec3 getViewVector(float p_20253_)    // Entity.java:1543  (FINAL)
public Vec3 getLookAngle()               // Entity.java:2141  (NOT final)
public double getEyeY()                  // Entity.java:3348
public Level level()                     // Entity.java:3640
public final AABB getBoundingBox()       // Entity.java:2876  (FINAL)
```

```java
public ServerLevel serverLevel()         // ServerPlayer.java:1546
```

> **`serverLevel()` is declared on `ServerPlayer`, not on `Player` or `Entity`.** On a `ServerPlayer` handle you can call it directly; on a `Player` reference you must cast or use `level()`.

> **Client/server split:** none of these contain client branching — they are pure math over `xRot/yRot/xRotO/yRotO/xo/yo/zo/eyeHeight`. **But they interpolate against the `*O` previous-tick fields**, so a fake connection that never copies `xo/yo/zo` will produce stale `getEyePosition(float)`/`getViewVector(float)` values. `getEyePosition()` (no-arg) and `getLookAngle()` use current values only.

### 8.3 XP

```java
public int experienceLevel;          // Player.java:177
public int totalExperience;          // Player.java:178
public float experienceProgress;     // Player.java:179
```

```java
public void giveExperiencePoints(int p_36291_)    // Player.java:1667
public void giveExperienceLevels(int p_36276_)    // Player.java:1709
public int getXpNeededForNextLevel()              // Player.java:1728
```

— all in `net/minecraft/world/entity/player/Player.java`

> **NOT FOUND:** `setExperienceLevels(int)` and `setExperiencePoints(int)`. In 1.21.1 you must assign the **public fields** directly (`player.experienceLevel = n;`, `player.experienceProgress = p;`) — the setters that exist in later versions are absent.

XP is broadcast to the client from `ServerPlayer.doTick()` (~lines 578-611), guarded by `lastSentExp`, which is initialized to `-1` near `ServerPlayer.java:460`. Since that lives in `doTick()`, **XP packets are not sent unless the fake connection drives `doTick()`** — but the fields themselves are always readable server-side.

### 8.4 Abilities & game mode

```java
public enum GameType implements StringRepresentable {
    SURVIVAL(0, "survival"),
    CREATIVE(1, "creative"),
    ADVENTURE(2, "adventure"),
    SPECTATOR(3, "spectator");

    public static final GameType DEFAULT_MODE = SURVIVAL;
```
— `net/minecraft/world/level/GameType.java:10-17`

```java
public boolean isBlockPlacingRestricted() {
    return this == ADVENTURE || this == SPECTATOR;
}

public boolean isCreative() {
    return this == CREATIVE;
}

public boolean isSurvival() {
    return this == SURVIVAL || this == ADVENTURE;
}

public static GameType byId(int p_46394_)      // :81
public static GameType byName(String p_46401_) // :85
```
— `net/minecraft/world/level/GameType.java:73-87`

```java
public void updatePlayerAbilities(Abilities p_46399_)
```
— `net/minecraft/world/level/GameType.java:55-72` (sets `mayfly`/`instabuild`/`invulnerable`/`flying` per mode, then `mayBuild = !isBlockPlacingRestricted()`)

### 8.5 Adversarial / threat state

```java
public boolean hurt(DamageSource p_9037_, float p_9038_)   // ServerPlayer.java:768
public boolean hasLineOfSight(Entity p_147185_)            // LivingEntity.java:2969
```

```java
private int spawnInvulnerableTime = 60;
```
— `net/minecraft/server/level/ServerPlayer.java:192`

> **A freshly constructed bot is damage-immune for 60 ticks.** `ServerPlayer.hurt` returns `false` while `spawnInvulnerableTime > 0` (unless the source `BYPASSES_INVULNERABILITY`). This matters for observation tests — expect the first ~3 seconds of `hurt` attempts to no-op.

```java
if (this.invulnerableTime > 0) {
    this.invulnerableTime--;
}
```
— `net/minecraft/server/level/ServerPlayer.java:499-501`

> `LivingEntity` explicitly skips decaying `invulnerableTime` for `ServerPlayer` (`LivingEntity.java:467-468`); the `ServerPlayer` tick does it instead. **Both come from the `doTick()` chain** — with an undriven connection, i-frames never expire.

### HOW TO IMPLEMENT SERVER-SIDE — observation snapshot

```java
var snap = new Object() {
    float  health      = player.getHealth();
    float  maxHealth   = player.getMaxHealth();
    int    food        = player.getFoodData().getFoodLevel();
    float  saturation  = player.getFoodData().getSaturationLevel();
    int    air         = player.getAirSupply();
    int    maxAir      = player.getMaxAirSupply();
    double x = player.getX(), y = player.getY(), z = player.getZ();
    float  yaw = player.getYRot(), pitch = player.getXRot();
    Vec3   eye  = player.getEyePosition();
    Vec3   look = player.getLookAngle();
    var    effects = player.getActiveEffects();          // Collection<MobEffectInstance>
    GameType mode  = player.gameMode.getGameModeForPlayer();
    boolean onGround = player.onGround();
    boolean burning  = player.isOnFire();
    boolean inWater  = player.isInWater();
};
```

---

## 9. Raycasting / visibility

### 9.1 `ClipContext` — verbatim

```java
public class ClipContext {
    private final Vec3 from;
    private final Vec3 to;
    private final ClipContext.Block block;
    private final ClipContext.Fluid fluid;
    private final CollisionContext collisionContext;

    public ClipContext(Vec3 p_45688_, Vec3 p_45689_, ClipContext.Block p_45690_, ClipContext.Fluid p_45691_, Entity p_45692_) {
        this(p_45688_, p_45689_, p_45690_, p_45691_, CollisionContext.of(p_45692_));
    }

    public ClipContext(Vec3 p_311916_, Vec3 p_312802_, ClipContext.Block p_312540_, ClipContext.Fluid p_312487_, CollisionContext p_311823_) {
```
— `net/minecraft/world/level/ClipContext.java:16-33`

```java
public Vec3 getTo()          // :35
public Vec3 getFrom()        // :39
public VoxelShape getBlockShape(BlockState p_45695_, BlockGetter p_45696_, BlockPos p_45697_)   // :43
public VoxelShape getFluidShape(FluidState p_45699_, BlockGetter p_45700_, BlockPos p_45701_)   // :47
```

```java
public static enum Block implements ClipContext.ShapeGetter {
    COLLIDER(BlockBehaviour.BlockStateBase::getCollisionShape),
    OUTLINE(BlockBehaviour.BlockStateBase::getShape),
    VISUAL(BlockBehaviour.BlockStateBase::getVisualShape),
    FALLDAMAGE_RESETTING((p_201982_, p_201983_, p_201984_, p_201985_) -> p_201982_.is(BlockTags.FALL_DAMAGE_RESETTING) ? Shapes.block() : Shapes.empty());
```
— `net/minecraft/world/level/ClipContext.java:51-55`

```java
public static enum Fluid {
    NONE(p_45736_ -> false),
    SOURCE_ONLY(FluidState::isSource),
    ANY(p_45734_ -> !p_45734_.isEmpty()),
    WATER(p_201988_ -> p_201988_.is(FluidTags.WATER));
```
— `net/minecraft/world/level/ClipContext.java:69-73`

**Choosing the block shape:**
- `COLLIDER` — collision shapes. This is what **entity visibility / line-of-sight** uses and what the client uses for **block picking**.
- `OUTLINE` — the full outline shape; larger than `COLLIDER` for some blocks (e.g. fences, plants). Use when you want the "clickable" shape.
- `VISUAL` — the rendering/visual shape.
- `FALLDAMAGE_RESETTING` — special-purpose.

**Choosing the fluid shape:** `NONE` ignores fluids; `SOURCE_ONLY` only source blocks; `ANY` any non-empty fluid; `WATER` only water.

### 9.2 `Level.clip` — exact signature

```java
default BlockHitResult clip(ClipContext p_45548_) {
```
— `net/minecraft/world/level/BlockGetter.java:65`

> **NOT FOUND:** a `Level.clip(ClipContext, Predicate<BlockState>)` overload. `clip` is a **default method on `BlockGetter`** (which `Level` implements), not declared on `Level` itself. On a miss it returns `BlockHitResult.miss(...)` with `Direction.getNearest(...)`.

### 9.3 `BlockHitResult` / `EntityHitResult` / `HitResult`

```java
public static BlockHitResult miss(Vec3 p_82427_, Direction p_82428_, BlockPos p_82429_)   // :12
public BlockHitResult(Vec3 p_82415_, Direction p_82416_, BlockPos p_82417_, boolean p_82418_)   // :16
public BlockHitResult withDirection(Direction p_82433_)   // :28
public BlockHitResult withPosition(BlockPos p_82431_)     // :32
```
— `net/minecraft/world/phys/BlockHitResult.java`

```java
public enum Type {
    MISS,
    BLOCK,
    ENTITY;
```
— `net/minecraft/world/phys/HitResult.java:26-28`

```java
public double distanceTo(Entity p_82449_)
```
— `net/minecraft/world/phys/HitResult.java:12`

```java
public EntityHitResult(Entity p_82439_) {
    this(p_82439_, p_82439_.position());
}

public EntityHitResult(Entity p_82441_, Vec3 p_82442_) {
    super(p_82442_);
    this.entity = p_82441_;
}

public Entity getEntity() {
    return this.entity;
}
```
— `net/minecraft/world/phys/EntityHitResult.java:8-19`

### 9.4 `AABB` — constructors and key methods

```java
public AABB(double p_82295_, double p_82296_, double p_82297_, double p_82298_, double p_82299_, double p_82300_)   // :21
public AABB(BlockPos p_82305_)     // :30
public AABB(Vec3 p_82302_, Vec3 p_82303_)   // :41
public static AABB of(BoundingBox p_82322_)   // :45
public static AABB ofSize(Vec3 p_165883_, double p_165884_, double p_165885_, double p_165886_)   // :523
```
— `net/minecraft/world/phys/AABB.java`

> The two-`Vec3` form `new AABB(Vec3 min, Vec3 max)` is the one you asked about and **does** exist (`AABB.java:41`). `AABB.ofSize(Vec3 center, double xSize, double ySize, double zSize)` also exists (`:523`).

### 9.5 Entity visibility — the canonical helper

```java
public boolean hasLineOfSight(Entity p_147185_) {
    if (p_147185_.level() != this.level()) {
        return false;
    } else {
        Vec3 vec3 = new Vec3(this.getX(), this.getEyeY(), this.getZ());
        Vec3 vec31 = new Vec3(p_147185_.getX(), p_147185_.getEyeY(), p_147185_.getZ());
        return vec31.distanceTo(vec3) > 128.0
            ? false
            : this.level().clip(new ClipContext(vec3, vec31, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }
}
```
— `net/minecraft/world/entity/LivingEntity.java:2969-2979`

> **This is exactly the "can the bot see X" helper you want**, and it reveals the canonical visibility raycast: **eye-to-eye, `Block.COLLIDER`, `Fluid.NONE`, max 128 blocks, visible iff `Type.MISS`.** Declared on **`LivingEntity`** (not `Entity`), so a `ServerPlayer` has it.

### 9.6 Entity raycast helper (the projectile-style pick)

```java
public static EntityHitResult getEntityHitResult(Entity p_37288_, Vec3 p_37289_, Vec3 p_37290_, AABB p_37291_, Predicate<Entity> p_37292_, double p_37293_)
```
— `net/minecraft/world/entity/projectile/ProjectileUtil.java:65`

```java
public static EntityHitResult getEntityHitResult(Level p_37305_, Entity p_37306_, Vec3 p_37307_, Vec3 p_37308_, AABB p_37309_, Predicate<Entity> p_37310_)
```
— `net/minecraft/world/entity/projectile/ProjectileUtil.java:102`

```java
@Nullable
public static EntityHitResult getEntityHitResult(Level p_37305_, Entity p_37306_, Vec3 p_37307_, Vec3 p_37308_, AABB p_37309_, Predicate<Entity> p_37310_) {
    return getEntityHitResult(p_37305_, p_37306_, p_37307_, p_37308_, p_37309_, p_37310_, 0.3F);
}
```
— `net/minecraft/world/entity/projectile/ProjectileUtil.java:101-104`

```java
@Nullable
public static EntityHitResult getEntityHitResult(
    Level p_150176_, Entity p_150177_, Vec3 p_150178_, Vec3 p_150179_, AABB p_150180_, Predicate<Entity> p_150181_, float p_150182_
)
```
— `net/minecraft/world/entity/projectile/ProjectileUtil.java:106-109`

The 7-arg form is the real implementation: it iterates `p_150176_.getEntities(p_150177_, p_150180_, p_150181_)`, inflates each candidate's AABB by `p_150182_` (default `0.3F`), clips against it, and keeps the nearest hit. **Note the first entity argument to `getEntities` is the querying entity — that is how the shooter is excluded** (`ProjectileUtil.java:115`).

```java
public boolean isPickable()          // Entity.java:1610
public boolean isSpectator()         // Entity.java:286
```
— `net/minecraft/world/entity/Entity.java`

### 9.7 Entity lookup

```java
default <T extends Entity> List<T> getEntitiesOfClass(Class<T> p_45979_, AABB p_45980_, Predicate<? super T> p_45981_)
```
— `net/minecraft/world/level/EntityGetter.java:26`

The underlying non-default method on `Level`:

```java
@Override
public List<Entity> getEntities(@Nullable Entity p_46536_, AABB p_46537_, Predicate<? super Entity> p_46538_)
```
— `net/minecraft/world/level/Level.java:863`

> The first parameter being the **querying entity** lets the level exclude it (and, in NeoForge, include `PartEntity` parts). Pass `player` to exclude the bot itself.

```java
public boolean canInteractWithEntity(Entity p_320327_, double p_320632_)
public boolean canInteractWithEntity(AABB p_320959_, double p_319981_)
public boolean canInteractWithBlock(BlockPos p_319804_, double p_320349_)
public double blockInteractionRange()
public double entityInteractionRange()
```
— `net/minecraft/world/entity/player/Player.java:2220, 2224, 2229, 2212, 2216`

Defaults: `ENTITY_INTERACTION_RANGE` = 3.0, `BLOCK_INTERACTION_RANGE` = 4.5.

### 9.8 `Vec3`

```java
public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);   // :21
public static Vec3 atCenterOf(Vec3i p_82513_)              // :41
public static Vec3 atBottomCenterOf(Vec3i p_82540_)        // :45
public Vec3 subtract(Vec3 p_82547_)                        // :80
public Vec3 subtract(double p_82493_, double p_82494_, double p_82495_)   // :84
public Vec3 add(Vec3 p_82550_)                             // :88
public Vec3 add(double p_82521_, double p_82522_, double p_82523_)        // :92
public double distanceTo(Vec3 p_82555_)                    // :100
public double distanceToSqr(Vec3 p_82558_)                 // :107
public double distanceToSqr(double p_82532_, double p_82533_, double p_82534_)   // :114
```
— `net/minecraft/world/phys/Vec3.java`

### HOW TO IMPLEMENT SERVER-SIDE — raycasting

```java
// --- BLOCK TARGET (what a real client picks, using COLLIDER + NONE and blockInteractionRange) ---
Vec3 from = player.getEyePosition(1.0F);
Vec3 to   = from.add(player.getLookAngle().scale(player.blockInteractionRange()));

BlockHitResult blockHit = player.level().clip(
        new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

if (blockHit.getType() == HitResult.Type.BLOCK) {
    BlockPos     hitPos = blockHit.getBlockPos();
    Direction    face   = blockHit.getDirection();
    Vec3         exact  = blockHit.getLocation();
    // For placement, the new block goes at hitPos.relative(face).
}

// --- ENTITY TARGET within reach ---
double reach = player.entityInteractionRange();
Vec3 eFrom = player.getEyePosition(1.0F);
Vec3 eTo   = eFrom.add(player.getLookAngle().scale(reach));
AABB searchBox = player.getBoundingBox().expandTowards(eTo.subtract(eFrom)).inflate(1.0);

EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
        player.level(), player, eFrom, eTo, searchBox,
        e -> !e.isSpectator() && e.isPickable());     // both confirmed: Entity.java:286, :1610

// --- SIMPLE LINE OF SIGHT ("can the bot see X?") ---
boolean canSee = player.hasLineOfSight(entity);       // LivingEntity.java:2969; COLLIDER / NONE / 128 blocks

// --- BUILDING AN AABB FROM TWO POINTS ---
AABB box = new AABB(vec3A, vec3B);                    // min / max corners (AABB.java:41)
AABB sized = AABB.ofSize(center, 2.0, 2.0, 2.0);      // (AABB.java:523)

// --- FINDING ENTITIES IN A BOX ---
List<LivingEntity> nearby = player.level().getEntitiesOfClass(
        LivingEntity.class, player.getBoundingBox().inflate(16.0), e -> e != player);
```

> **Uncertainty flagged:** the exact predicate a real client uses for entity picking (`e.isPickable()` / `!e.isSpectator()`) is my reconstruction of the conventional client filter — I confirmed both `isPickable()` (`Entity.java:1610`) and `isSpectator()` (`Entity.java:286`) exist, but did **not** read `Minecraft.pick`/`GameRenderer` to quote the client's literal lambda. The three `ProjectileUtil.getEntityHitResult` overloads are now fully verified (lines 65, 101-104, 106-109).

---

## 10. Summary of corrections to the task's premises

These are places where the API described in the brief does **not** match MC 1.21.1 / NeoForge 21.1.248:

| Premise in brief | Reality in this tree |
|---|---|
| `tick()` completes timed breaking after `START_DESTROY_BLOCK` | **False** — `isDestroyingBlock` branch never calls `destroyBlock`. Needs `STOP_DESTROY_BLOCK` (or `destroyBlock` directly). |
| `ServerPlayerGameMode.startDestroyBlock/continueDestroyBlock/stopDestroyBlock` | **NOT FOUND** — client-only (`MultiPlayerGameMode`). |
| `ServerPlayerGameMode.destroyProgress` field | **NOT FOUND** — client-only (`MultiPlayerGameMode.java:70`). |
| `Player.useItemOn(ItemStack, InteractionHand, BlockHitResult)` | **NOT FOUND on `Player`** — it's `ServerPlayerGameMode.useItemOn(ServerPlayer, Level, ItemStack, InteractionHand, BlockHitResult)`. |
| `Player.useItem(...)` | **NOT FOUND on `Player`** — it's `ServerPlayerGameMode.useItem(ServerPlayer, Level, ItemStack, InteractionHand)`. |
| `InteractionResult.SUCCESS_SERVER` | **NOT FOUND** — that's 1.21.2+. Set is `SUCCESS, SUCCESS_NO_ITEM_USED, CONSUME, CONSUME_PARTIAL, PASS, FAIL`. |
| `BlockHitResult` 5-arg ctor | **NOT FOUND** — only the 4-arg ctor + `miss(...)`. |
| `Player.openMenu(MenuProvider, Consumer<FriendlyByteBuf>)` | **NOT FOUND** — NeoForge uses `Consumer<RegistryFriendlyByteBuf>`. |
| `AbstractContainerMenu.SUCCESS` | **NOT FOUND** — only `SLOT_CLICKED_OUTSIDE`. |
| `ChestMenu(int, Inventory, Container, int)` | **NOT FOUND** — public ctor is `ChestMenu(MenuType<?>, int, Inventory, Container, int)`. |
| `ChestMenu.nineRows(...)` | **NOT FOUND** — `oneRow`…`sixRows` only. |
| `AbstractContainerMenu.updateData(...)` | **NOT FOUND** — it is `setData(int, int)`. |
| `Entity.hurtServer(ServerLevel, DamageSource, float)` | **NOT FOUND** — 1.21.2+. Use `hurt(DamageSource, float)`. |
| `Entity.isCrit` | **NOT FOUND** — only NeoForge `CriticalHitEvent.isCriticalHit()`. |
| `Inventory.getSelectedSlot()` / `setSelectedSlot(int)` | **NOT FOUND** — use the public `Inventory.selected` field + `getSelected()`. |
| `Player.setExperienceLevels(int)` / `setExperiencePoints(int)` | **NOT FOUND** — assign the public fields `experienceLevel` / `experienceProgress` directly. |
| `PlayerList.broadcastChatMessage` needs signing | **False** — `PlayerChatMessage.unsigned(UUID, String)` broadcasts fine; only logs "Not Secure". |
| `ClipContext.Fluid` options `NONE/SOURCE_ONLY/ANY` | Correct, **plus** an extra `WATER`. |
| `Level.clip(ClipContext)` declared on `Level` | It's a **default method on `BlockGetter`** (which `Level` implements). |
| `Player.getEyePosition()` / `getViewVector(float)` | Exist and work, but are **`final` on `Entity`** and **interpolate against `xo/yo/zo/xRotO/yRotO`** — a fake connection must maintain those. |
| `ServerPlayer.connection` | `public ServerGamePacketListenerImpl connection;` — public, **not final**, assigned externally. |
| NeoForge `FakePlayer` usable as a base | **No** — it no-ops `openMenu` and `tick()`. |

---

## 11. Remaining uncertainties (explicit)

1. **Keep-alive / disconnect behaviour** of a custom `ServerGamePacketListenerImpl` (§0) — read from source only, not verified at runtime.
2. **NeoForge `Level.getCapability` / `Capabilities.ItemHandler.BLOCK`** (§3.8) — the capability exists and I confirmed `ChestBlock.getContainer` is what NeoForge's chest hook uses, but I did not quote the exact `Level.getCapability` signature in this pass.
3. **`ItemInteractionResult` full constant list** (§2.3) — only `PASS_TO_DEFAULT_BLOCK_INTERACTION` was confirmed verbatim (from `ServerPlayerGameMode.java:369`).
4. **Client entity-pick predicate** (§9.8) — see the note there; the helper signatures are verified, the client's literal lambda is not.
5. **Client rendering of an unsigned `ClientboundPlayerChatPacket`** (§6.6) — the server-side delivery path is confirmed from source; client display behaviour was not runtime-verified.
6. **Mod-provided commands** (`/home`, `/spawn`) may declare `LEVEL_ALL`, defeating the "non-OP therefore denied" reasoning — verify per mod.
7. **`CommandSourceStack.withSuppressedOutput()`** (§7.6) — exists at `CommandSourceStack.java:236`, but I did not read its body/signature in full.
8. No build was run and nothing was executed; **all line numbers are from static reading** of the decompiled sources. A concurrent gradle build was in progress, so no compilation of the sketches in this document has been performed.
