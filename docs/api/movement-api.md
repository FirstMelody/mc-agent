# Server-Side Movement Control of a `ServerPlayer` — Exact API Contract

Target: Minecraft **1.21.1** + **NeoForge 21.1.248** (decompiled sources).
Source root: `/ymtc/Repos/.mcai-scratch/mcsrc/`
All signatures/field declarations below are **copied verbatim** from those sources, with file:line.
Anything inferred rather than copied is marked **[INFERRED]** or **[UNCERTAIN]**.

---

## 0. TL;DR — the three findings that decide the design

1. ✅ **Server-set `zza`/`xxa` DO move a `ServerPlayer`.** `ServerPlayer.doTick()` calls
   `Player.tick()` → `LivingEntity.tick()` → `aiStep()` → `travel(vec31)`, and
   `travel()` reads `this.xxa/this.yya/this.zza`. This runs **every tick on the dedicated
   server**, driven by `ServerGamePacketListenerImpl.tick()`.
2. ❌ **BUT `ServerGamePacketListenerImpl.tick()` TELEPORTS THE PLAYER BACK TO
   `firstGoodX/Y/Z` after `doTick()`.** This is vanilla code
   (`ServerGamePacketListenerImpl.java:261`), not a client-authority check:
   ```java
   this.player.doTick();
   this.player.absMoveTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
   ```
   and `firstGoodX/Y/Z` are refreshed from the player's *current* position at the **top of
   every tick** (`resetPosition()` at line 256). Net effect for a purely server-driven player:
   **all physical displacement produced by `travel()` is discarded and the player is snapped
   back to wherever it was at the start of the tick. The bot will never walk.**
   `ServerPlayer.tick()` (the non-`do` variant, line 495) does *not* tick physics at all.
   See §6.4 — this is the single most important obstacle in this API.
3. ❌ **`ServerPlayer.setPlayerInput(...)` is a no-op for a non-passenger.** It only assigns
   `xxa`/`zza`/`jumping`/`shiftKeyDown` inside `if (this.isPassenger())`
   (`ServerPlayer.java:1220-1233`). Do **not** use it as the driver; write the fields directly
   (they are `public`).

**Consequence for the design:** we must supply our own `ServerGamePacketListenerImpl` subclass
that keeps the vanilla physics tick but suppresses the `absMoveTo` snap-back (and the rotation
reset that comes with it), or bypass `doTick()` entirely. Full analysis in §6 and §7; concrete
sketch in §9.

---

## 1. Input fields `zza`, `xxa`, `yya`

Declared in `net/minecraft/world/entity/LivingEntity.java:217-220`:

```java
    protected boolean jumping;
    public float xxa;
    public float yya;
    public float zza;
```

| Field | Type | Access | Line | Default | Settable from server code? |
|---|---|---|---|---|---|
| `xxa` | `float` | `public` | `LivingEntity.java:218` | `0.0F` (Java default) | ✅ yes, directly |
| `yya` | `float` | `public` | `LivingEntity.java:219` | `0.0F` | ✅ yes, directly |
| `zza` | `float` | `public` | `LivingEntity.java:220` | `0.0F` | ✅ yes, directly |
| `jumping` | `boolean` | `protected` | `LivingEntity.java:217` | `false` | ✅ via `setJumping(boolean)` (public) |
| `noJumpDelay` | `int` | `private` | `LivingEntity.java:237` | `0` | ❌ **private — not settable at all** |

Semantics (all `[INFERRED]` from the vanilla call sites, none of them are guessed API):
- `zza` = forward impulse (positive = forward), `xxa` = left/strafe impulse, `yya` = vertical
  impulse (only ever nonzero for mobs; see `Mob.setYya` below).
- Vanilla settles them toward zero **once per tick** in `LivingEntity.aiStep()`:
  ```java
        this.xxa *= 0.98F;      // LivingEntity.java:2745
        this.zza *= 0.98F;      // LivingEntity.java:2746
  ```
  so a driver must **re-write them every tick**.

Related setters that exist but are on `Mob`, **not** on `Player` (`Mob.java:532-542`):
```java
    public void setZza(float p_21565_) {
        this.zza = p_21565_;
    }

    public void setYya(float p_21568_) {
        this.yya = p_21568_;
    }

    public void setXxa(float p_21571_) {
        this.xxa = p_21571_;
    }
```
`Player`/`ServerPlayer` have **no** such wrappers ([verified by grep: no `setZza`/`setXxa`/
`setYya` in `Player.java` or `ServerPlayer.java`]) — assign the public fields directly.

### 1.1 The only vanilla "setter" and why you must not use it

`ServerPlayer.java:1220-1233` (verbatim):
```java
    public void setPlayerInput(float p_8981_, float p_8982_, boolean p_8983_, boolean p_8984_) {
        if (this.isPassenger()) {
            if (p_8981_ >= -1.0F && p_8981_ <= 1.0F) {
                this.xxa = p_8981_;
            }

            if (p_8982_ >= -1.0F && p_8982_ <= 1.0F) {
                this.zza = p_8982_;
            }

            this.jumping = p_8983_;
            this.setShiftKeyDown(p_8984_);
        }
    }
```
Its only caller is the packet handler `ServerGamePacketListenerImpl.java:371-374`:
```java
    @Override
    public void handlePlayerInput(ServerboundPlayerInputPacket p_9893_) {
        PacketUtils.ensureRunningOnSameThread(p_9893_, this, this.player.serverLevel());
        this.player.setPlayerInput(p_9893_.getXxa(), p_9893_.getZza(), p_9893_.isJumping(), p_9893_.isShiftKeyDown());
    }
```
Note the guard `if (this.isPassenger())` in **both** places — vanilla only ever applies
`ServerboundPlayerInputPacket` while riding, because for a walking player the input is already
carried by `ServerboundMovePlayerPacket`. **A fake connection never sends either packet, so
neither path fires for us** — which is good (nothing fights us) but also means we get no free
input plumbing.

---

## 2. `travel(Vec3)` and the full tick → travel call chain

### 2.1 `LivingEntity.travel` — exact signature

`LivingEntity.java:2161`:
```java
    public void travel(Vec3 p_21280_) {
        if (this.isControlledByLocalInstance()) {
            ...
        }

        this.calculateEntityAnimation(this instanceof FlyingAnimal);
    }
```

The whole body is guarded by `isControlledByLocalInstance()`. For a server-side player:

`Entity.java:3050-3056` (verbatim):
```java
    public boolean isControlledByLocalInstance() {
        return this.getControllingPassenger() instanceof Player player ? player.isLocalPlayer() : this.isEffectiveAi();
    }

    public boolean isEffectiveAi() {
        return !this.level().isClientSide;
    }
```
- `Player.java:1421-1423`: `public boolean isLocalPlayer() { return false; }` — **not overridden
  by `ServerPlayer`** (only `LocalPlayer.java:363` overrides it to `true`).
- `getControllingPassenger()` is **not overridden by `Player`/`ServerPlayer`**
  ([verified by grep]) and `Entity.java:2970-2972` returns `null` for a plain entity.
- ⇒ On the dedicated server: `isControlledByLocalInstance() == true` and
  `isEffectiveAi() == true`. **`travel()` body runs.**
- ⚠️ **Caveat [VERIFIED]:** if the bot is riding a `Boat`/`AbstractHorse`, those override
  `getControllingPassenger()` (`Boat.java:885`, `AbstractHorse.java:1049`) and return the
  passenger `Player`; `isControlledByLocalInstance()` then evaluates `player.isLocalPlayer()`
  which is `false` for our bot, so its own `travel()` is skipped. Irrelevant while walking;
  relevant if we ever make the bot ride.

The ground branch that consumes the inputs, `LivingEntity.java:2264-2284`:
```java
            } else {
                BlockPos blockpos = this.getBlockPosBelowThatAffectsMyMovement();
                float f2 = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getFriction(level(), this.getBlockPosBelowThatAffectsMyMovement(), this);
                float f3 = this.onGround() ? f2 * 0.91F : 0.91F;
                Vec3 vec35 = this.handleRelativeFrictionAndCalculateMovement(p_21280_, f2);
```
and `LivingEntity.java:2325-2336`:
```java
    public Vec3 handleRelativeFrictionAndCalculateMovement(Vec3 p_21075_, float p_21076_) {
        this.moveRelative(this.getFrictionInfluencedSpeed(p_21076_), p_21075_);
        this.setDeltaMovement(this.handleOnClimbable(this.getDeltaMovement()));
        this.move(MoverType.SELF, this.getDeltaMovement());
        Vec3 vec3 = this.getDeltaMovement();
```
and `Entity.java:1379-1394` — **the exact place where `xxa/zza` become world-space motion**:
```java
    public void moveRelative(float p_19921_, Vec3 p_19922_) {
        Vec3 vec3 = getInputVector(p_19922_, p_19921_, this.getYRot());
        this.setDeltaMovement(this.getDeltaMovement().add(vec3));
    }

    private static Vec3 getInputVector(Vec3 p_20016_, float p_20017_, float p_20018_) {
        double d0 = p_20016_.lengthSqr();
        if (d0 < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            Vec3 vec3 = (d0 > 1.0 ? p_20016_.normalize() : p_20016_).scale((double)p_20017_);
            float f = Mth.sin(p_20018_ * (float) (Math.PI / 180.0));
            float f1 = Mth.cos(p_20018_ * (float) (Math.PI / 180.0));
            return new Vec3(vec3.x * (double)f1 - vec3.z * (double)f, vec3.y, vec3.z * (double)f1 + vec3.x * (double)f);
        }
    }
```
`p_19921_` = speed, `p_20018_` = **`this.getYRot()`**. `LivingEntity.java:2370-2372`:
```java
    private float getFrictionInfluencedSpeed(float p_21331_) {
        return this.onGround() ? this.getSpeed() * (0.21600002F / (p_21331_ * p_21331_ * p_21331_)) : this.getFlyingSpeed();
    }
```
Note `getInputVector` **normalises** the `(xxa, yya, zza)` vector when its length² > 1, so
diagonal input is not faster than straight input — exactly like a real client.

`getSpeed()` for a `Player` is an **attribute lookup, not a field** (`Player.java:1562-1565`):
```java
    @Override
    public float getSpeed() {
        return (float)this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }
```
so the bot walks at whatever `Attributes.MOVEMENT_SPEED` is (player default `0.1F`,
`Player.java:228`), including the sprint modifier.

### 2.2 `ServerPlayer.travel` override (**your cited line 1236 region**)

`ServerPlayer.java:1235-1242` (verbatim, exactly as in source):
```java
    @Override
    public void travel(Vec3 p_308985_) {
        double d0 = this.getX();
        double d1 = this.getY();
        double d2 = this.getZ();
        super.travel(p_308985_);
        this.checkMovementStatistics(this.getX() - d0, this.getY() - d1, this.getZ() - d2);
    }
```
📍 **The line number you gave (`ServerPlayer.java:1236`, "region") is the `@Override`/
`public void travel(...)` boundary:** line 1235 is `@Override`, **1236** is
`public void travel(Vec3 p_308985_) {`, 1240 is `super.travel(p_308985_);`. It only adds
statistics bookkeeping — **no input modification, no early return**.

`Player.travel` (`Player.java:1524-1547`) sits in between:
```java
    @Override
    public void travel(Vec3 p_36359_) {
        if (this.isSwimming() && !this.isPassenger()) {
            double d0 = this.getLookAngle().y;
            double d1 = d0 < -0.2 ? 0.085 : 0.06;
            if (d0 <= 0.0
                || this.jumping
                || !this.level().getBlockState(BlockPos.containing(this.getX(), this.getY() + 1.0 - 0.1, this.getZ())).getFluidState().isEmpty()) {
                Vec3 vec3 = this.getDeltaMovement();
                this.setDeltaMovement(vec3.add(0.0, (d0 - vec3.y) * d1, 0.0));
            }
        }

        if (this.abilities.flying && !this.isPassenger()) {
            double d2 = this.getDeltaMovement().y;
            super.travel(p_36359_);
            Vec3 vec31 = this.getDeltaMovement();
            this.setDeltaMovement(vec31.x, d2 * 0.6, vec31.z);
            this.resetFallDistance();
            this.setSharedFlag(7, false);
        } else {
            super.travel(p_36359_);
        }
    }
```
Chain: `ServerPlayer.travel` → `Player.travel` → `LivingEntity.travel`.
⚠️ Flying branch: `Player.travel` **restores the pre-travel `y`** (`vec31.x, d2 * 0.6, vec31.z`),
so while `abilities.flying` the vertical motion comes from `LivingEntity.aiStep`/elsewhere —
not from `travel`. **`yya` will not make you fly** (§5.5).

### 2.3 Full call chain `Entity.tick()` → … → `travel()`

```
MinecraftServer.tickChildren(BooleanSupplier)                     // MinecraftServer.java:1018
 └─ this.getConnection().tick()                                   // :1051
     └─ ServerConnectionListener.tick()                           // ServerConnectionListener.java:151
         └─ connection.tick()                                       // :159  (for each Connection)
             └─ Connection.tick()                                 // Connection.java:409
                 └─ tickablepacketlistener.tick()                  // :412
                     └─ ServerGamePacketListenerImpl.tick()        // ServerGamePacketListenerImpl.java:250
                         ├─ this.resetPosition()                   // :256   ← firstGood*/lastGood* = CURRENT pos
                         ├─ player.xo/yo/zo = player.getX/Y/Z()    // :257-259
                         ├─ this.player.doTick()                   // :260   ★ the real physics tick
                         │   └─ ServerPlayer.doTick()              // ServerPlayer.java:553
                         │       └─ super.tick()  →  Player.tick() // (bytecode: invokespecial Player.tick)
                         │           └─ Player.tick()              // Player.java:253
                         │               ├─ EventHooks.firePlayerTickPre(this)   // :254
                         │               ├─ super.tick()           // :281 → LivingEntity.tick()
                         │               │   └─ LivingEntity.tick()   // LivingEntity.java:2392
                         │               │       ├─ super.tick()      // :2393 → Entity.tick()
                         │               │       │   └─ Entity.tick() // Entity.java:424
                         │               │       │       └─ Entity.baseTick()  // :428
                         │               │       │           ├─ xRotO/yRotO = getXRot/getYRot()  // :440-441
                         │               │       │           └─ updateSwimming()                 // :451
                         │               │       └─ this.aiStep()      // :2432  ★★ if (!this.isRemoved())
                         │               │           └─ LivingEntity.aiStep()   // :2660
                         │               │               ├─ noJumpDelay-- (if > 0)             // :2661-2663
                         │               │               ├─ serverAiStep()                   // :2706
                         │               │               │   └─ Player.serverAiStep()         // Player.java:520
                         │               │               │       ├─ super.serverAiStep()       // LivingEntity.java:2827
                         │               │               │       ├─ updateSwingTime()          // :522
                         │               │               │       └─ this.yHeadRot = this.getYRot()  // :523  ★
                         │               │               ├─ jump block (reads this.jumping, noJumpDelay)  // :2712-2741
                         │               │               ├─ this.xxa *= 0.98F; this.zza *= 0.98F;  // :2745-2746
                         │               │               ├─ Vec3 vec31 = new Vec3(xxa, yya, zza) // :2749
                         │               │               └─ this.travel(vec31)        // :2760  ★★★
                         │               │                   └─ ServerPlayer.travel → Player.travel → LivingEntity.travel
                         │               │                       └─ moveRelative(getSpeed()*k, (xxa,yya,zza))  // Entity.java:1379
                         │               │                           └─ getInputVector(..., this.getYRot()) → deltaMovement
                         │               │                       └─ move(MoverType.SELF, deltaMovement)      // LivingEntity.java:2328
                         │               ├─ this.updatePlayerPose()    // Player.java:328
                         │               └─ EventHooks.firePlayerTickPost(this)   // :332
                         └─ player.absMoveTo(firstGoodX, firstGoodY, firstGoodZ, yRot, xRot)  // :261  ⛔ SNAP-BACK
```

Key de-duplication note: `ServerPlayer` has **two** tick entry points and they are different:
- `ServerPlayer.tick()` (`ServerPlayer.java:495`) — menu/gameMode/camera bookkeeping, **no physics**.
- `ServerPlayer.doTick()` (`ServerPlayer.java:553`) — the one that calls `super.tick()` and thus runs physics.
  Bytecode-verified: `doTick` bytecode's first `invokespecial` is `Player.tick:()V`; `ServerPlayer.tick()` bytecode contains no `invokespecial` to a superclass `tick`.
- `doTick()` has exactly **one** caller in the whole tree: `ServerGamePacketListenerImpl.java:260`
  ([verified by repo-wide grep for `.doTick()`]). NeoForge documents this too
  (`PlayerTickEvent.java` javadoc: *"on the server, they rely on `ServerPlayer#doTick()` which is
  called from `ServerGamePacketListenerImpl#tick()`"*).

### 2.4 Which method consumes the inputs

**`Entity.moveRelative(float, Vec3)` called from `LivingEntity.handleRelativeFrictionAndCalculateMovement`
called from `LivingEntity.travel`** is the consumer. `LivingEntity.aiStep` (`:2745-2760`) is what
packs the fields into `Vec3 vec31 = new Vec3((double)this.xxa, (double)this.yya, (double)this.zza)`.
`moveRelative` is also called directly inside the water/lava branches of `travel`
(`LivingEntity.java:2190`, `:2206`) with a **hard-coded** speed (`f5`, `0.02F`), so swimming
also works from the same inputs.

---

## 3. Jumping

### 3.1 `jumpFromGround()`

`LivingEntity.java:2122-2136` (verbatim):
```java
    @VisibleForTesting
    public void jumpFromGround() {
        float f = this.getJumpPower();
        if (!(f <= 1.0E-5F)) {
            Vec3 vec3 = this.getDeltaMovement();
            this.setDeltaMovement(vec3.x, (double)f, vec3.z);
            if (this.isSprinting()) {
                float f1 = this.getYRot() * (float) (Math.PI / 180.0);
                this.addDeltaMovement(new Vec3((double)(-Mth.sin(f1)) * 0.2, 0.0, (double)Mth.cos(f1) * 0.2));
            }

            this.hasImpulse = true;
            net.neoforged.neoforge.common.CommonHooks.onLivingJump(this);
        }
    }
```
It is **`public`** and safe to call directly from server code. `Player` overrides it
(`Player.java:1513-1522`) to add the `Stats.JUMP` stat and hunger exhaustion
(`0.2F` if sprinting else `0.05F`).

`getJumpPower` (`LivingEntity.java:2110-2120`):
```java
    protected float getJumpPower() {
        return this.getJumpPower(1.0F);
    }

    protected float getJumpPower(float p_326107_) {
        return (float)this.getAttributeValue(Attributes.JUMP_STRENGTH) * p_326107_ * this.getBlockJumpFactor() + this.getJumpBoostPower();
    }
```
`Attributes.JUMP_STRENGTH` default = `0.42F` (`Attributes.java:53-55`), so a normal bot jump
gives `deltaMovement.y = 0.42` — the same as a vanilla player.

⚠️ **`jumpFromGround()` sets `y`, it does not check `onGround()`.** Calling it mid-air will
overwrite your `deltaMovement.y`. **Gate it yourself** on `bot.onGround()` (or on the
fluid-jump conditions) unless you deliberately want a double jump.

### 3.2 `setJumping(boolean)` and the `jumping` field

`LivingEntity.java:217`: `protected boolean jumping;`
`LivingEntity.java:2950-2952` (verbatim):
```java
    public void setJumping(boolean p_21314_) {
        this.jumping = p_21314_;
    }
```
`Player` does **not** override it. Settable from server code via the public setter. Vanilla
`ServerPlayer` writes the field directly (`ServerPlayer.java:1230`).

### 3.3 `noJumpDelay` and the automatic jump path

`LivingEntity.java:237`: `private int noJumpDelay;` — **private, no getter, no setter**
([verified by grep: no `getNoJumpDelay`/`setNoJumpDelay` exist]).

`LivingEntity.aiStep()` — decrement and consumption, `LivingEntity.java:2660-2663` and
`:2711-2741` (verbatim, NeoForge-patched):
```java
    public void aiStep() {
        if (this.noJumpDelay > 0) {
            this.noJumpDelay--;
        }
        ...
        this.level().getProfiler().push("jump");
        if (this.jumping && this.isAffectedByFluids()) {
            double d3;
            net.neoforged.neoforge.fluids.FluidType fluidType = this.getMaxHeightFluidType();
            if (!fluidType.isAir()) d3 = this.getFluidTypeHeight(fluidType);
            else
            if (this.isInLava()) {
                d3 = this.getFluidHeight(FluidTags.LAVA);
            } else {
                d3 = this.getFluidHeight(FluidTags.WATER);
            }

            boolean flag = this.isInWater() && d3 > 0.0;
            double d4 = this.getFluidJumpThreshold();
            if (!flag || this.onGround() && !(d3 > d4)) {
                if (!this.isInLava() || this.onGround() && !(d3 > d4)) {
                    if (fluidType.isAir() || this.onGround() && !(d3 > d4)) {
                    if ((this.onGround() || flag && d3 <= d4) && this.noJumpDelay == 0) {
                        this.jumpFromGround();
                        this.noJumpDelay = 10;
                    }
                    } else this.jumpInFluid(fluidType);
                } else {
                    this.jumpInFluid(net.neoforged.neoforge.common.NeoForgeMod.LAVA_TYPE.value());
                }
            } else {
                this.jumpInFluid(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value());
            }
        } else {
            this.noJumpDelay = 0;
        }
```

Exact semantics:
- `jumping == true` + `onGround()` + `noJumpDelay == 0` ⇒ `jumpFromGround()` and `noJumpDelay = 10`.
- `jumping == false` ⇒ **`noJumpDelay = 0`** (`:2740`), i.e. the cooldown is cleared as soon as
  the key is released; it is *not* a hard 10-tick lock, it is a "hold-space auto-rejump" damper.
- `noJumpDelay--` at the top of `aiStep` while > 0.

**Answer to "do we need `noJumpDelay = 0`?" — NO, and we can't anyway.**
- It is `private` with no accessor, so a mod cannot legally write it
  (only reflection/AT/Mixin).
- We don't need to: **`setJumping(false)` on any non-adjacent tick already zeroes it**.
- [UNCERTAIN / untested] If you keep `jumping = true` continuously, vanilla auto-rejumps every
  10 ticks — that is normal vanilla "hold space" behaviour, and a driver that sets
  `setJumping(true)` for one tick then `false` gets a single clean jump.
- `isAffectedByFluids()` is `!this.abilities.flying` for a `Player` (`Player.java:1106-1108`), so a
  flying bot never enters the jump block at all.

### 3.4 Minimum reliable jump recipe

```java
if (bot.onGround()) {           // your own gate; jumpFromGround does not check it
    bot.jumpFromGround();       // direct, deterministic, public
}
```
or the "vanilla-shaped" version:
```java
bot.setJumping(true);           // consumed by aiStep on the NEXT tick boundary
// ... next tick:
bot.setJumping(false);          // also clears noJumpDelay
```
⚠️ **Ordering matters:** the jump block runs *inside* `aiStep()`, so a flag set during a
`PlayerTickEvent.Pre` handler is consumed the same tick; a flag set from a
`ServerTickEvent.Post` / `PlayerTickEvent.Post` handler is consumed the next tick.

---

## 4. Yaw / pitch / head rotation

### 4.1 Field declarations

`Entity.java:168-171` (verbatim):
```java
    private float yRot;
    private float xRot;
    public float yRotO;
    public float xRotO;
```
`LivingEntity.java:201-204` (verbatim):
```java
    public float yBodyRot;
    public float yBodyRotO;
    public float yHeadRot;
    public float yHeadRotO;
```

### 4.2 Accessors

| API | Declaration | File:line |
|---|---|---|
| `public float getYRot()` | returns `this.yRot` | `Entity.java:3424-3426` |
| `public void setYRot(float p_146923_)` | `Float.isFinite` guarded, assigns `this.yRot` | `Entity.java:3432-3438` |
| `public float getXRot()` | returns `this.xRot` | `Entity.java:3440-3442` |
| `public void setXRot(float p_146927_)` | `Float.isFinite` guarded, assigns `this.xRot` | `Entity.java:3444-3450` |
| `public float getYHeadRot()` | `LivingEntity` returns `this.yHeadRot`; `Entity` base returns `0.0F` | `LivingEntity.java:3005-3007`, `Entity.java:2499-2501` |
| `public void setYHeadRot(float p_21306_)` | `LivingEntity`: `this.yHeadRot = p_21306_;` (`Entity` base is an empty stub) | `LivingEntity.java:3010-3012`, `Entity.java:2503-2504` |
| `public void setYBodyRot(float p_21309_)` | `this.yBodyRot = p_21309_;` | `LivingEntity.java:3015-3017` |
| `public void absMoveTo(double,double,double,float,float)` | pos + `absRotateTo` | `Entity.java:1403-1406` |
| `public void absRotateTo(float p_348662_, float p_348500_)` | `setYRot(x % 360)`; `setXRot(clamp(y,-90,90) % 360)`; **then `yRotO = getYRot(); xRotO = getXRot();`** | `Entity.java:1408-1413` |

⚠️ **`setXRot` is NOT clamped.** `setXRot` assigns raw. Only `absRotateTo` clamps to
`[-90, 90]`. Clamp pitch yourself to `[-90, 90]` (a player never looks past that).

### 4.3 What vanilla updates automatically every tick (do NOT hand-manage these)

`Entity.baseTick()` (`Entity.java:440-441`):
```java
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
```
`LivingEntity.baseTick()` end (`LivingEntity.java:494-498`):
```java
        this.animStepO = this.animStep;
        this.yBodyRotO = this.yBodyRot;
        this.yHeadRotO = this.yHeadRot;
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
```
`LivingEntity.tick()` tail also wraps `yRotO`/`xRotO`/`yBodyRotO`/`yHeadRotO` into
±180 of their targets (`LivingEntity.java:2468-2498`).

`Player.serverAiStep()` (`Player.java:519-524`):
```java
    @Override
    protected void serverAiStep() {
        super.serverAiStep();
        this.updateSwingTime();
        this.yHeadRot = this.getYRot();
    }
```
⇒ **On the server, `yHeadRot` is force-synced to `yRot` every tick for every `Player`.**
You do **not** need to call `setYHeadRot` for normal walking.

`LivingEntity.tick()` also drives `yBodyRot` toward the movement direction
(`LivingEntity.java:2435-2464`, `tickHeadTurn` at `:2639-2655`), so the body naturally
faces where the bot walks.

### 4.4 What the bot driver must keep in sync

- ✅ Set **`setYRot(yaw)`** every tick (this is the direction `moveRelative` uses).
- ✅ Set **`setXRot(pitch)`** every tick (or leave at 0 / a fixed value when walking).
  It is used by `Player.travel` for the swim pitch (`getLookAngle().y`) and for look-based
  raycasts (`getLookAngle()`, `getViewVector()`), but **not** by ground `moveRelative`.
- ❌ Do **not** write `yRotO`/`xRotO` — `baseTick`/`setOldPosAndRot`/`absMoveTo` own them.
- ❌ Do **not** write `yHeadRot`/`yHeadRotO` for walking — `Player.serverAiStep` owns it.
  (Only relevant if you deliberately want a head-vs-body offset, e.g. "look at player while
  walking away"; then set `yHeadRot` **after** `aiStep`, e.g. in `PlayerTickEvent.Post`,
  otherwise `serverAiStep` overwrites it in the same tick.)
- ⚠️ `yBodyRot` lags `yRot` by up to 50° (`getMaxHeadRotationRelativeToBody`,
  `LivingEntity.java:2656-2658`; overridden to 15° while blocking, `Player.java:339-341`).
  This is purely cosmetic. **[INFERRED]** A hard 180° yaw flip in one tick makes `yBodyRot`
  swing visibly for a few ticks — rotate smoothly if you care about appearance.

### 4.5 What `ServerPlayer` does with `yRot`,`lastSentYRot`, `ClientboundPlayerPositionPacket`, `connection.teleport`

**There is no `lastSentYRot` in `ServerPlayer`.** [Verified by grep: `ServerPlayer.java` contains
no `lastSent*` field other than `lastSentHealth`, `lastSentFood`, `lastSentExp`.]
`lastSentYRot` / `lastSentXRot` / `lastSentYHeadRot` live in **`ServerEntity`**
(`net/minecraft/server/level/ServerEntity.java:59-61`):
```java
    private int lastSentYRot;
    private int lastSentXRot;
    private int lastSentYHeadRot;
```
and are used only for **outbound** tracking/rotation packets (`ServerEntity.java:121-217`), i.e.
what *other* players see. `ServerEntity` does **not** read anything back from the client and does
**not** write the entity's rotation (see the head-rotation broadcast at `:213-217` and the
`ClientboundMoveEntityPacket.Rot` / `ClientboundTeleportEntityPacket` branches at `:137-172`;
`this.entity.hasImpulse = false;` at `:219` is the only server-side mutation). ⇒
**It cannot rubber-band our bot.**

`ServerPlayer.connection.teleport(...)` is called from several places
(`ServerPlayer.java:879, 900, 1047, 1492, 1498, 1513, 1768, 2053`). The important one for
movement is `ServerGamePacketListenerImpl.teleport(double,double,double,float,float,Set<RelativeMovement>)`
— `ServerGamePacketListenerImpl.java:1026-1042`, verbatim:
```java
    public void teleport(double p_9781_, double p_9782_, double p_9783_, float p_9784_, float p_9785_, Set<RelativeMovement> p_9786_) {
        double d0 = p_9786_.contains(RelativeMovement.X) ? this.player.getX() : 0.0;
        double d1 = p_9786_.contains(RelativeMovement.Y) ? this.player.getY() : 0.0;
        double d2 = p_9786_.contains(RelativeMovement.Z) ? this.player.getZ() : 0.0;
        float f = p_9786_.contains(RelativeMovement.Y_ROT) ? this.player.getYRot() : 0.0F;
        float f1 = p_9786_.contains(RelativeMovement.X_ROT) ? this.player.getXRot() : 0.0F;
        this.awaitingPositionFromClient = new Vec3(p_9781_, p_9782_, p_9783_);
        if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
        }

        this.awaitingTeleportTime = this.tickCount;
        this.player.absMoveTo(p_9781_, p_9782_, p_9783_, p_9784_, p_9785_);
        this.player
            .connection
            .send(new ClientboundPlayerPositionPacket(p_9781_ - d0, p_9782_ - d1, p_9783_ - d2, p_9784_ - f, p_9785_ - f1, p_9786_, this.awaitingTeleport));
    }
```
⇒ **`teleport(...)` DOES apply the absolute position server-side immediately**
(`this.player.absMoveTo(p_9781_, p_9782_, p_9783_, …)`) — so teleports themselves work on a bot.
What breaks is only the **latch**: `awaitingPositionFromClient` is set and never cleared, which
(a) permanently disables `handleUseItemOn` (§7.1) and (b) makes `updateAwaitingTeleport()`
re-issue a `teleport(...)` to the *same* spot every ~20 ticks, whose `absMoveTo` will **yank the
bot back to the teleport destination every 20 ticks** — i.e. a slow-motion rubber-band that no
`handleMovePlayer` packet could ever clear. The `ServerboundAcceptTeleportationPacket` handler
(`:492-517`) is what normally clears it, and a fake connection never sends it.
⚠️ Note also that `teleport(...)` does **not** write `firstGood*`; only `resetPosition()` does.
The relative-offset subtraction (`d0`/`d1`/`d2`/`f`/`f1`) is applied **only to the outgoing
packet**, not to the server-side `absMoveTo` — the incoming arguments are already absolute.

### 4.6 The rotation reset that bites you

`ServerGamePacketListenerImpl.java:256-261`:
```java
        this.resetPosition();
        this.player.xo = this.player.getX();
        this.player.yo = this.player.getY();
        this.player.zo = this.player.getZ();
        this.player.doTick();
        this.player.absMoveTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
```
The pitch/yaw arguments are the **current** ones (`getYRot()`/`getXRot()` evaluated *after*
`doTick()`), so rotation survives — but `absRotateTo` also does
`this.yRotO = this.getYRot(); this.xRotO = this.getXRot();` (`Entity.java:1411-1412`), which
**zeroes the visual rotation delta every tick**. Any other player watching the bot will see it
snap to its new yaw instantly with no interpolation, and yaw-driven animations
(`yBodyRot` lerp, `tickHeadTurn`, `calculateEntityAnimation`) are computed against a stale
`yRotO` for the packet that gets sent. Combined with the position snap of §6.4, this is another
reason to replace the default listener tick.

---

## 5. Sprint / sneak / swim / fly / pose

### 5.1 Sprint

- `Entity.java:2271-2277` (verbatim):
  ```java
      public boolean isSprinting() {
          return this.getSharedFlag(3);
      }

      public void setSprinting(boolean p_20274_) {
          this.setSharedFlag(3, p_20274_);
      }
  ```
- `LivingEntity` override, `LivingEntity.java:2060-2068` (verbatim) — **this is what makes
  sprinting actually faster (×1.3 movement speed)**:
  ```java
      @Override
      public void setSprinting(boolean p_21284_) {
          super.setSprinting(p_21284_);
          AttributeInstance attributeinstance = this.getAttribute(Attributes.MOVEMENT_SPEED);
          attributeinstance.removeModifier(SPEED_MODIFIER_SPRINTING.id());
          if (p_21284_) {
              attributeinstance.addTransientModifier(SPEED_MODIFIER_SPRINTING);
          }
      }
  ```
  with `LivingEntity.java:140-142`:
  ```java
      private static final AttributeModifier SPEED_MODIFIER_SPRINTING = new AttributeModifier(
          SPRINTING_MODIFIER_ID, 0.3F, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
      );
  ```
- **Does a `ServerPlayer` derive sprinting from input? NO.**
  **We must call `setSprinting(true)` ourselves.**
  Evidence:
  - The flavour-dependent sprint logic lives in `LocalPlayer.aiStep()`
    (`LocalPlayer.java:719-745`: `canStartSprinting()`, `sprintTriggerTime`, `keySprint.isDown()`,
    `input.hasForwardImpulse()` …) — **client-only code that never runs on the server.**
  - On the server, sprint state only ever changes in
    `ServerGamePacketListenerImpl.handlePlayerCommand` (`:1454-1459`):
    ```java
            case START_SPRINTING:
                this.player.setSprinting(true);
                break;
            case STOP_SPRINTING:
                this.player.setSprinting(false);
                break;
    ```
    i.e. in response to a `ServerboundPlayerCommandPacket` that a fake connection never sends.
  - `Player.canSprint()` returns `true` (`Player.java:2198-2200`) but nothing on the server
    calls it to start sprinting.
- ⚠️ **`setShiftKeyDown(true)` must NOT be set while sprinting**: `Entity.updateSwimming()`
  (`Entity.java:1246-1254`) and `Player.updatePlayerPose()` (`Player.java:411-440`) branch on
  `isShiftKeyDown()` (`Pose.CROUCHING`), and `Player.travel`'s swim pitch test uses `jumping`.
  Also `setSprinting` is independent of sneak server-side, so you can accidentally produce a
  "sneaking sprinter" if you set both.
- Hunger: sprinting itself is free, but `Player.jumpFromGround` charges `0.2F` exhaustion while
  sprinting vs `0.05F` otherwise (`Player.java:1517-1521`), and `FoodData` will eventually stop
  a starving bot from regenerating. A long-running bot should be fed or use a creative-ish
  food source. [INFERRED — behavioural, not an API claim]

### 5.2 Sneak

- `Entity.java:2243-2249` (verbatim):
  ```java
      public void setShiftKeyDown(boolean p_20261_) {
          this.setSharedFlag(1, p_20261_);
      }

      public boolean isShiftKeyDown() {
          return this.getSharedFlag(1);
      }
  ```
- `Entity.java:2267-2269`: `public boolean isCrouching() { return this.hasPose(Pose.CROUCHING); }`
- Pose is derived from the sneak flag in `Player.updatePlayerPose()` (`Player.java:411-440`,
  called from `Player.tick():328`), branch:
  `} else if (this.isShiftKeyDown() && !this.abilities.flying) { pose = Pose.CROUCHING; }`.
- `Entity.java:365`: `public void setPose(Pose p_20125_) { ... }` — **[INFERRED]** you can force the
  pose, but `updatePlayerPose()` recomputes it every tick, so forcing `Pose.SWIMMING`/`CROUCHING`
  without matching flags gets overwritten next tick. Prefer the flags.
- Sneak also matters for walking off ledges: `Entity.isSteppingCarefully()` /
  `isSuppressingBounce()` / `isDescending()` all return `isShiftKeyDown()`
  (`Entity.java:2251-2265`) → no fall damage and no accidental ledge walk-off.

### 5.3 Swim

- `Entity.java:2279-2293` (verbatim):
  ```java
      public boolean isSwimming() {
          return this.getSharedFlag(4);
      }
      ...
      public void setSwimming(boolean p_20283_) {
          this.setSharedFlag(4, p_20283_);
      }
  ```
- **Auto-derived**: `Entity.updateSwimming()` (`Entity.java:1246-1254`) is called from
  `Entity.baseTick():451` every tick. **Verbatim** (`Entity.java:1246-1254`) — note it is
  *two* branches, not one:
  ```java
      public void updateSwimming() {
          if (this.isSwimming()) {
              this.setSwimming(this.isSprinting() && (this.isInWater() || this.isInFluidType((fluidType, height) -> this.canSwimInFluidType(fluidType))) && !this.isPassenger());
          } else {
              this.setSwimming(
                  this.isSprinting() && (this.isUnderWater() || this.canStartSwimming()) && !this.isPassenger()
              );
          }
      }
  ```
  ⚠️ **This is the exact code in THIS checkout** (`Entity.java:1246-1254`, NeoForge 21.1.248 /
  MC 1.21.1). Some third-party write-ups quote a `this.level().isClientSide` split inside
  `updateSwimming()` that is **not present here** — always re-check `Entity.java:1246` before
  relying on either version.
  → **sprint + in-water/underwater = swimming automatically on BOTH sides.**
  `canStartSwimming()` is a NeoForge default method
  (`net/neoforged/neoforge/common/extensions/IEntityExtension.java:292-294`):
  ```java
      default boolean canStartSwimming() {
          return !this.getEyeInFluidType().isAir() && this.canSwimInFluidType(this.getEyeInFluidType()) && this.canSwimInFluidType(this.self().level().getFluidState(this.self().blockPosition()).getFluidType());
      }
  ```
  ⇒ It is the *eye-in-fluid* test, i.e. "the bot's head just went under".
  No manual `setSwimming` needed; calling it manually gets overwritten each tick.
  **[VERIFIED]** sprinting is therefore a *prerequisite* for swimming — a bot that never sprints
  will never enter the swimming pose/animation even when submerged.
- `Player.updateSwimming()` (`Player.java:1549-1556`) short-circuits it to `false` while flying.
- `Player.travel` handles the swim pitch bob (`Player.java:1526-1535`), and
  `LivingEntity.travel`'s water branch (`LivingEntity.java:2170-2203`) uses the same
  `xxa/zza` inputs via `moveRelative(f5, p_21280_)` at `:2190`.

### 5.4 Fly / abilities

- `Player.java:176`: `private final Abilities abilities = new Abilities();`
- `Player.java:1433`: `public Abilities getAbilities() { ... }`
- `Abilities.java:5-14` (verbatim):
  ```java
  public class Abilities {
      public boolean invulnerable;
      public boolean flying;
      /**
       * @deprecated Neoforge: use {@link net.neoforged.neoforge.common.extensions.IPlayerExtension#mayFly} to read and {@link net.neoforged.neoforge.common.NeoForgeMod#CREATIVE_FLIGHT} Attribute to modify
       */
      @Deprecated
      public boolean mayfly;
      public boolean instabuild;
      public boolean mayBuild = true;
  ```
  All `public` — writable, but always re-send to the client with
  `onUpdateAbilities()` (`ServerPlayer.java:1538-1544`) as vanilla does.
- `Player.isAffectedByFluids()` (`Player.java:1106-1108`):
  ```java
      public boolean isAffectedByFluids() {
          return !this.abilities.flying;
      }
  ```
- `Player.travel` flying branch (`Player.java:1537-1543`) is quoted in §2.2: it **preserves
  `deltaMovement.y` before and restores it * 0.6** — so to fly you must apply vertical
  `deltaMovement` yourself (the client does it in `LocalPlayer.aiStep():791-803`:
  ```java
          if (abilities.flying && this.isControlledCamera()) {
              int j = 0;
              if (this.input.shiftKeyDown) {
                  j--;
              }

              if (this.input.jumping) {
                  j++;
              }

              if (j != 0) {
                  this.setDeltaMovement(this.getDeltaMovement().add(0.0, (double)((float)j * abilities.getFlyingSpeed() * 3.0F), 0.0));
              }
          }
  ```
  ). **`yya` does not produce flight.**
- `ServerPlayer.doTick()` revokes flight if not permitted (`ServerPlayer.java:613-616`):
  ```java
              if (this.getAbilities().flying && !this.mayFly()) {
                  this.getAbilities().flying = false;
                  this.onUpdateAbilities();
              }
  ```
- `mayFly()` is a NeoForge default on `IPlayerExtension`
  (`net/neoforged/neoforge/common/extensions/IPlayerExtension.java:87-90`):
  ```java
      default boolean mayFly() {
          // TODO 1.20.5: consider forcing mods to use the attribute
          return self().getAbilities().mayfly || self().getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) > 0;
      }
  ```
  ⇒ to fly, the bot needs **both** `abilities.mayfly = true` **and** `abilities.flying = true`
  (then `onUpdateAbilities()`). `Player.createAttributes()` includes
  `CREATIVE_FLIGHT` (`Player.java:238`), default `false` (`NeoForgeMod.java:212`).
- ⚠️ **While flying, `Player.travel` never falls and `jumping` does not make you jump** — the
  jump block in `aiStep` is gated on `isAffectedByFluids()` which is `false` when flying.
  Vertical drive must be direct `setDeltaMovement` (see the sketch, §9.6).

### 5.5 Summary table

| Capability | API | Who sets it |
|---|---|---|
| Walk forward | `bot.zza = 1.0F` | **us, every tick** |
| Strafe | `bot.xxa = ±1.0F` | **us, every tick** |
| Face direction | `bot.setYRot(yaw)` | **us, every tick** |
| Jump | `bot.jumpFromGround()` (or `setJumping`) | **us** |
| Sprint | `bot.setSprinting(true)` | **us** — server does *not* derive it |
| Sneak | `bot.setShiftKeyDown(bool)` | **us** — server does *not* derive it |
| Swim | *(auto)* `updateSwimming()` from sprint + in-water | server, automatic |
| Pose | *(auto)* `Player.updatePlayerPose()` from flags | server, automatic |
| Fly | `getAbilities().mayfly/flying` + `setDeltaMovement` for Y | **us** |
| Vertical swim pitch | *(auto)* uses `getXRot()` + `jumping` | server, given our yaw/pitch |

---

## 6. THE CRITICAL QUESTION — does server-set `zza`/`xxa` actually move a `ServerPlayer`?

### 6.1 Is `aiStep()`/`travel()` really invoked every tick for a `ServerPlayer` on the dedicated server?

**Yes.** Full code path quoted in §2.3. The three gates that could have said no, and why they say yes:

1. **Is the player in the level's tick list at all?**
   `ServerLevel.addNewPlayer(ServerPlayer)` → `addPlayer(...)` (`ServerLevel.java:909-928`) →
   `this.entityManager.addNewEntityWithoutEvent(p_8854_)` →
   `PersistentEntitySectionManager.addEntityWithoutEvent` (`PersistentEntitySectionManager.java:83-106`) →
   `Visibility visibility = getEffectiveStatus(p_157539_, entitysection.getStatus());`
   and (`:108-110`):
   ```java
       static <T extends EntityAccess> Visibility getEffectiveStatus(T p_157536_, Visibility p_157537_) {
           return p_157536_.isAlwaysTicking() ? Visibility.TICKING : p_157537_;
       }
   ```
   with `Player.java:2166-2169`:
   ```java
       @Override
       public boolean isAlwaysTicking() {
           return true;
       }
   ```
   ⇒ `startTicking` → `onTickingStart` → `ServerLevel.this.entityTickList.add(p_143363_)`
   (`ServerLevel.java:1734-1736`). **Players are always in the entity tick list,
   independent of chunk status.** ⚠️ **But** `ServerLevel.tick`'s `entityTickList.forEach`
   ALSO requires `this.chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(...)`
   (`ServerLevel.java:408`) before calling `tickNonPassenger` — so a bot whose chunk is outside
   simulation distance will *not* be ticked by the level. [UNCERTAIN] For a normally-registered
   player this is satisfied because players force-load their own chunk via the chunk-source
   ticket system; if you bypass `PlayerList.placeNewPlayer`, **verify this** (check
   `bot.serverLevel().getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(...)`
   or that the chunk is entity-ticking).
2. **The order of `tickNonPassenger` vs the connection tick.**
   `MinecraftServer.tickChildren` (`:1018-1053`) does, in order:
   `… serverlevel.tick(...)` for every level (`:1037`) → `this.getConnection().tick()` (`:1051`)
   → `this.playerList.tick()` (`:1053`).
   `Player` is `isAlwaysTicking()` and in `entityTickList`, and `ServerGamePacketListenerImpl.tick()`
   reaches `doTick()` — **so BOTH paths run.** Does that mean physical movement happens twice?
   **No:** `ServerLevel.tickNonPassenger` (`:766-782`) calls `p_8648_.tick()`, which for
   `ServerPlayer` is the *bookkeeping-only* override (`ServerPlayer.java:495`) — it does **not**
   reach physics. Physics happens once per tick, from the connection tick. ✅
   ⚠️ Conversely: if the connection tick never runs, **physics never runs at all.**
3. **`isEffectiveAi()`** → `Entity.java:3054-3056` → `!this.level().isClientSide` → `true` on the
   server. ⇒ `serverAiStep()` runs (`LivingEntity.java:2704-2708`) and `travel()`'s guard passes.

Also relevant: `LivingEntity.tick():2431-2433`:
```java
        if (!this.isRemoved()) {
            this.aiStep();
        }
```
and `isImmobile()` (`LivingEntity.java:2080-2082`, `Player.java:1101-1104`) zeroing the inputs
at `LivingEntity.java:2700-2703`:
```java
        if (this.isImmobile()) {
            this.jumping = false;
            this.xxa = 0.0F;
            this.zza = 0.0F;
        } else if (this.isEffectiveAi()) {
```
with
```java
    protected boolean isImmobile() {          // LivingEntity.java:2080-2082
        return this.isDeadOrDying();
    }
    ...
    protected boolean isImmobile() {          // Player.java:1101-1104
        return super.isImmobile() || this.isSleeping();
    }
```
⇒ **the inputs are zeroed whenever the bot is dead or sleeping.** Don't try to drive a sleeping bot.

### 6.2 Does anything zero the inputs?

Only the two cases above (`isImmobile()`), plus the automatic `*0.98F` decay
(`LivingEntity.java:2745-2746`) and `getInputVector` returning `Vec3.ZERO` when
`lengthSqr() < 1.0E-7` (`Entity.java:1385-1388`). **There is no `this.zza = 0` in
`ServerPlayer.doTick()` or in `ServerPlayer.tick()`** — [verified by grep on `ServerPlayer.java`:
`zza` appears only at lines 1227 and 1230-region (`setPlayerInput`); `xxa` only at line 1223].

### 6.3 Is there client-authoritative movement handling that fights us?

`ServerGamePacketListenerImpl.handleMovePlayer(ServerboundMovePlayerPacket)` (`:857-986`) is the
only thing that can overwrite position from network input. **It is reachable only by a packet:**
```java
    @Override
    public void handleMovePlayer(ServerboundMovePlayerPacket p_9874_) {
        PacketUtils.ensureRunningOnSameThread(p_9874_, this, this.player.serverLevel());
                                          ^^^ p_9874_ is the packet being handled
```
and `ServerboundMovePlayerPacket.handle` (`ServerboundMovePlayerPacket.java:35-37`):
```java
    public void handle(ServerGamePacketListener p_134138_) {
        p_134138_.handleMovePlayer(this);
    }
```
A fake connection that never decodes/sends a `ServerboundMovePlayerPacket` **never enters this
method**, so:
- no `this.player.move(MoverType.PLAYER, new Vec3(d6, d7, d8))` (`:921`),
- no `this.player.absMoveTo(d0, d1, d2, f, f1)` (`:944`),
- no "moved too quickly!" rubber-band teleport (`:903-906`),
- no "moved wrongly!" correction (`:931-938`),
- no `clientIsFloating` / "kicked for floating" (`:946-954`, `:264-273`).
✅ **Confirmed: a fake connection cannot trigger any of these.**

⚠️ **But** `ServerGamePacketListenerImpl.tick()` unconditionally calls `resetPosition()`
(which refreshes `firstGood*`/`lastGood*` to the *current* position) and then `absMoveTo`s
back to `firstGood*` — see next section. That is not "client authority", it is a
"rewind to tick-start" that assumes a *later* `handleMovePlayer` will move the player forward.

### 6.4 ⛔ The actual blocker: the `firstGood*` rewind

`ServerGamePacketListenerImpl.java:256-261` (verbatim, bytecode-confirmed):
```java
        this.resetPosition();
        this.player.xo = this.player.getX();
        this.player.yo = this.player.getY();
        this.player.zo = this.player.getZ();
        this.player.doTick();
        this.player.absMoveTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, this.player.getYRot(), this.player.getXRot());
```
and `ServerGamePacketListenerImpl.java:325-332`:
```java
    public void resetPosition() {
        this.firstGoodX = this.player.getX();
        this.firstGoodY = this.player.getY();
        this.firstGoodZ = this.player.getZ();
        this.lastGoodX = this.player.getX();
        this.lastGoodY = this.player.getY();
        this.lastGoodZ = this.player.getZ();
    }
```
`firstGoodX/Y/Z` are assigned **only** in `resetPosition()`
(`ServerGamePacketListenerImpl.java:326-328`; `:881` only *reads* `firstGoodX` — verified by grep:
the only writes in the entire tree are lines 326-328).

**Exhaustive list of `resetPosition()` call sites** (repo-wide grep for `.resetPosition()`,
`ServerGamePacketListenerImpl.resetPosition()` being the only zero-arg one):
| Call site | Trigger |
|---|---|
| `ServerGamePacketListenerImpl.java:256` | **top of `tick()`, every tick** |
| `ServerGamePacketListenerImpl.java:865` | `handleMovePlayer` — packet only, unreachable for us |
| `ServerPlayer.java:880`, `:901` | respawn (same-dim) and dimension change — each **immediately after** a `connection.teleport(...)` |
| `ServerPlayer.java:1525` | `ServerPlayer.moveTo(double,double,double)` override |
| `ServerPlayer.java:1713` | camera reset (`setCamera`) |

⇒ The **only** per-tick refresh is line 256. Nothing in the physics path
(`Entity.move` → `setPos`, `Entity.java:609`/`:640`) touches `firstGood*`, and `resetPosition()`
itself is never called from `doTick()`, `Player.tick()`, `LivingEntity.tick()`, `aiStep()` or
`travel()`. **So `firstGood*` holds the position as of the START of the current tick, and the
`absMoveTo` at line 261 always undoes that tick's self-movement.** Diagnosis confirmed by
elimination, not just by reading the two lines.
`ServerPlayer.moveTo` also calls `this.connection.resetPosition()` (`ServerPlayer.java:1523-1526`)
— note that this is a *different*, 3-arg `moveTo`, i.e. still a rewind, not a forward move.

**Net, for a bot with a connection whose `tick()` is not overridden:**
```
start of tick:  resetPosition()            → firstGood* = X0
                mouse...  doTick()          → physics moves player to X1
                absMoveTo(firstGood*)       → player snapped back to X0
```
⇒ **The bot is teleported to its tick-start position every single tick.** It accumulates
**zero** net displacement and will stand still forever, no matter what we write to
`zza`/`xxa`/`jumping`/`yRot`. Collisions/gravity are computed (the player can be pushed by
water, will take no fall damage because it never falls, etc.), but the *result* is discarded.

This is exactly why NeoForge's own `FakePlayer.FakePlayerNetHandler` overrides **`tick()` to a
no-op** — see `net/neoforged/neoforge/common/util/FakePlayer.java:195-206`:
```java
    private static class FakePlayerNetHandler extends ServerGamePacketListenerImpl {
        private static final Connection DUMMY_CONNECTION = new FakeConnection();

        public FakePlayerNetHandler(MinecraftServer server, ServerPlayer player) {
            super(server, DUMMY_CONNECTION, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        @Override
        public void tick() {}
        ...
        @Override
        public void resetPosition() {}
```
⚠️ **And that no-op `tick()` is why NeoForge's `FakePlayer` cannot move** — with `tick()`
overridden to nothing, `doTick()` is never called either, so `FakePlayer.tick() {}`
(`FakePlayer.java:122-123`) just makes it worse. NeoForge `FakePlayer` is designed for
inventory/interaction automation, **not** locomotion. **Do not copy it for movement.**

### 6.5 What this means for the design (the viable options)

| # | Approach | Effect | Verdict |
|---|---|---|---|
| A | Subclass `ServerGamePacketListenerImpl`, override `tick()` with our own copy that runs `resetPosition(); xo/yo/zo=…; doTick();` **without** the trailing `absMoveTo` (and without the `xo/yo/zo` pre-write if we want vanilla-exact `xo` semantics) | physics sticks; every vanilla movement feature (collision, swimming, ladders, ice, jumping, fall damage) works | ✅ **recommended** |
| B | Same but keep the trailing `absMoveTo` and instead **call our driver in `PlayerTickEvent.Post` and then `setPos` forward** — fights the rewind, jittery | brittle | ❌ |
| C | Override `resetPosition()` to a no-op (mirroring `FakePlayer`) but keep vanilla `tick()` | `firstGood*` stay `(0,0,0)` forever ⇒ `absMoveTo(0,0,0,…)` teleports the bot to the world origin every tick | ❌ catastrophic |
| D | Don't give the bot a working connection; drive `bot.doTick()` ourselves from `ServerTickEvent.Post` | works, but you must also replicate `xo/yo/zo` handling and you bypass `ServerGamePacketListenerImpl`'s safety net entirely; and any `connection.send` will NPE if `connection == null` | ⚠️ possible fallback |
| E | Extend `ServerPlayer` and override `doTick()` | does not help — the rewind is in the *listener*, outside `doTick()` | ❌ |

Note for option A: the `xo/yo/zo` pre-write at `:257-259` exists so that a *network*-driven player's
interpolation deltas are computed against its tick-start position (the real movement then arrives
via `handleMovePlayer`). For a self-driven bot this makes `deltaMovement`-independent animations
(`calculateEntityAnimation`, `LivingEntity.tick():2435-2437`) read as zero for that tick. If you
want walk animations and sprint particles to look right for observers, **omit those three lines**
too — `LivingEntity.baseTick`/`ServerLevel.tickNonPassenger` already maintain `xo/yo/zo`
via `setOldPosAndRot()` (`Entity.java:1448-1460`, called at `ServerLevel.java:767`).
**[INFERRED — needs an in-game check]** Whether omitting them causes any observable regression
in vanilla tracking has not been tested here.

Also note: if you override `tick()` wholesale, you must keep **`this.tickCount++`**
(`ServerGamePacketListenerImpl.java:262`), `this.knownMovePacketCount = this.receivedMovePacketCount`
(`:263`), `keepConnectionAlive()` (`:299`), and ideally the `lastVehicle`/floating bookkeeping, or
subtle things (idle timeout, keep-alive) break. [UNCERTAIN] `keepConnectionAlive`
(`ServerCommonPacketListenerImpl.java:134-149`) sends a `ClientboundKeepAlivePacket` every 15 s and
kicks the player if the ack never arrives — with a connection that discards outbound packets that
is fine, but see §7.3.

---

## 7. `ServerGamePacketListenerImpl` movement fields — do they need initialisation?

### 7.1 Field declarations (`ServerGamePacketListenerImpl.java:204-229`, verbatim excerpts)

```java
    private int tickCount;
    private int ackBlockChangesUpTo = -1;
    private int chatSpamTickCount;
    private int dropSpamTickCount;
    private double firstGoodX;
    private double firstGoodY;
    private double firstGoodZ;
    private double lastGoodX;
    private double lastGoodY;
    private double lastGoodZ;
    @Nullable
    private Entity lastVehicle;
    private double vehicleFirstGoodX;
    private double vehicleFirstGoodY;
    private double vehicleFirstGoodZ;
    private double vehicleLastGoodX;
    private double vehicleLastGoodY;
    private double vehicleLastGoodZ;
    @Nullable
    private Vec3 awaitingPositionFromClient;
    private int awaitingTeleport;
    private int awaitingTeleportTime;
    private boolean clientIsFloating;
    private int aboveGroundTickCount;
    private boolean clientVehicleIsFloating;
    private int aboveGroundVehicleTickCount;
    private int receivedMovePacketCount;
    private int knownMovePacketCount;
```

All of these are `private` — **not reachable from a mod without reflection/AT/Mixin**, except
via the public methods `resetPosition()`, `teleport(...)`, `send(...)`, and `tick()`.

| Field | Initial value | Needs init? | Why |
|---|---|---|---|
| `firstGoodX/Y/Z` | `0.0` | ✅ yes — overwritten by `resetPosition()` at the top of `tick()` (`:256`), so effectively self-initialising **as long as `tick()` runs**. | They are the snap-back target (§6.4). If your custom `tick()` skips `resetPosition()`, they stay `0,0,0` and you teleport to the origin. |
| `lastGoodX/Y/Z` | `0.0` | ⚠️ only used by `handleMovePlayer` (`:912-914`, `:974-976`) and `handleAcceptTeleportPacket` (`:508-510`) — both packet-only. **Irrelevant for a fake connection.** | — |
| `awaitingPositionFromClient` | `null` (`@Nullable Vec3`) | ✅ **must stay `null`**. | If non-null: `updateAwaitingTeleport()` (`:988-1006`) makes `handleMovePlayer` a no-op and every ~20 ticks re-calls `teleport(awaitingPositionFromClient…)`, and **`handleUseItemOn` refuses every block interaction** (`:1120`, `if (this.awaitingPositionFromClient == null && serverlevel.mayInteract(...))`). It is set by `connection.teleport(...)` (`:1032`) and cleared **only** by `handleAcceptTeleportPacket` (`:515`) — a fake connection never sends `ServerboundAcceptTeleportationPacket`, so **once set it is stuck forever**. ⇒ **avoid calling `connection.teleport(...)` entirely, or override it** (see §7.4). ⚠️ **This will bite the chest-opening feature**: `PlayerList.placeNewPlayer` calls `servergamepacketlistenerimpl.teleport(...)` at `PlayerList.java:220` on the normal join path, and respawn does the same at `:482` — a chest that silently refuses to open is the symptom. |
| `awaitingTeleport` | `0` | no | just the teleport id counter used in `ClientboundPlayerPositionPacket`. |
| `awaitingTeleportTime` | `0` | no | refreshed by `updateAwaitingTeleport()`. |
| `clientIsFloating`, `aboveGroundTickCount` | `false` / `0` | no, and **cannot trigger**: only set inside `handleMovePlayer` (`:946`) which we never reach. The `:264-273` floating kick is guarded by `if (this.clientIsFloating …)` and `else { this.clientIsFloating = false; this.aboveGroundTickCount = 0; }` each tick. | ✅ safe |
| `vehicleFirstGood*`, `vehicleLastGood*`, `clientVehicleIsFloating`, `aboveGroundVehicleTickCount` | `0`/`false` | no | only meaningful while riding with a client. |
| `receivedMovePacketCount`, `knownMovePacketCount` | `0` | no | remain 0; used only by the too-many-packets detector. |
| `tickCount` | `0` | no (incremented at `:262`) | but if you write your own `tick()`, keep the increment. |

### 7.2 Will the server rubber-band or reset our bot's position?

- ❌ **Not from packets** — `handleMovePlayer` is dead code for a fake connection (§6.3).
- ✅ **Not from anti-cheat** — "moved too quickly"/"moved wrongly"/"floating too long" all live
  inside `handleMovePlayer`/the `clientIsFloating` flag it sets. All unreachable.
- ⛔ **But YES from the vanilla `firstGood*` rewind in `tick()`** — see §6.4. That is the one and
  only thing that will hold the bot in place, and it is not configurable without replacing `tick()`.

### 7.3 Other traps in a hand-rolled listener

- `ServerPlayer.java:613-616` (in `doTick`) turns off flying when `!mayFly()` — plan for it.
- `ServerPlayer.doTick()` writes to `this.connection` unconditionally (inventory `ComplexItem`
  packets `:559-567`, health `:572`, exp `:610`, abilities `:615`). ⇒ **`connection` must be
  non-null**; use a stub that swallows writes.
- `ServerCommonPacketListenerImpl.send(Packet, PacketSendListener)` (`:177-194`) calls
  `net.neoforged.neoforge.network.registration.NetworkRegistry.checkPacket(...)` and then
  `this.connection.send(...)`. A `Connection` constructed as `new Connection(PacketFlow.SERVERBOUND)`
  has `channel == null` ⇒ `isConnected()` is `false` (`Connection.java:583-585`) ⇒ `send` just
  **queues into `pendingActions`** (`Connection.java:336-343`) and never touches a channel. That
  is memory-safe, but the queue grows unboundedly. **[INFERRED]** Override `send` to drop packets
  (as `FakePlayer.FakePlayerNetHandler` does at `FakePlayer.java:295-299`) but **keep the
  superclass bookkeeping you need** (`isTerminal()`/`close()` handling at `:180-182` is worth
  keeping if you ever send a disconnect).
- `Connection.isConnecting()` returns `this.channel == null` (`Connection.java:587-589`);
  combined with `isConnected()` this means a channel-less connection is "connecting" forever, so
  `ServerConnectionListener.tick()` would skip it **if it were registered**. ⇒ **Do not register
  the fake connection in `ServerConnectionListener.connections`**; drive `doTick()` from your own
  `tick()` invoked by a `ServerTickEvent.Post`/`Pre` handler (or accept that the listener is not
  registered and call `listener.tick()` yourself). This is a real design decision the parent agent
  must make — see §8.
  Alternatively override `isConnected()`/`isConnected()`-adjacent behaviour; but `Connection.channel`
  is `private` (`Connection.java:90`) so you cannot make `isConnected()` true without a real channel.
- `ServerPlayer.isChangingDimension()` / `teleportTo(...)` paths call `connection.teleport(...)`,
  which (per §7.1) latches `awaitingPositionFromClient`. **[INFERRED]** if you ever dimension-change
  or respawn the bot, explicitly clear it (you cannot — it is `private`), or avoid
  `connection.teleport` by using `bot.teleportTo(...)` carefully, or subclass and shadow the
  behaviour. This is the strongest argument for **subclassing `ServerGamePacketListenerImpl` and
  overriding `teleport(...)` to a direct `absMoveTo`** — see the sketch.

### 7.4 Minimal safe listener (recommended shape)

```java
/** Owns the player's tick so vanilla physics is kept but the firstGood* rewind is not. */
static final class BotConnection extends ServerGamePacketListenerImpl {
    private final ServerPlayer bot;

    BotConnection(MinecraftServer server, ServerPlayer bot) {
        super(server, new DummyConnection(), bot, CommonListenerCookie.createInitial(bot.getGameProfile(), false));
        this.bot = bot;
    }

    @Override
    public void tick() {
        // Mirrors ServerGamePacketListenerImpl.tick() minus the trailing absMoveTo(firstGood*) rewind.
        this.bot.doTick();           // runs real vanilla physics incl. travel()
        // deliberately NOT: this.bot.absMoveTo(firstGoodX, firstGoodY, firstGoodZ, ...)
    }

    /** Direct teleport: never latch awaitingPositionFromClient (no client will ever ack it). */
    @Override
    public void teleport(double x, double y, double z, float yRot, float xRot) {
        this.bot.absMoveTo(x, y, z, yRot, xRot);
    }

    @Override
    public void teleport(double x, double y, double z, float yRot, float xRot, Set<RelativeMovement> rel) {
        this.teleport(x, y, z, yRot, xRot);
    }

    @Override
    public void send(Packet<?> packet) { /* drop */ }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) { /* drop */ }

    @Override
    public void disconnect(Component reason) { /* log, never kick */ }

    @Override
    public void handleMovePlayer(ServerboundMovePlayerPacket packet) { /* no-op */ }
}
```
⚠️ **Note the `tick()` override loses** the `xo/yo/zo` pre-write, the `tickCount++`, and the
keep-alive/idle bookkeeping. **[UNCERTAIN]** Whether the missing `tickCount++` matters for
`ServerPlayer` — it is only used inside this class for `awaitingTeleportTime` and
`tickCount == 0` in `handleMovePlayer`, both packet-only. The `keepConnectionAlive()`/idle-timeout
loss is a *feature* for a bot (we don't want to be kicked for not answering keep-alives).
Call `keepConnectionAlive()` only if you want the disconnect-on-no-ack behaviour — you don't.

#### 7.4.1 Where our `teleport` override gets used (i.e. what it fixes)

`ServerPlayer` routes almost everything through `connection.teleport`:

| `ServerPlayer` method | Line | Routes to |
|---|---|---|
| `public void teleportTo(double x, double y, double z)` | `1490-1493` | `connection.teleport(x,y,z,getYRot(),getXRot(),RelativeMovement.ROTATION)` |
| `public void teleportRelative(double dx, double dy, double dz)` | `1495-1499` | `connection.teleport(getX()+dx, …, RelativeMovement.ALL)` |
| `public void teleportTo(ServerLevel, double,double,double,float,float)` | `1764-1768` | `connection.teleport(...)` when the level is unchanged |
| `stopSleepInBed` | `1045-1048` | `connection.teleport(getX(),getY(),getZ(),…)` |
| respawn / dimension change | `879-880`, `900-901` | `connection.teleport(...)` + `resetPosition()` |
| `setCamera` reset | `2053` | `connection.teleport(getX(),getY(),getZ(),…)` |

⇒ **Overriding the two `teleport(...)` overloads covers all of them at once**, and turns
"set the pending-teleport latch and send a packet" into a real, immediate `absMoveTo`.
This is the single highest-value override in the listener. Contrast with the *base* implementation
`Entity.teleportTo(double,double,double)` (`Entity.java:2771-2776`), which is pure
`moveTo(...)` + `teleportPassengers()` and never touches the connection — `ServerPlayer`
overrides it *away* from that safe behaviour, which is counter-intuitive and worth remembering.
⚠️ Consequence of the override: **nothing about the incoming arguments changes** — despite the
`Set<RelativeMovement>` parameter, callers of `connection.teleport(...)` pass **already-absolute**
coordinates. Proof: `ServerPlayer.teleportRelative` (`:1495-1499`) pre-resolves them itself —
```java
    @Override
    public void teleportRelative(double p_251611_, double p_248861_, double p_252266_) {
        this.connection
            .teleport(this.getX() + p_251611_, this.getY() + p_248861_, this.getZ() + p_252266_, this.getYRot(), this.getXRot(), RelativeMovement.ALL);
    }
```
— and the relative set only ever affects the **outgoing packet** fields
(`ServerGamePacketListenerImpl.java:1027-1031`: `d0/d1/d2/f/f1` are subtracted in the packet
constructor call, never added to `absMoveTo`). So the 6-arg override is simply:
```java
    @Override
    public void teleport(double x, double y, double z, float yRot, float xRot, Set<RelativeMovement> rel) {
        // Arguments are already absolute; `rel` only shapes the (dropped) client packet.
        this.bot.absMoveTo(x, y, z, yRot, xRot);
    }
```
⚠️ Do **not** re-apply the relative offsets yourself — that double-adds and would make
`teleportRelative` jump twice as far. (This is the natural mistake; it is called out here
because the vanilla API's `Set<RelativeMovement>` parameter is misleadingly named.)

- `DummyConnection` must be channel-less so `isConnected() == false` and `send` only queues
  **if** you don't override `send`. Since we override `send`, the queue never grows.
- `Connection` has **no public constructor taking a channel**; `new Connection(PacketFlow.SERVERBOUND)`
  is the only option (`Connection.java:113-115`).
- `ConnectionUtil`/`ConnectionType`: `CommonListenerCookie.createInitial(GameProfile, boolean)`
  (`CommonListenerCookie.java:15-17`) uses `ConnectionType.OTHER` (the deprecated 4-arg ctor),
  exactly as NeoForge's `FakePlayer` does — acceptable.

---

## 8. `PathNavigation` reference — is it reusable for a `ServerPlayer`?

### 8.1 It is a `Mob`-only system

`PathNavigation.java:29-56` (verbatim excerpts):
```java
public abstract class PathNavigation {
    ...
    protected final Mob mob;
    protected final Level level;

    protected Path path;
    protected double speedModifier;
    protected int tick;
    ...
    public PathNavigation(Mob p_26515_, Level p_26516_) {
```
It is **hard-typed to `Mob`**, held by `Mob.java:112`:
```java
    protected PathNavigation navigation;
```
with the accessor at `Mob.java:212-214`:
```java
    public PathNavigation getNavigation() {
        return this.getControlledVehicle() instanceof Mob mob ? mob.getNavigation() : this.navigation;
    }
```
and created in `Mob`'s constructor path (`Mob.java:146`): `this.navigation = this.createNavigation(p_21369_);`

**`Player` has NO `PathNavigation`.**
[Verified by grep: `Player.java` and `ServerPlayer.java` contain **zero** occurrences of
`PathNavigation` or `getNavigation`. `Player extends LivingEntity`, `Player.java:116`:
`public abstract class Player extends LivingEntity implements net.neoforged.neoforge.common.extensions.IPlayerExtension`
— it does not extend `Mob`, and `ServerPlayer.java:165` is `public class ServerPlayer extends Player`.]
⇒ **There is nothing to reuse by inheritance, and `PathNavigation`'s constructor strictly requires
a `Mob`, so you cannot instantiate one for a `ServerPlayer` either.** To reuse the A* itself you
would have to use the *lower* layers: `PathFinder`, `NodeEvaluator`, `WalkNodeEvaluator`,
`Path` — those are `Mob`-agnostic enough to be worth a look, but that is a separate investigation.

### 8.2 How it drives a mob (for reference)

- `PathNavigation.java:37`: `protected double speedModifier;`
- `PathNavigation.java:78-80`: `public void setSpeedModifier(double p_26518_) { this.speedModifier = p_26518_; }`
- `PathNavigation.java:157-195` (`moveTo` family, verbatim heads):
  ```java
      public boolean moveTo(double p_26520_, double p_26521_, double p_26522_, double p_26523_) {
      public boolean moveTo(double p_334082_, double p_333723_, double p_333873_, int p_333757_, double p_333795_) {
      public boolean moveTo(Entity p_26532_, double p_26533_) {
      public boolean moveTo(@Nullable Path p_26537_, double p_26538_) {
  ```
- `PathNavigation.tick()` — `PathNavigation.java:201-224` (verbatim):
  ```java
      public void tick() {
          this.tick++;
          if (this.hasDelayedRecomputation) {
              this.recomputePath();
          }

          if (!this.isDone()) {
              if (this.canUpdatePath()) {
                  this.followThePath();
              } else if (this.path != null && !this.path.isDone()) {
                  Vec3 vec3 = this.getTempMobPos();
                  Vec3 vec31 = this.path.getNextEntityPos(this.mob);
                  if (vec3.y > vec31.y && !this.mob.onGround() && Mth.floor(vec3.x) == Mth.floor(vec31.x) && Mth.floor(vec3.z) == Mth.floor(vec31.z)) {
                      this.path.advance();
                  }
              }

              DebugPackets.sendPathFindingPacket(this.level, this.mob, this.path, this.maxDistanceToWaypoint);
              if (!this.isDone()) {
                  Vec3 vec32 = this.path.getNextEntityPos(this.mob);
                  this.mob.getMoveControl().setWantedPosition(vec32.x, this.getGroundY(vec32), vec32.z, this.speedModifier);
              }
          }
      }
  ```
- **Key architectural insight:** `PathNavigation` **does not set `xxa`/`zza` itself.** It pushes a
  destination into the mob's **`MoveControl`** (`this.mob.getMoveControl().setWantedPosition(...)`).
- `GroundPathNavigation` (`net/minecraft/world/entity/ai/navigation/GroundPathNavigation.java:19-22`):
  `public class GroundPathNavigation extends PathNavigation { public GroundPathNavigation(Mob p_26448_, Level p_26449_) {`
  — adds door/fence handling (`setCanOpenDoors`, `setCanPassDoors`, `setAvoidSun`,
  `setCanWalkOverFences`, lines 133-155).
- `MoveControl.tick()` is where strafe/forward become **input-ish** values; `MoveControl.java:57-77`
  (verbatim excerpt):
  ```java
      public void tick() {
          if (this.operation == MoveControl.Operation.STRAFE) {
              float f = (float)this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
              float f1 = (float)this.speedModifier * f;
              float f2 = this.strafeForwards;
              float f3 = this.strafeRight;
              ...
              float f5 = Mth.sin(this.mob.getYRot() * (float) (Math.PI / 180.0));
              float f6 = Mth.cos(this.mob.getYRot() * (float) (Math.PI / 180.0));
              float f7 = f2 * f6 - f3 * f5;
              float f8 = f3 * f6 + f2 * f5;
  ```
  and it calls `this.mob.setZza(...)`/`setXxa(...)`/`setSpeed(...)` (`Mob.java:532-548`) — the very
  same mechanism we will use by hand, just authored by `MoveControl` instead of us.
- `Mob.serverAiStep` (`Mob.java:781-798`) calls `this.navigation.tick()` then
  `moveControl.tick()`, `lookControl.tick()`, `jumpControl.tick()` — i.e. **all of this is
  `Mob`-only plumbing that a `Player` never executes.**

### 8.3 Verdict

- ❌ **`PathNavigation`/`GroundPathNavigation` are NOT reusable for a `ServerPlayer`** — they are
  `Mob`-typed, and `Player` has no navigation/MoveControl/LookControl/JumpControl at all.
- ✅ **What IS reusable:** the generic path-finding layer (`PathFinder`, `NodeEvaluator`,
  `WalkNodeEvaluator`, `Path`, `PathTypeCache`, `Target`/`BlockPos` helpers) — none of it requires
  a `Mob` at the A* level [INFERRED from the class shapes; not verified line-by-line here].
- ✅ **We re-implement `MoveControl`'s job ourselves** (yaw from look-direction + `zza`/`xxa`)
  — which is the recommended driver below.

---

## 9. RECOMMENDED MOVEMENT DRIVER

Design: **one driver object per bot**, invoked **after `doTick()` has already run physics for the
tick** (so the values we write are consumed by the *next* physics tick), using the §7.4 listener
so nothing rewinds the result. No A* here — a straight-line steerer with a stuck-escalation hook.

### 9.1 Where to hook

Inside our `BotConnection.tick()` (from §7.4), *after* `bot.doTick()`:

```java
    @Override
    public void tick() {
        this.bot.doTick();          // 1. vanilla physics, using last tick's inputs
        MovementDriver.tick(this.bot, this.target);  // 2. decide inputs for the next tick
    }
```
Rationale: `doTick()` → `aiStep()` consumes and decays (`*0.98F`) the inputs, so writing them
*after* keeps them at exactly the values we chose. Writing them *before* `doTick()` also works
(they'd be decayed by one tick) — **pick one and be consistent**; do not do both.

### 9.2 The driver

```java
package com.melody.mcagent.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a target world position into ServerPlayer input fields every tick so that
 * LivingEntity.travel() walks the bot there with real vanilla physics + collision.
 *
 * PRECONDITIONS (enforced by BotConnection):
 *   - bot.connection.tick() calls bot.doTick() and does NOT absMoveTo(firstGood*).
 *   - The bot is not sleeping / dead / riding (inputs are zeroed by isImmobile()).
 */
public final class MovementDriver {

    /** Reached when horizontal distance <= this (blocks). */
    private static final double ARRIVE_RADIUS = 0.35;
    /** Re-aim (recompute yaw) when the bearing error exceeds this many degrees. */
    private static final float YAW_DEADZONE = 6.0F;
    /** Stop sprinting when the remaining horizontal distance is below this. */
    private static final double SPRINT_STOP_DISTANCE = 1.5;
    /** Jump when the horizontal blockage has persisted this many ticks (ledge/wall). */
    private static final int STUCK_JUMP_TICKS = 4;

    private final ServerPlayer bot;
    private Vec3 target;
    private int stuckTicks;
    private double lastX, lastZ;
    private boolean jumpThisTick;

    public MovementDriver(ServerPlayer bot) {
        this.bot = bot;
        this.lastX = bot.getX();
        this.lastZ = bot.getZ();
    }

    public void setTarget(Vec3 target) { this.target = target; }
    public void clearTarget()           { this.target = null; }
    public boolean isIdle()             { return this.target == null; }

    /** Call once per server tick, AFTER bot.doTick(). */
    public void tick() {
        ServerPlayer bot = this.bot;

        // --- 0. refuse to fight the engine in states where inputs are ignored/zeroed ---
        if (this.target == null || bot.isRemoved() || bot.isDeadOrDying() || bot.isSleeping()
                || bot.isPassenger() || bot.getAbilities().flying) {
            this.reset();
            return;
        }

        // --- 1. horizontal delta to target ---
        double dx = this.target.x - bot.getX();
        double dz = this.target.z - bot.getZ();
        double dy = this.target.y - bot.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (horiz <= ARRIVE_RADIUS && Math.abs(dy) < 1.5) {
            this.reset();                       // arrived: zero inputs, stop sprinting
            return;
        }

        // --- 2. yaw: face the target. Entity.getInputVector() rotates (xxa,zza) by getYRot(),
        //        so forward (zza=+1) travels along -sin(yaw), +cos(yaw) => yaw = atan2(-dx, dz). ---
        float yaw = (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI));
        bot.setYRot(yaw);
        // pitch: keep it level while walking; pitch only matters for look/raycasts and swim bob.
        bot.setXRot(0.0F);
        // yHeadRot is force-synced to yRot by Player.serverAiStep(); yRotO/xRotO are owned by
        // baseTick()/setOldPosAndRot(). Do NOT write yBodyRot*/yHeadRot*/yRotO/xRotO here.

        // --- 3. forward impulse. Values are in [-1,1]; getInputVector normalises length>1. ---
        bot.zza = 1.0F;
        bot.xxa = 0.0F;          // pure "face target and walk" steering
        bot.yya = 0.0F;          // unused for ground movement

        // --- 4. sprint: NOT derived from input on the server; we must set it explicitly. ---
        //     Sprinting applies SPEED_MODIFIER_SPRINTING (+30% MOVEMENT_SPEED) and is also what
        //     makes Entity.updateSwimming() turn on swimming when underwater.
        bot.setSprinting(horiz > SPRINT_STOP_DISTANCE);

        // --- 5. sneak must be OFF while walking/sprinting (it forces Pose.CROUCHING and
        //        suppresses bounce/step and would fight the sprint). Set it only if asked. ---
        bot.setShiftKeyDown(false);

        // --- 6. jumping: stairs / ledge / stuck. jumpFromGround() does NOT check onGround(). ---
        boolean moved = Math.abs(bot.getX() - this.lastX) > 1.0E-4
                     || Math.abs(bot.getZ() - this.lastZ) > 1.0E-4;
        this.lastX = bot.getX();
        this.lastZ = bot.getZ();

        boolean wantJump = this.jumpThisTick;
        this.jumpThisTick = false;
        if (!moved) {
            if (++this.stuckTicks >= STUCK_JUMP_TICKS) {
                this.stuckTicks = 0;
                wantJump = true;
            }
        } else {
            this.stuckTicks = 0;
        }

        if (wantJump && bot.onGround()) {
            bot.jumpFromGround();            // public; sets deltaMovement.y = JUMP_STRENGTH (0.42)
        }
        // Vanilla-shaped alternative (consumed by aiStep's jump block next tick):
        //   bot.setJumping(true);   ... one tick later ...   bot.setJumping(false);
        // Keep it false at all other times: setJumping(false) also clears the private noJumpDelay.
        bot.setJumping(false);

        // --- 7. step-up assist: vanilla auto-steps up to maxUpStep while walking; nothing to do.
        //        For a 2-block wall you need a jump (step 6) or a real path planner.
    }

    /** Schedule a jump on the next tick (use for explicit "hop over this" requests). */
    public void requestJump() { this.jumpThisTick = true; }

    private void reset() {
        this.bot.zza = 0.0F;
        this.bot.xxa = 0.0F;
        this.bot.yya = 0.0F;
        this.bot.setJumping(false);
        this.bot.setSprinting(false);
        this.stuckTicks = 0;
    }
}
```

### 9.3 Why the yaw formula is `atan2(-dx, dz)`

`Entity.getInputVector` (`Entity.java:1384-1394`) computes, with `f = sin(yaw)`, `f1 = cos(yaw)`:
```
dx_world =  in.x * cos(yaw) - in.z * sin(yaw)
dz_world =  in.z * cos(yaw) + in.x * sin(yaw)
```
For pure forward input `(xxa, zza) = (0, 1)` this reduces to
`(dx_world, dz_world) = (-sin(yaw), cos(yaw))`. To travel toward `(dx, dz)` you need
`-sin(yaw) ∝ dx` and `cos(yaw) ∝ dz`, i.e. **`yaw = atan2(-dx, dz)`** in degrees. ✅ matches the
formula in the sketch. (This is the same convention vanilla uses: `getLookAngle()` from yaw returns
`(-sin(yaw)·cos(pitch), -sin(pitch), cos(yaw)·cos(pitch))`.)

### 9.4 Steering refinements worth adding

- **Diagonal steering:** if the yaw error is large, `yaw` jumps a lot in one tick. Since we set
  `yaw` directly (a real client also snaps yaw when the mouse moves), this is fine *mechanically*,
  but for looks, rate-limit it:
  ```java
  float delta = Mth.wrapDegrees(targetYaw - bot.getYRot());
  float step  = Mth.clamp(delta, -maxTurnPerTick, maxTurnPerTick);   // e.g. 30F
  bot.setYRot(bot.getYRot() + step);
  ```
  **[INFERRED]** A large instantaneous yaw change does not affect movement correctness
  (`moveRelative` uses the new yaw the very next tick), it only affects `yBodyRot` lerping and
  observer-visible rotation smoothness.
- **Blocked-by-wall detection:** `bot.horizontalCollision` (`Entity` public field) is set by
  `move(MoverType.SELF, …)` each tick. `if (bot.horizontalCollision && bot.onGround()) requestJump();`
  is a cheaper stuck test than distance deltas. Combine both.
- **`jumping` while in water** is what makes `Player.travel`'s swim code push you up
  (`Player.java:1529-1534`) — keep `setJumping(false)` out of the way when you actually want to
  swim upward.
- **Fall protection:** `setShiftKeyDown(true)` while descending prevents walking off ledges and
  fall damage (`Entity.java:2251-2265`). Toggle it when the drop ahead is > 3 blocks if you care.
- **Abilities:** do not enable `flying` for a walking bot (`Player.travel` would then ignore
  ground fall/gravity and preserve `y`). If you do want flight, follow §5.4 + drive
  `deltaMovement.y` yourself.

### 9.5 Arrival inside the last block

The driver above stops at `ARRIVE_RADIUS = 0.35` horizontally. Vanilla `Player` movement uses
`getInputVector` normalisation, so a 0.35-block error with `zza = 1.0` still produces a full-speed
step and can overshoot; the next tick reverses `zza` because the sign of `dx` flips, so it
oscillates by well under a block and **visually looks like a normal player settling onto a spot**.
[INFERRED] If you need tighter arrival, scale the impulse with the error:
```java
bot.zza = (float) Math.min(1.0, horiz / 0.6);      // ease in over the last 0.6 blocks
```
Note the input vector stays unit-normalised by `getInputVector` when `lengthSqr() > 1`, but for
`lengthSqr() < 1` it is used *as is* (scaled), which is exactly what makes easing work.

### 9.6 Vertical: jumping gaps, and the flying variant

- **Ground:** `bot.jumpFromGround()` only when `bot.onGround()`; horizontal carry-over is automatic
  because `jumpFromGround` preserves `deltaMovement.x/z` and adds the sprint kick
  (`LivingEntity.java:2128-2131`).
- **Flying (`abilities.flying = true`):** `travel()` preserves `y` from before the call
  (`Player.java:1537-1543`) and the `aiStep` jump block is skipped (`isAffectedByFluids() == false`).
  So drive Y directly:
  ```java
  Abilities ab = bot.getAbilities();
  if (ab.flying) {
      double climb = Mth.clamp((target.y - bot.getY()) * 0.3, -0.3, 0.3);
      Vec3 dm = bot.getDeltaMovement();
      bot.setDeltaMovement(dm.x, climb, dm.z);   // travel() will keep y*0.6 after the move
  }
  ```
  (`setDeltaMovement(double,double,double)` is public, `Entity.java:3312`.)

---

## 10. Reference index (file:line)

| Item | Location |
|---|---|
| `xxa` / `yya` / `zza` / `jumping` declarations | `LivingEntity.java:217-220` |
| `noJumpDelay` declaration | `LivingEntity.java:237` |
| `SPEED_MODIFIER_SPRINTING` (+30%) | `LivingEntity.java:140-142` |
| `jumpFromGround()` | `LivingEntity.java:2122-2136` |
| `getJumpPower()` | `LivingEntity.java:2110-2116` |
| `travel(Vec3)` | `LivingEntity.java:2161` |
| `travel` ground branch | `LivingEntity.java:2264-2284` |
| `handleRelativeFrictionAndCalculateMovement` | `LivingEntity.java:2325-2336` |
| `getFrictionInfluencedSpeed` | `LivingEntity.java:2370-2372` |
| `LivingEntity.tick()` → `aiStep()` | `LivingEntity.java:2392-2433` (`aiStep` call at `:2432`) |
| `LivingEntity.aiStep()` | `LivingEntity.java:2660` |
| jump block (`jumping` + `noJumpDelay`) | `LivingEntity.java:2711-2741` |
| `xxa *= 0.98F; zza *= 0.98F;` | `LivingEntity.java:2745-2746` |
| `new Vec3(xxa, yya, zza)` + `travel(vec31)` | `LivingEntity.java:2749`, `:2760` |
| `isImmobile()` zeroes inputs | `LivingEntity.java:2700-2703`, `:2080-2082`; `Player.java:1101-1104` |
| `setJumping(boolean)` | `LivingEntity.java:2950-2952` |
| `setSprinting(boolean)` (attribute modifier) | `LivingEntity.java:2060-2068` |
| `getYHeadRot()` / `setYHeadRot` / `setYBodyRot` | `LivingEntity.java:3005-3017` |
| `serverAiStep()` (empty) | `LivingEntity.java:2827-2828` |
| `yBodyRotO/yHeadRotO/yRotO/xRotO` refresh | `LivingEntity.java:494-498`; `Entity.java:440-441` |
| `yRot`/`xRot`/`yRotO`/`xRotO` declarations | `Entity.java:168-171` |
| `getYRot`/`setYRot`/`getXRot`/`setXRot` | `Entity.java:3424-3450` |
| `absMoveTo` / `absRotateTo` | `Entity.java:1403-1413` |
| `setOldPosAndRot` | `Entity.java:1448-1460` |
| `moveRelative` / `getInputVector` | `Entity.java:1379-1394` |
| `setShiftKeyDown` / `isShiftKeyDown` / `isCrouching` | `Entity.java:2243-2269` |
| `isSprinting` / `setSprinting` / `isSwimming` / `setSwimming` | `Entity.java:2271-2293` |
| `updateSwimming()` | `Entity.java:1246-1254` (called at `:451`) |
| `isControlledByLocalInstance()` / `isEffectiveAi()` | `Entity.java:3050-3056` |
| `Entity.tick()` / `baseTick()` | `Entity.java:424-441` |
| `setDeltaMovement` | `Entity.java:3304-3316` |
| `setPose` | `Entity.java:365` |
| `Player.tick()` | `Player.java:253` (pre-event `:254`, post-event `:332`) |
| `Player.aiStep()` / `setSpeed(attr)` | `Player.java:527-549` |
| `Player.serverAiStep()` (`yHeadRot = getYRot()`) | `Player.java:519-524` |
| `Player.travel` (swim bob + flying) | `Player.java:1524-1547` |
| `Player.jumpFromGround` (stats + exhaustion) | `Player.java:1513-1522` |
| `Player.updateSwimming` (fly ⇒ false) | `Player.java:1549-1556` |
| `Player.getSpeed()` = attribute | `Player.java:1562-1565` |
| `Player.updatePlayerPose()` | `Player.java:411-440` |
| `Player.isAffectedByFluids()` | `Player.java:1106-1108` |
| `Player.canSprint()` | `Player.java:2198-2200` |
| `Player.getFlyingSpeed()` | `Player.java:2203-2209` |
| `Player.isAlwaysTicking()` | `Player.java:2166-2169` |
| `Player.isLocalPlayer()` = `false` | `Player.java:1421-1423` |
| `Player` attribute defaults | `Player.java:225-238` |
| `ServerPlayer.tick()` (no physics) | `ServerPlayer.java:494-531` |
| `ServerPlayer.doTick()` | `ServerPlayer.java:553` |
| `ServerPlayer.setPlayerInput(...)` (passenger-only!) | `ServerPlayer.java:1220-1233` |
| `ServerPlayer.travel(Vec3)` override | `ServerPlayer.java:1235-1242` |
| `connection` field | `ServerPlayer.java:177` |
| `getAbilities().flying && !mayFly()` revoke | `ServerPlayer.java:613-616` |
| `onUpdateAbilities()` | `ServerPlayer.java:1538-1544` |
| `getKnownMovement()` / `setKnownMovement` | `ServerPlayer.java:2103-2110` |
| `ServerGamePacketListenerImpl.tick()` + rewind | `ServerGamePacketListenerImpl.java:249-313` (rewind at `:261`) |
| `resetPosition()` | `ServerGamePacketListenerImpl.java:325-332` |
| `handlePlayerInput` | `ServerGamePacketListenerImpl.java:370-374` |
| `handleAcceptTeleportPacket` | `ServerGamePacketListenerImpl.java:492-517` |
| `handleMovePlayer` | `ServerGamePacketListenerImpl.java:856-986` |
| `updateAwaitingTeleport()` | `ServerGamePacketListenerImpl.java:988-1006` |
| `teleport(double,double,double,float,float[,Set])` | `ServerGamePacketListenerImpl.java:1022-1041` |
| `handlePlayerCommand` (sprint/sneak from packets) | `ServerGamePacketListenerImpl.java:1444-1459` |
| `keepConnectionAlive()` | `ServerCommonPacketListenerImpl.java:134-149` |
| `ServerCommonPacketListenerImpl.send` | `ServerCommonPacketListenerImpl.java:172-194` |
| `Connection` ctor / `isConnected` / `send` queue | `Connection.java:113-115`, `:583-585`, `:336-343` |
| `Connection.tick()` | `Connection.java:409-414` |
| `ServerConnectionListener.tick()` | `ServerConnectionListener.java:151-159` |
| `MinecraftServer.tickChildren` | `MinecraftServer.java:1018-1053` |
| `ServerLevel.tick` entity loop | `ServerLevel.java:400-426` |
| `ServerLevel.tickNonPassenger` | `ServerLevel.java:766-782` |
| `ServerLevel.addNewPlayer` / `addPlayer` | `ServerLevel.java:909-928` |
| `ServerLevel.EntityCallbacks.onTickingStart` | `ServerLevel.java:1734-1736` |
| `PersistentEntitySectionManager.addEntityWithoutEvent` | `PersistentEntitySectionManager.java:83-110` |
| `Attributes.JUMP_STRENGTH` default 0.42 | `Attributes.java:53-55` |
| `Abilities` fields | `player/Abilities.java:5-14` |
| `IPlayerExtension.mayFly()` | `neoforged/.../IPlayerExtension.java:87-90` |
| `NeoForgeMod.CREATIVE_FLIGHT` | `neoforged/neoforge/common/NeoForgeMod.java:212` |
| `FakePlayer` (do NOT copy for movement) | `neoforged/neoforge/common/util/FakePlayer.java:97-388` |
| `PathNavigation` (`Mob`-typed) | `ai/navigation/PathNavigation.java:29-56`, `:157-224` |
| `GroundPathNavigation` | `ai/navigation/GroundPathNavigation.java:19-22` |
| `MoveControl.tick()` | `ai/control/MoveControl.java:57-77` |
| `Mob.getNavigation()` / `Mob.setZza/setYya/setXxa` | `Mob.java:212-214`, `:532-542` |
| `Mob.serverAiStep` navigation tick | `Mob.java:781-798` |
| `ServerEntity` rotation packet fields | `server/level/ServerEntity.java:59-61`, `:121-217` |
| NeoForge `PlayerTickEvent` javadoc (doTick source) | `neoforged/neoforge/event/tick/PlayerTickEvent.java` |

---

## 11. Open questions / explicit uncertainty

1. **[UNVERIFIED — highest priority]** The §6.4 rewind analysis is from source + bytecode
   inspection, not from a running server. **Before building the driver, empirically confirm** by
   logging `bot.getX()` immediately after `doTick()` and again after `tick()` returns, on a bot
   with a default `ServerGamePacketListenerImpl`. If the two differ, diagnosis confirmed.
2. **[UNVERIFIED]** Whether the removed `xo/yo/zo` pre-write (`:257-259`) and the removed
   `tickCount++` (`:262`) cause observable regressions in entity tracking / interpolation for
   observers. Needs an in-game check.
3. **[UNVERIFIED]** `ServerLevel.tick`'s `inEntityTickingRange` gate (`ServerLevel.java:408`) for a
   bot registered by a non-standard path. Confirm the bot's chunk is entity-ticking, or force a
   ticket.
4. **[UNVERIFIED]** Whether a `Connection` with `channel == null` causes any exception inside the
   parts of `ServerPlayer.doTick()`/`ServerGamePacketListenerImpl` that call `connection.send`
   (they are guarded only by `isConnected()` inside `Connection.send`, so they should merely queue
   — but they queue *before* our override if we don't override `send`).
5. **[UNVERIFIED]** Reusability of `PathFinder`/`WalkNodeEvaluator`/`NodeEvaluator` without a `Mob`
   — flagged as a separate, unstarted investigation (§8.3).
6. **[VERIFIED, no uncertainty]** `setPlayerInput` is unusable for a walking bot; `noJumpDelay` is
   `private` and cannot be set; `Player` has no `PathNavigation`; `ServerPlayer` has no
   `lastSentYRot`.
