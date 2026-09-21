# Server-Side Item / Recipe Knowledge Layer — Exact API Contract

**Target:** Minecraft 1.21.1 + NeoForge 21.1.248
**Sources:** `/ymtc/Repos/.mcai-scratch/mcsrc/` (decompiled), verified against
`compiledWithNeoForge_1d60623121501ccf2b8473495df0278dc2b9ed66_output.jar` via `javap -p`.
**Status:** every signature below was copied from source. Uncertain points are flagged **[UNCERTAIN]**.

---

## 0. Headline corrections to the task brief

Three assumptions in the brief are **wrong for 1.21.1** and would not compile:

| Assumed | Reality in 1.21.1 |
|---|---|
| `RecipeManager.getRecipeMap()` returning `RecipeMap<RecipeHolder<?>>` | **Does not exist.** `RecipeMap` does not exist in this version (`javap` → `Error: class not found: net.minecraft.world.item.crafting.RecipeMap`). The backing state is two **private** fields: `Multimap<RecipeType<?>, RecipeHolder<?>> byType` and `Map<ResourceLocation, RecipeHolder<?>> byName`. Public iteration is `getRecipes()` / `getOrderedRecipes()`. See §2. **Private fields, no reflection needed** — use the public getters. |
| `Ingredient` backed by `HolderSet` / `ValueInput` | **Wrong for 1.21.1.** That is the 1.21.2+ design. Here `Ingredient` holds `private final Ingredient.Value[] values` where `Value` is a sealed-ish union of `ItemValue(ItemStack)` and `TagValue(TagKey<Item>)`. See §4. |
| `TransmuteRecipe` | **Does not exist in 1.21.1** (added in 1.21.2). Confirmed: no `Transmute*` file in the tree. |

Also: `Recipe` is **not** a `record`/class — it is `interface Recipe<T extends RecipeInput>`, and
the concrete-type list differs slightly from the brief (§3).

---

## 1. Getting the `RecipeManager` + lifecycle

### 1.1 Accessors

`net/minecraft/server/MinecraftServer.java:1684`
```java
public RecipeManager getRecipeManager() {
    return this.resources.managers.getRecipeManager();
}
```

`net/minecraft/server/level/ServerLevel.java:1335`
```java
public RecipeManager getRecipeManager() {
    return this.server.getRecipeManager();
}
```

`net/minecraft/world/level/Level.java:1137` (abstract, implemented by `ServerLevel` and `ClientLevel`)
```java
public abstract RecipeManager getRecipeManager();
```

`net/minecraft/server/ReloadableServerResources.java:64`
```java
public RecipeManager getRecipeManager() {
    return this.recipes;
}
```

So from anywhere with a `ServerLevel`: `serverLevel.getRecipeManager()`. From a `MinecraftServer`:
`server.getRecipeManager()`. Both return the **same live instance** — the `RecipeManager` object is
created once per `ReloadableServerResources` and *replaced* (not mutated) on datapack reload.

`net/minecraft/locale/...` not relevant here; but note
`net/neoforged/neoforge/common/extensions/ICommandSourceStackExtension.java:40`
```java
default RecipeManager getRecipeManager() {
    return self().getServer().getRecipeManager();
}
```
— useful from a command source.

### 1.2 Construction and population

`net/minecraft/world/item/crafting/RecipeManager.java:36-47`
```java
public class RecipeManager extends SimpleJsonResourceReloadListener {
    private final HolderLookup.Provider registries;
    private Multimap<RecipeType<?>, RecipeHolder<?>> byType = ImmutableMultimap.of();
    private Map<ResourceLocation, RecipeHolder<?>> byName = ImmutableMap.of();
    private boolean hasErrors;

    public RecipeManager(HolderLookup.Provider p_324137_) {
        super(GSON, Registries.elementsDirPath(Registries.RECIPE));
        this.registries = p_324137_;
    }
```

`apply` is the population point — `RecipeManager.java:49-77`:
```java
protected void apply(Map<ResourceLocation, JsonElement> p_44037_, ResourceManager p_44038_, ProfilerFiller p_44039_) {
    this.hasErrors = false;
    Builder<RecipeType<?>, RecipeHolder<?>> builder = ImmutableMultimap.builder();
    com.google.common.collect.ImmutableMap.Builder<ResourceLocation, RecipeHolder<?>> builder1 = ImmutableMap.builder();
    RegistryOps<JsonElement> registryops = this.makeConditionalOps(); // Neo: add condition context

    for (Entry<ResourceLocation, JsonElement> entry : p_44037_.entrySet()) {
        ResourceLocation resourcelocation = entry.getKey();
        if (resourcelocation.getPath().startsWith("_")) continue; //Forge: filter anything beginning with "_" as it's used for metadata.

        try {
            var decoded = Recipe.CONDITIONAL_CODEC.parse(registryops, entry.getValue()).getOrThrow(JsonParseException::new);
            decoded.ifPresentOrElse(r -> {
            Recipe<?> recipe = r.carrier();
            RecipeHolder<?> recipeholder = new RecipeHolder<>(resourcelocation, recipe);
            builder.put(recipe.getType(), recipeholder);
            builder1.put(resourcelocation, recipeholder);
            }, () -> {
                LOGGER.debug("Skipping loading recipe {} as its conditions were not met", resourcelocation);
            });
        } catch (IllegalArgumentException | JsonParseException jsonparseexception) {
            LOGGER.error("Parsing error loading recipe {}", resourcelocation, jsonparseexception);
        }
    }

    this.byType = builder.build();
    this.byName = builder1.build();
    LOGGER.info("Loaded {} recipes", this.byType.size());
}
```

Key consequences:
- `apply` **assigns** `this.byType` / `this.byName` to fresh immutable collections. A snapshot you
  hold across a reload becomes stale — see §1.4.
- NeoForge **conditions** are evaluated here: a recipe whose `neoforge:conditions` fail is silently
  dropped (`LOGGER.debug`), so it never appears in `byType`/`byName`. Your index therefore
  automatically reflects the *active* mod set, which is exactly what you want.
- `apply` is `protected`. You cannot call it; you observe the result.

### 1.3 WHEN recipes become available — exact ordering

Chain of evidence:

1. `MinecraftServer.runServer()` — `MinecraftServer.java:668-675`:
   ```java
   protected void runServer() {
       try {
           if (!this.initServer()) {
               throw new IllegalStateException("Failed to initialize server");
           }

           net.neoforged.neoforge.server.ServerLifecycleHooks.handleServerStarted(this);
   ```
2. `initServer()` → `loadLevel()` → builds `WorldStem` via `WorldLoader.load(...)`.
3. `WorldLoader.java:49-66` — resources are loaded, **then** tags are bound **before** the server object is created:
   ```java
   return ReloadableServerResources.loadResources(
           closeableresourcemanager,
           layeredregistryaccess2,
           worlddataconfiguration.enabledFeatures(),
           p_214363_.commandSelection(),
           p_214363_.functionCompilationLevel(),
           p_214366_,
           p_214367_
       )
       .whenComplete((p_214370_, p_214371_) -> {
           if (p_214371_ != null) {
               closeableresourcemanager.close();
           }
       })
       .thenApplyAsync(p_335216_ -> {
           p_335216_.updateRegistryTags();
           return p_214365_.create(closeableresourcemanager, p_335216_, layeredregistryaccess2, dataloadoutput.cookie);
       }, p_214367_);
   ```
4. `ReloadableServerResources.loadResources(...)` — `ReloadableServerResources.java:98-137` — creates the
   `RecipeManager` and runs it through `SimpleReloadInstance`:
   ```java
   this.recipes = new RecipeManager(this.registryLookup);            // line 47 (ctor)
   ...
   return SimpleReloadInstance.create(
           p_248588_, listeners, p_249136_, p_249601_, DATA_RELOAD_INITIAL_TASK, LOGGER.isDebugEnabled()
       )
       .done()  ...
   ```

**Conclusion — the `RecipeManager` is fully populated by the time `WorldLoader` produces the
`WorldStem`, i.e. strictly before `MinecraftServer`'s constructor body runs and long before
`ServerStartedEvent`.** Ordering:

```
ReloadableServerResources.loadResources()   (recipes applied here, async)
        ↓ thenApplyAsync
updateRegistryTags()                        (ITEM tags bound here — MappedRegistry.bindTags)
        ↓
new MinecraftServer(...)                    (this.resources = new ReloadableResources(...))
        ↓
DedicatedServer.initServer()
        ↓
ServerAboutToStartEvent                     (ServerLifecycleHooks.handleServerAboutToStart)
        ↓
ServerStartingEvent                         (handleServerStarting → LanguageHook.loadModLanguages)
        ↓
ServerStartedEvent                          (handleServerStarted)
```

**[UNCERTAIN — must be tested]** Whether recipes are usable *during* your own reload listener's
`apply`, and in particular whether **item tags are bound** yet at that moment. Evidence pointing
both ways:

- `ReloadableServerResources.loadResources` injects a `ConfigurableRegistryLookup` with
  `MissingTagAccessPolicy.CREATE_NEW` before the reload and flips it to `FAIL` in `whenComplete`
  (`ReloadableServerResources.java:46`, `123-125`). In `CREATE_NEW` mode
  `createDispatchedLookup(...).parent()` returns `asTagAddingLookup()`
  (`Registry.java:200-217`), whose `getTag` **auto-creates an empty** `HolderSet.Named` rather than
  failing. So `Ingredient.TagValue.getItems()` (which uses `BuiltInRegistries.ITEM.getTagOrEmpty`,
  `Ingredient.java:292`) can legitimately resolve to empty **and the empty set gets cached into
  `Ingredient.itemStacks` permanently** (`Ingredient.java:94-105`) during that window.
- `updateRegistryTags()` → `bindTags` runs only *after* the whole reload completes
  (`WorldLoader.java:64` initial start; `MinecraftServer.java:1511` on `/reload`).
- Mitigating: `MappedRegistry.bindTags` rebinds the *same* `HolderSet.Named` objects
  (`MappedRegistry.java:420-425`), so previously-empty sets do get contents later — but
  `Ingredient.getItems()` has already cached an empty `itemStacks` array and will **not** recompute.

**Recommendation:** do **not** build the index inside a `PreparableReloadListener`'s `apply`.
Build it from a place guaranteed to be after `updateRegistryTags()`. Ranked options:

1. **`TagsUpdatedEvent` with `UpdateCause.SERVER_DATA_LOAD`** — the canonical "tags are now bound" hook.
   `net/neoforged/neoforge/event/TagsUpdatedEvent.java:34`
   ```java
   public TagsUpdatedEvent(RegistryAccess registryAccess, boolean fromClientPacket, boolean isIntegratedServerConnection)
   public RegistryAccess getRegistryAccess()
   public UpdateCause getUpdateCause()
   public boolean shouldUpdateStaticData()
   public enum UpdateCause { SERVER_DATA_LOAD, CLIENT_PACKET_RECEIVED }
   ```
   Fired at the end of `ReloadableServerResources.updateRegistryTags()`:
   `ReloadableServerResources.java:143`
   ```java
   net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.TagsUpdatedEvent(this.fullRegistryHolder.get(), false, false));
   ```
   (initial start) and from the same method on `/reload`
   (`MinecraftServer.java:1511`). **This fires on every recipe+t tag reload, before the server is
   constructed on first boot** — which is fine, because you only need the `RecipeManager` handle at
   query time, not at index time. Guard the first-boot case where `ServerLifecycleHooks.getCurrentServer()`
   is still `null`.
2. **`OnDatapackSyncEvent`** — `net/neoforged/neoforge/event/OnDatapackSyncEvent.java:24`. Fired
   *after* recipes and tags are loaded and *before* they are sent to clients
   (`PlayerList.java:205` on join, `PlayerList.java:902` on `/reload`). Its own javadoc:
   *"Fires when a player joins the server or when the reload command is ran, before tags and crafting
   recipes are sent to the client."* Good for lazy/first-player-triggered building, and it is
   guaranteed post-`updateRegistryTags`.
3. **`ServerStartedEvent`** (`net/neoforged/neoforge/event/server/ServerStartedEvent.java`) — simplest
   and safest for a one-shot warm-up on boot, but it does **not** fire on `/reload`; pair it with (1).

### 1.4 Reload notification and staleness

`MinecraftServer.reloadResources(...)` — `MinecraftServer.java:1477-1525`. The important part:
```java
.thenAcceptAsync(
    p_335203_ -> {
        this.resources.close();
        this.resources = p_335203_;
        this.packRepository.setSelected(p_129862_);
        ...
        this.resources.managers.updateRegistryTags();
        this.getPlayerList().saveAll();
        this.getPlayerList().reloadResources();
        ...
    },
    this
);
```

So on `/reload` a **brand new `RecipeManager` object** replaces the old one, and
`updateRegistryTags()` (and therefore `TagsUpdatedEvent`) fires on the server thread *after* the swap.

**Therefore: never cache the `RecipeManager` reference or hold `RecipeHolder` objects long-term.**
Always fetch `server.getRecipeManager()` at query time, or key your cache by the `RecipeManager`
identity and rebuild when it changes.

`/reload` also invalidates the client's view; `PlayerList.reloadResources()`
(`PlayerList.java:897-910`) pushes `ClientboundUpdateRecipesPacket(this.server.getRecipeManager().getOrderedRecipes())`
to every player — a useful confirmation that `getOrderedRecipes()` is the canonical "everything" list.

### 1.5 NeoForge reload-listener hook (if you do choose a listener)

`net/neoforged/neoforge/event/AddReloadListenerEvent.java:31-76`
```java
public class AddReloadListenerEvent extends Event {
    public AddReloadListenerEvent(ReloadableServerResources serverResources, RegistryAccess registryAccess)
    public void addListener(PreparableReloadListener listener)
    public List<PreparableReloadListener> getListeners()
    public ReloadableServerResources getServerResources()
    public ICondition.IContext getConditionContext()
    public RegistryAccess getRegistryAccess()
}
```
Fired from `EventHooks.onResourceReload(...)` (`EventHooks.java:818-819`), appended **after** the vanilla
listeners, and dispatched in registration order by `SimpleReloadInstance`
(`SimpleReloadInstance.java:60-93` — each listener's barrier chains off the previous one's future).
`ContextAwareReloadListener` (`net/neoforged/neoforge/resource/ContextAwareReloadListener.java`) gives
you `getContext()`, `getRegistryLookup()`, `makeConditionalOps()` if you need condition-aware loading.
**See the `[UNCERTAIN]` warning in §1.3 before relying on tags inside such a listener.**

---

## 2. Enumerating all recipes

`RecipeManager` is **not** backed by a public `RecipeMap` in 1.21.1. Exact public surface:

`net/minecraft/world/item/crafting/RecipeManager.java:147-157`
```java
public Collection<RecipeHolder<?>> getOrderedRecipes() {
    return this.byType.values();
}

public Collection<RecipeHolder<?>> getRecipes() {
    return this.byName.values();
}

public Stream<ResourceLocation> getRecipeIds() {
    return this.byName.keySet().stream();
}
```

| Method | Backing field | Ordering | Duplicates | Use for |
|---|---|---|---|---|
| `getOrderedRecipes()` | `byType.values()` (`Multimap`) | grouped by `RecipeType` | **yes** — a recipe appears once per type entry, and since each recipe has exactly one type, entries are unique but ordered by type | network sync, type-grouped iteration |
| `getRecipes()` | `byName.values()` (`Map`) | map order | no | index building — **use this** |
| `getRecipeIds()` | `byName.keySet().stream()` | map order | no | cheap id-only scans |

Both return `Collection<RecipeHolder<?>>` — **already wildcard-typed**, so no cast is needed to iterate
everything regardless of type:

```java
for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
    ResourceLocation id = holder.id();
    Recipe<?> recipe = holder.value();
    RecipeType<?> type = recipe.getType();
    // ...
}
```

### 2.1 `RecipeHolder` accessors

`net/minecraft/world/item/crafting/RecipeHolder.java:7-10`
```java
public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id, T value) {
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeHolder<?>> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC, RecipeHolder::id, Recipe.STREAM_CODEC, RecipeHolder::value, RecipeHolder::new
    );
```
`id()` → `ResourceLocation`; `value()` → `T`. Note `equals`/`hashCode` are **id-only**
(`RecipeHolder.java:12-28`), so a `RecipeHolder` is a safe map key and a `Set<RecipeHolder<?>>`
deduplicates by id.

### 2.2 Targeted lookups

`RecipeManager.java:137-145`
```java
public Optional<RecipeHolder<?>> byKey(ResourceLocation p_44044_) {
    return Optional.ofNullable(this.byName.get(p_44044_));
}

@Nullable
private <T extends Recipe<?>> RecipeHolder<T> byKeyTyped(RecipeType<T> p_341695_, ResourceLocation p_341666_) {
```

`RecipeManager.java:106-116`
```java
public <I extends RecipeInput, T extends Recipe<I>> List<RecipeHolder<T>> getAllRecipesFor(RecipeType<T> p_44014_) {
    return List.copyOf(this.byType(p_44014_));
}

public <I extends RecipeInput, T extends Recipe<I>> List<RecipeHolder<T>> getRecipesFor(RecipeType<T> p_44057_, I p_346353_, Level p_44059_) {
    return this.byType(p_44057_)
        .stream()
        .filter(p_344410_ -> p_344410_.value().matches(p_346353_, p_44059_))
        .sorted(Comparator.comparing(p_335290_ -> p_335290_.value().getResultItem(p_44059_.registryAccess()).getDescriptionId()))
        .collect(Collectors.toList());
}
```

`byType(...)` is **private** (`RecipeManager.java:118-120`) and returns the raw `Multimap` bucket —
which returns an **empty collection, not null**, for an unknown type (Guava `Multimap.get` semantics).
That is why `getAllRecipesFor` is safe for modded types that registered no recipes.

Iterating only the types that actually have recipes:
```java
for (RecipeType<?> type : new java.util.LinkedHashSet<>(
        server.getRecipeManager().getOrderedRecipes().stream().map(h -> h.value().getType()).toList())) {
    // BuiltInRegistries.RECIPE_TYPE.getKey(type) -> ResourceLocation
}
```

---

## 3. Recipe introspection

### 3.1 The `Recipe` interface — `net/minecraft/world/item/crafting/Recipe.java:16-71`

```java
public interface Recipe<T extends RecipeInput> {
    Codec<Recipe<?>> CODEC = BuiltInRegistries.RECIPE_SERIALIZER.byNameCodec().dispatch(Recipe::getSerializer, RecipeSerializer::codec);
    Codec<java.util.Optional<net.neoforged.neoforge.common.conditions.WithConditions<Recipe<?>>>> CONDITIONAL_CODEC = net.neoforged.neoforge.common.conditions.ConditionalOps.createConditionalCodecWithConditions(CODEC);
    StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> STREAM_CODEC = ByteBufCodecs.registry(Registries.RECIPE_SERIALIZER)
        .dispatch(Recipe::getSerializer, RecipeSerializer::streamCodec);

    boolean matches(T p_346065_, Level p_345375_);

    ItemStack assemble(T p_345149_, HolderLookup.Provider p_346030_);

    boolean canCraftInDimensions(int p_43999_, int p_44000_);

    ItemStack getResultItem(HolderLookup.Provider p_336125_);

    default NonNullList<ItemStack> getRemainingItems(T p_345383_) { ... }

    default NonNullList<Ingredient> getIngredients() {
        return NonNullList.create();
    }

    default boolean isSpecial() {
        return false;
    }

    default boolean showNotification() {
        return true;
    }

    default String getGroup() {
        return "";
    }

    default ItemStack getToastSymbol() {
        return new ItemStack(Blocks.CRAFTING_TABLE);
    }

    RecipeSerializer<?> getSerializer();

    RecipeType<?> getType();

    default boolean isIncomplete() {
        NonNullList<Ingredient> nonnulllist = this.getIngredients();
        return nonnulllist.isEmpty() || nonnulllist.stream().anyMatch(Ingredient::hasNoItems);
    }
}
```

> ⚠️ **`getIngredients()` returns an EMPTY list for all `CustomRecipe` subclasses.**
> `ArmorDyeRecipe`, `RepairItemRecipe`, `FireworkStarRecipe`, `MapExtendingRecipe`,
> `ShulkerBoxColoring`, `SuspiciousStewRecipe`, `DecoratedPotRecipe`, `BookCloningRecipe` and the
> other "special" recipes **do not override `getIngredients()`** — verified by grep (0 hits in each).
> For these you get only `isSpecial() == true` and an empty input list. **Any inverted
> `item -> recipes using it` index will silently miss every special recipe.** You must either
> (a) hard-code a small special-case table for vanilla specials, or (b) accept the gap and mark
> such recipes in the JSON as `"special": true, "inputs": []`. Do not silently claim completeness.

`assemble` and `getResultItem` take a `HolderLookup.Provider` — `RegistryAccess extends HolderLookup.Provider`
(`RegistryAccess.java:15`), so `level.registryAccess()` (`Level.java:1186`) or
`server.registryAccess()` (`MinecraftServer.java:1947`) is the natural argument.

`RecipeInput` types (all in `net/minecraft/world/item/crafting/`):
```java
// RecipeInput.java:5-18
public interface RecipeInput {
    ItemStack getItem(int p_346128_);
    int size();
    default boolean isEmpty() { ... }
}
// SingleRecipeInput.java:5
public record SingleRecipeInput(ItemStack item) implements RecipeInput { ... size() == 1 }
// SmithingRecipeInput.java:5
public record SmithingRecipeInput(ItemStack template, ItemStack base, ItemStack addition) implements RecipeInput { ... size() == 3 }
// CraftingInput.java:8
public class CraftingInput implements RecipeInput { ... }
```
`CraftingInput` construction — `CraftingInput.java:32-34`:
```java
public static CraftingInput of(int p_346122_, int p_344877_, List<ItemStack> p_345183_) {
    return ofPositioned(p_346122_, p_344877_, p_345183_).input();
}
```
plus `getItem(int,int)` (`:89`), `size()`, `isEmpty()`, `stackedContents()`, `items()`,
`ingredientCount()`, `width()`, `height()`.

### 3.2 `ShapedRecipe` — `net/minecraft/world/item/crafting/ShapedRecipe.java`

```java
public class ShapedRecipe implements CraftingRecipe {          // :14
    public final ShapedRecipePattern pattern;                   // :15  <-- public
    final ItemStack result;                                     // :16
    final String group;                                         // :17
    final CraftingBookCategory category;                        // :18
    final boolean showNotification;                             // :19

    public int getWidth()  { return this.pattern.width(); }     // :76-78
    public int getHeight() { return this.pattern.height(); }    // :80-82
```
Inputs/outputs:
- Pattern / key / dimensions: `recipe.pattern` is **public**, so
  `recipe.pattern.width()`, `.height()`, `.ingredients()`, `.data()`, `.ingredientCount()`, `.symmetrical()`.
- **The original character grid and `Map<Character, Ingredient>` key** live in
  `ShapedRecipePattern.Data` — `ShapedRecipePattern.java:238-273`:
  ```java
  public static record Data(Map<Character, Ingredient> key, List<String> pattern) { ... }
  ```
  Reachable via `ShapedRecipePattern.data()` → `Optional<ShapedRecipePattern.Data>`
  (`ShapedRecipePattern.java:58`, populated when decoded from JSON; `Optional.empty()` when built
  from the network — see `fromNetwork` at `:218-224`). **On a dedicated server, recipes come from
  JSON, so `data()` is present.** Use `.orElse(null)` defensively.
- Result: `getResultItem(HolderLookup.Provider)` (no-arg `result` field is package-private → not
  reachable from your package).
- **NeoForge expands the grid:** `ShapedRecipePattern.maxWidth` / `maxHeight` are mutable statics
  with `getMaxWidth()`, `getMaxHeight()`, `setCraftingSize(int,int)` (`:25-45`) — mods with large
  crafting tables bump these. Never hard-code 3×3.

### 3.3 `ShapelessRecipe` — `ShapelessRecipe.java:16-79`
```java
public class ShapelessRecipe implements CraftingRecipe {
    final String group; final CraftingBookCategory category; final ItemStack result;
    final NonNullList<Ingredient> ingredients;   // package-private
    private final boolean isSimple;

    @Override public NonNullList<Ingredient> getIngredients() { return this.ingredients; }   // :52-54
    @Override public ItemStack getResultItem(HolderLookup.Provider p_335606_) { return this.result; }  // :47-49
    @Override public boolean canCraftInDimensions(int p_44252_, int p_44253_) {
        return p_44252_ * p_44253_ >= this.ingredients.size();
    }
```
Inputs via `getIngredients()`; order is **not** significant (matching uses
`RecipeMatcher.findMatches` for non-simple ingredients — `ShapelessRecipe.java:64`).

### 3.4 `AbstractCookingRecipe` — `AbstractCookingRecipe.java:8-74`
```java
public abstract class AbstractCookingRecipe implements Recipe<SingleRecipeInput> {
    protected final RecipeType<?> type;
    protected final CookingBookCategory category;
    protected final String group;
    protected final Ingredient ingredient;
    protected final ItemStack result;
    protected final float experience;
    protected final int cookingTime;

    public boolean matches(SingleRecipeInput p_344849_, Level p_345973_) {
        return this.ingredient.test(p_344849_.item());
    }

    @Override public NonNullList<Ingredient> getIngredients() { /* single-element list */ }   // :43-47
    public float getExperience() { return this.experience; }                                  // :49-51
    public int getCookingTime() { return this.cookingTime; }                                  // :63-65
    @Override public RecipeType<?> getType() { return this.type; }                            // :68-70
    public CookingBookCategory category() { return this.category; }                           // :72-74
    public interface Factory<T extends AbstractCookingRecipe> {                               // :76-78
        T create(String p_312581_, CookingBookCategory p_312220_, Ingredient p_312282_, ItemStack p_311868_, float p_312803_, int p_312165_);
    }
}
```
Subclasses (all take the same 6-arg constructor shape):
```java
// SmeltingRecipe.java:6
public SmeltingRecipe(String p_250200_, CookingBookCategory p_251114_, Ingredient p_250340_, ItemStack p_250306_, float p_249577_, int p_250030_)
// BlastingRecipe.java, SmokingRecipe.java, CampfireCookingRecipe.java — same pattern
```
Cooking time / experience are `protected` fields but exposed by the public getters above.
Default times come from the serializers (`RecipeSerializer.java`):
`SMELTING_RECIPE` 200, `BLASTING_RECIPE` 100, `SMOKING_RECIPE` 100, `CAMPFIRE_COOKING_RECIPE` 100.

### 3.5 `StonecutterRecipe` — `StonecutterRecipe.java:7-19`
```java
public class StonecutterRecipe extends SingleItemRecipe {
    public StonecutterRecipe(String p_44479_, Ingredient p_44480_, ItemStack p_302318_) {
        super(RecipeType.STONECUTTING, RecipeSerializer.STONECUTTER, p_44479_, p_44480_, p_302318_);
    }
    public boolean matches(SingleRecipeInput p_344927_, Level p_345392_) {
        return this.ingredient.test(p_344927_.item());
    }
    @Override public ItemStack getToastSymbol() { return new ItemStack(Blocks.STONECUTTER); }
}
```
Base `SingleItemRecipe.java:14-63`:
```java
public abstract class SingleItemRecipe implements Recipe<SingleRecipeInput> {
    protected final Ingredient ingredient;
    protected final ItemStack result;
    protected final String group;
    @Override public RecipeType<?> getType() { return this.type; }
    @Override public ItemStack getResultItem(HolderLookup.Provider p_336121_) { return this.result; }
    @Override public NonNullList<Ingredient> getIngredients() { /* single-element list */ }
    @Override public boolean canCraftInDimensions(int p_44424_, int p_44425_) { return true; }
    public ItemStack assemble(SingleRecipeInput p_345857_, HolderLookup.Provider p_335463_) { return this.result.copy(); }
}
```
**Note: `canCraftInDimensions` returns `true` unconditionally for both cooking and stonecutting** —
so it is **not** a validity filter for non-crafting recipes.

### 3.6 Smithing — `SmithingTransformRecipe` / `SmithingTrimRecipe`

`SmithingRecipe.java:6-24`
```java
public interface SmithingRecipe extends Recipe<SmithingRecipeInput> {
    @Override default RecipeType<?> getType() { return RecipeType.SMITHING; }
    @Override default boolean canCraftInDimensions(int p_266835_, int p_266829_) {
        return p_266835_ >= 3 && p_266829_ >= 1;
    }
    @Override default ItemStack getToastSymbol() { return new ItemStack(Blocks.SMITHING_TABLE); }
    boolean isTemplateIngredient(ItemStack p_266982_);
    boolean isBaseIngredient(ItemStack p_266962_);
    boolean isAdditionIngredient(ItemStack p_267132_);
}
```

`SmithingTransformRecipe.java:13-64` — fields are **package-private**, so use `getIngredients()`
which is the inherited default returning an empty list. **[UNCERTAIN / IMPORTANT]** Neither
`SmithingTransformRecipe` nor `SmithingTrimRecipe` overrides `getIngredients()` (verified: no
`getIngredients` occurrence in either file). **Smithing recipes therefore expose NO inputs through
the `Recipe` interface.** To index them you must either:
- match on `getSerializer() == RecipeSerializer.SMITHING_TRANSFORM / SMITHING_TRIM`, then rely on
  `isTemplateIngredient(ItemStack)` / `isBaseIngredient(ItemStack)` / `isAdditionIngredient(ItemStack)`
  — but these test *a given stack*, they do not **enumerate** the ingredient set; or
- reflect the package-private `template` / `base` / `addition` fields (fragile), or
- place the class in package `net.minecraft.world.item.crafting` (not acceptable for a mod), or
- **[BEST]** use an **access transformer / access widener** if your build already uses one, otherwise
  accept `"inputs": []` for smithing and note it in the JSON.

`SmithingTrimRecipe.getResultItem` is also **non-deterministic by design** —
`SmithingTrimRecipe.java:59-68` returns a sample `IRON_CHESTPLATE` carrying the *first* trim pattern
found in the registry plus `TrimMaterials.REDSTONE`, purely as a display icon. Its real output
depends on runtime `TrimMaterials.getFromIngredient(...)` / `TrimPatterns.getFromTemplate(...)`
(`:41-42`). **Do not present `getResultItem()` of a trim recipe to an LLM as the craft output** —
it is a placeholder.

### 3.7 `CustomRecipe` / special crafting recipes — `CustomRecipe.java:6-26`
```java
public abstract class CustomRecipe implements CraftingRecipe {
    private final CraftingBookCategory category;

    public CustomRecipe(CraftingBookCategory p_249010_) { this.category = p_249010_; }

    @Override public boolean isSpecial() { return true; }

    @Override public ItemStack getResultItem(HolderLookup.Provider p_336187_) { return ItemStack.EMPTY; }

    @Override public CraftingBookCategory category() { return this.category; }
}
```
Full list of subclasses present in 1.21.1 (`net/minecraft/world/item/crafting/`):
`ArmorDyeRecipe`, `BannerDuplicateRecipe`, `BookCloningRecipe`, `DecoratedPotRecipe`,
`FireworkRocketRecipe`, `FireworkStarFadeRecipe`, `FireworkStarRecipe`, `MapCloningRecipe`,
`MapExtendingRecipe`, `RepairItemRecipe`, `ShieldDecorationRecipe`, `ShulkerBoxColoring`,
`SuspiciousStewRecipe`, `TippedArrowRecipe`.

**Critical for your LLM JSON: for these, `getResultItem()` returns `ItemStack.EMPTY` and
`getIngredients()` returns an empty list.** Example — `ArmorDyeRecipe.java`: it declares `matches`
(`:13`) and `canCraftInDimensions` (`:70`) but **no** `getResultItem` override, so it inherits
`ItemStack.EMPTY`. Its real logic tests `ItemTags.DYEABLE` and `instanceof DyeItem` at runtime
(`:21-32`). Represent these as e.g.
`{"type":"minecraft:crafting","special":true,"id":"minecraft:armor_dye","inputs":[],"outputs":[]}`
and let the LLM fall back to `descriptionId`-based reasoning.

`CraftingRecipe.java`:
```java
public interface CraftingRecipe extends Recipe<CraftingInput> {
    @Override default RecipeType<?> getType() { return RecipeType.CRAFTING; }
    CraftingBookCategory category();
}
```
Every crafting recipe — shaped, shapeless, and all specials — reports
`getType() == RecipeType.CRAFTING`.

### 3.8 `TransmuteRecipe`
**NOT PRESENT in 1.21.1.** Confirmed by `find` (no file) and grep for `transmute` (only
`ItemStack.transmuteCopy` call sites, e.g. `ShulkerBoxColoring.java:61`, `GrindstoneMenu.java:201`).
Do not reference it.

### 3.9 NeoForge-added recipe types
**There are none.** `grep -rn "RecipeType.simple\|RecipeType.register\|BuiltInRegistries.RECIPE_TYPE" net/neoforged/`
returns **zero hits** — NeoForge 21.1.248 adds no `RecipeType` of its own.

NeoForge's recipe-adjacent server-side additions that *do* exist:
- `net/neoforged/neoforge/common/crafting/ICustomIngredient.java` — custom ingredient behaviour (§4).
- `net/neoforged/neoforge/common/crafting/` — `BlockTagIngredient`, `CompoundIngredient`,
  `DataComponentIngredient`, `DifferenceIngredient`, `IntersectionIngredient`, `SizedIngredient`,
  `IngredientType`, `IRecipeContainer`, `ConditionalRecipeOutput`, `CraftingHelper`.
- `net/neoforged/neoforge/common/brewing/BrewingRecipe.java` + `BrewingRecipeRegistry` — **brewing is
  NOT a `RecipeType`**; it is a separate registry. **It will not appear in `RecipeManager`.** If you
  want potion recipes in the knowledge layer, index `BrewingRecipeRegistry` separately.
- `net/neoforged/neoforge/common/util/RecipeMatcher.java` — the shapeless matcher used at
  `ShapelessRecipe.java:64`.
- `net/neoforged/neoforge/client/RecipeBookManager.java` — **client-only** (`net.neoforged.neoforge.client`),
  category lookup for the client recipe book. Not usable server-side.

---

## 4. `Ingredient` — structure and enumeration

`net/minecraft/world/item/crafting/Ingredient.java:30-61`
```java
public final class Ingredient implements Predicate<ItemStack> {
    public static final Ingredient EMPTY = new Ingredient(Stream.empty());
    public static final StreamCodec<RegistryFriendlyByteBuf, Ingredient> CONTENTS_STREAM_CODEC = ...;
    private final Ingredient.Value[] values;
    @Nullable private ItemStack[] itemStacks;
    @Nullable private IntList stackingIds;
    @Nullable private net.neoforged.neoforge.common.crafting.ICustomIngredient customIngredient = null;
```

> **This is the 1.21.1 design, NOT `HolderSet`/`ValueInput`.** `ValueInput` and `HolderSet`-backed
> ingredients arrive in 1.21.2. Here the union is `ItemValue | TagValue`, plus the NeoForge
> `ICustomIngredient` escape hatch.

### 4.1 Enumerating the `ItemStack`s it matches

`Ingredient.java:94-105` — **the single method you need**:
```java
public ItemStack[] getItems() {
    if (this.itemStacks == null) {
        // Neo: vanilla used Stream.distinct() here which has basically no effect as ItemStack does not override hashCode() and equals().
        // Using ItemStackLinkedSet::createTypeAndComponentsSet instead for a real distinct result.
        final Stream<ItemStack> stream = this.customIngredient == null
                ? Arrays.stream(this.values).flatMap(value -> value.getItems().stream())
                : this.customIngredient.getItems();
        this.itemStacks = stream.collect(java.util.stream.Collectors.toCollection(net.minecraft.world.item.ItemStackLinkedSet::createTypeAndComponentsSet)).toArray(ItemStack[]::new);
    }

    return this.itemStacks;
}
```
- Returns `ItemStack[]` (not a `Collection`), **cached** in `this.itemStacks`.
- **Performance note:** for a tag ingredient this materialises *every* item in the tag into an
  `ItemStack`. On a 258-mod pack a tag like `c:ingots` or `forge:ores` can hold hundreds of entries.
  For a full index pass over ~10k+ recipes this is the dominant cost — batch it and be prepared to
  cap or lazily expand.

Membership test — `Ingredient.java:107-123`:
```java
public boolean test(@Nullable ItemStack p_43914_) {
    if (p_43914_ == null) {
        return false;
    } else if (this.customIngredient != null) {
        return this.customIngredient.test(p_43914_);
    } else if (this.isEmpty()) {
        return p_43914_.isEmpty();
    } else {
        for (ItemStack itemstack : this.getItems()) {
            if (itemstack.is(p_43914_.getItem())) {
                return true;
            }
        }
        return false;
    }
}
```
⚠️ Note it compares by **`Item` only** (`itemstack.is(item)`), ignoring components/count — unlike
NeoForge's `ICustomIngredient.test`.

Other accessors — `Ingredient.java:125-199`:
```java
public IntList getStackingIds()                                      // :125
public boolean isEmpty()                                             // :143  explicit [] only
public boolean hasNoItems()                                          // :152  catches accidentally-empty tags
public Value[] getValues()                                           // :181  throws if isCustom()
public boolean isSimple()                                            // :188
@Nullable public net.neoforged.neoforge.common.crafting.ICustomIngredient getCustomIngredient()  // :193
public boolean isCustom()                                            // :197
```

Static factories — `Ingredient.java:201-224`:
```java
public static Ingredient fromValues(Stream<? extends Ingredient.Value> p_43939_)
public static Ingredient of()
public static Ingredient of(ItemLike... p_43930_)
public static Ingredient of(ItemStack... p_43928_)
public static Ingredient of(Stream<ItemStack> p_43922_)
public static Ingredient of(TagKey<Item> p_204133_)
```

### 4.2 How tags are represented — exact structure

`Ingredient.java:276-303`:
```java
public static record TagValue(TagKey<Item> tag) implements Ingredient.Value {
    static final com.mojang.serialization.MapCodec<Ingredient.TagValue> MAP_CODEC = RecordCodecBuilder.mapCodec(
        p_301118_ -> p_301118_.group(TagKey.codec(Registries.ITEM).fieldOf("tag").forGetter(p_301154_ -> p_301154_.tag))
                .apply(p_301118_, Ingredient.TagValue::new)
    );
    static final Codec<Ingredient.TagValue> CODEC = MAP_CODEC.codec();

    @Override
    public boolean equals(Object p_301162_) {
        return p_301162_ instanceof Ingredient.TagValue ingredient$tagvalue ? ingredient$tagvalue.tag.location().equals(this.tag.location()) : false;
    }

    @Override
    public Collection<ItemStack> getItems() {
        List<ItemStack> list = Lists.newArrayList();

        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(this.tag)) {
            list.add(new ItemStack(holder));
        }

        if (list.isEmpty()) {
            net.minecraft.world.item.ItemStack itemStack = new net.minecraft.world.item.ItemStack(net.minecraft.world.level.block.Blocks.BARRIER);
            itemStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Empty Tag: " + this.tag.location()));
            list.add(itemStack);
        }
        return list;
    }
}
```

`Ingredient.java:250-274`:
```java
public static record ItemValue(ItemStack item) implements Ingredient.Value { ... }
```

`Ingredient.java:305-320`:
```java
// Neo: Do not extend this interface. For custom ingredient behaviors see ICustomIngredient.
public interface Value {
    com.mojang.serialization.MapCodec<Ingredient.Value> MAP_CODEC = net.neoforged.neoforge.common.util.NeoForgeExtraCodecs.xor(Ingredient.ItemValue.MAP_CODEC, Ingredient.TagValue.MAP_CODEC)...
    Codec<Ingredient.Value> CODEC = MAP_CODEC.codec();
    Collection<ItemStack> getItems();
}
```

**Getting the `TagKey<Item>` out of an ingredient** — this is the reliable pattern:
```java
public static @Nullable TagKey<Item> tagOf(Ingredient ing) {
    if (ing.isCustom()) return null;              // would throw IllegalStateException
    Ingredient.Value[] values = ing.getValues();  // Ingredient.java:181
    if (values.length == 1 && values[0] instanceof Ingredient.TagValue tv) {
        return tv.tag();                          // TagValue is a record -> tag()
    }
    return null;                                   // plain item list, or a mix of item+tag
}
```
`Ingredient.equals`/`hashCode` use `Arrays.equals` over `values` (`:164-175`), and `TagValue.equals`
compares **`tag.location()` only** (`:284-286`) — so `TagValue` is safe in a `HashSet`.

**Multiple values.** An ingredient can legitimately be a *mix*: JSON allows
`[{ "item": "minecraft:stick" }, { "tag": "c:planks" }]`, producing `values.length == 2`. Treat the
matched set as `union(values[i].getItems())` — which is exactly what `getItems()` already computes.

### 4.3 NeoForge custom ingredients (very relevant on a 258-mod pack)

`net/neoforged/neoforge/common/crafting/ICustomIngredient.java`:
```java
public interface ICustomIngredient {
    boolean test(ItemStack stack);
    Stream<ItemStack> getItems();
    boolean isSimple();
    IngredientType<?> getType();
    @ApiStatus.NonExtendable
    default Ingredient toVanilla() { return new Ingredient(this); }
}
```
Its own javadoc is worth quoting for your indexer's expectations:

> *"These stacks are generally used for display purposes, and need not be exhaustive or perfectly
> accurate. … An exception is ingredients that are simple, for which it is important that the
> returned stacks correspond exactly to all the accepted Items. … At least one stack must be
> returned for the ingredient not to be considered accidentally empty. … The ingredient should try
> to return at least one stack with each accepted Item. This allows mods that inspect the ingredient
> to figure out which stacks it might accept."*

**Implication: for custom ingredients, `getItems()` is a *hint*, not a guarantee.** Mods like
Create/AE2/Mekanism register `DataComponentIngredient`, `CompoundIngredient`, `SizedIngredient`,
`DifferenceIngredient`, `IntersectionIngredient`, `BlockTagIngredient`. Your index must record the
custom ingredient's **type id** (`ICustomIngredient.getType()` → `IngredientType<?>`, registered in
`NeoForgeRegistries.Keys.INGREDIENT_TYPES`) alongside whatever stacks it reported, and the LLM JSON
should carry a `"custom": "<type-id>"` marker so the model knows the item list is indicative only.

`isSimple()` semantics matter for matching: `ShapelessRecipe` uses the fast
`StackedContents` path only when all ingredients are simple (`ShapelessRecipe.java:28`, `:59-69`).

---

## 5. Item registry, `ItemStack`, and display names

### 5.1 Enumerating ALL items

`net/minecraft/core/registries/BuiltInRegistries.java:144`
```java
public static final DefaultedRegistry<Item> ITEM = registerDefaultedWithIntrusiveHolders(Registries.ITEM, "air", p_260227_ -> Items.AIR);
```
Type: `DefaultedRegistry<Item>`, which extends `Registry<Item>`
(`Registries.ITEM` = `createRegistryKey("item")`, `Registries.java:159`).

Relevant `Registry<T>` members (`net/minecraft/core/Registry.java`):
```java
ResourceLocation getKey(T p_123006_);                       // :62  may return null
Optional<ResourceKey<T>> getResourceKey(T p_123008_);       // :64
@Nullable T get(@Nullable ResourceLocation p_123002_);      // :73
Optional<T> getOptional(@Nullable ResourceLocation p_123007_);   // :79
T getOrThrow(ResourceKey<T> p_123014_);                     // :89
Set<ResourceLocation> keySet();                             // :98
Set<Entry<ResourceKey<T>, T>> entrySet();                   // :100
default Stream<T> stream();                                 // :106
boolean containsKey(ResourceLocation p_123011_);            // :110
Stream<Holder.Reference<T>> holders();                      // :151
Optional<HolderSet.Named<T>> getTag(TagKey<T> p_206052_);   // :153
default Iterable<Holder<T>> getTagOrEmpty(TagKey<T> p_206059_);  // :155
Stream<Pair<TagKey<T>, HolderSet.Named<T>>> getTags();      // :165
Stream<TagKey<T>> getTagNames();                            // :167
Holder<T> wrapAsHolder(T p_263382_);                        // :121
```

**Iterate everything including modded:**
```java
for (Item item : BuiltInRegistries.ITEM) {                 // Registry implements Iterable<T>
    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
}
// or, with holders (needed for tags):
for (Holder.Reference<Item> h : BuiltInRegistries.ITEM.holders().toList()) {
    ResourceLocation id = h.key().location();
}
// or, cheapest id-first pass:
for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
    Item item = BuiltInRegistries.ITEM.get(id);
}
```
Modded items are in `BuiltInRegistries.ITEM` exactly like vanilla ones — that registry is the single
frozen static registry for all items (NeoForge `DeferredRegister` writes into it). **No special
handling needed.**

`Item.toString()` is already the registry name — `Item.java:234-237`:
```java
@Override
public String toString() {
    return BuiltInRegistries.ITEM.wrapAsHolder(this).getRegisteredName();
}
```

### 5.2 `ItemStack` construction

`net/minecraft/world/item/ItemStack.java:241-258`
```java
public ItemStack(ItemLike p_41599_) {
public ItemStack(Holder<Item> p_204116_) {
public ItemStack(Holder<Item> p_312081_, int p_41605_, DataComponentPatch p_330362_) {
public ItemStack(Holder<Item> p_220155_, int p_220156_) {
public ItemStack(ItemLike p_41601_, int p_41602_) {
```
`public static final ItemStack EMPTY = new ItemStack((Void)null);` — `ItemStack.java:180`.

```java
// Item.java:346-348
public ItemStack getDefaultInstance() {
    return new ItemStack(this);
}
```
Other essentials:
```java
public Item getItem()                                   // ItemStack.java:325
public int getCount()                                   // :1039
public ItemStack copy()                                 // :552
public ItemStack copyWithCount(int p_256354_)           // :562
public static boolean isSameItem(ItemStack a, ItemStack b)                 // :607
public static boolean isSameItemSameComponents(ItemStack a, ItemStack b)   // :611
public Component getHoverName()                         // :731
public String getDescriptionId()                        // :644
public Component getDisplayName()                       // :1006
```
`getDefaultInstance()` is the right way to get a "1× bare item" stack for display/lookup.

### 5.3 Human-readable names SERVER-SIDE — good news

`Item.java:239-253`
```java
protected String getOrCreateDescriptionId() {
    if (this.descriptionId == null) {
        this.descriptionId = Util.makeDescriptionId("item", BuiltInRegistries.ITEM.getKey(this));
    }
    return this.descriptionId;
}

public String getDescriptionId() {
    return this.getOrCreateDescriptionId();
}

public String getDescriptionId(ItemStack p_41455_) {
    return this.getDescriptionId();
}
```

`Item.java:299-301`
```java
public Component getName(ItemStack p_41458_) {
    return Component.translatable(this.getDescriptionId(p_41458_));
}
```

**`getDescriptionId()` always works server-side** and yields e.g. `item.create.cogwheel` — derived
purely from the registry id, no lang file involved. **Use this as the canonical identifier in your
JSON**, alongside the `ResourceLocation` id.

**Is `en_us.json` loaded server-side? YES — NeoForge makes it so.** `Language` loads vanilla
`en_us` statically in all environments:
`net/minecraft/locale/Language.java:34-42`
```java
public static final String DEFAULT = "en_us";
private static volatile Language instance = loadDefault();

private static Language loadDefault() {
    Builder<String, String> builder = ImmutableMap.builder();
    BiConsumer<String, String> biconsumer = builder::put;
    Map<String, net.minecraft.network.chat.Component> componentMap = new java.util.HashMap<>();
    parseTranslations(biconsumer, componentMap::put, "/assets/minecraft/lang/en_us.json");
    final Map<String, String> map = new java.util.HashMap<>(builder.build());
    net.neoforged.neoforge.server.LanguageHook.captureLanguageMap(map, componentMap);
```
Note `new java.util.HashMap<>(builder.build())` — **mutable**, which is what lets NeoForge inject into it.

`net/neoforged/neoforge/server/LanguageHook.java` — class javadoc:
> *"Loads the built-in language files, and handles loading the default language (`en_us`) on the dedicated server."*

`loadBuiltinLanguages()` merges:
```java
Language.loadFromJson(input, ...);   // assets/minecraft/lang/en_us.json
Language.loadFromJson(input, ...);   // assets/neoforge/lang/en_us.json
modTable.putAll(I18nManager.loadTranslations("en_us"));
defaultLanguageTable.putAll(modTable);
I18nManager.injectTranslations(modTable);
```
`loadModLanguages(MinecraftServer server)` scans **every namespace** for `lang/en_us.json`:
```java
private static void loadLanguage(String langName, MinecraftServer server) {
    String langFile = String.format(Locale.ROOT, "lang/%s.json", langName);
    ResourceManager resourceManager = server.getServerResources().resourceManager();
    // We cannot use the resource manager itself, because it is specifically scoped to data packs
    // (the PackType given to MultiPackResourceManager is SERVER_DATA)
    // Instead, we create a MultiPackResourceManager configured for PackType.CLIENT_RESOURCES
    ResourceManager clientResources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, resourceManager.listPacks().toList());
    for (String namespace : clientResources.getNamespaces()) {
        modTable.putAll(I18nManager.loadTranslations(langName));
        ResourceLocation langResource = ResourceLocation.fromNamespaceAndPath(namespace, langFile);
        for (Resource resource : clientResources.getResourceStack(langResource)) {
            Language.loadFromJson(stream, (key, value) -> modTable.put(key, value), ...);
        }
    }
}
```
Called from `ServerLifecycleHooks.handleServerStarting` — `net/neoforged/neoforge/server/ServerLifecycleHooks.java:100-105`:
```java
public static void handleServerStarting(final MinecraftServer server) {
    if (FMLEnvironment.dist.isDedicatedServer()) {
        LanguageHook.loadModLanguages(server);
        ...
```

**So on a dedicated server, by `ServerStartingEvent` (before `ServerStartedEvent`), all mod
`en_us` translations are loaded and resolvable.** Resolution path:
`Language.getInstance().getOrDefault(String key, String fallback)` (`Language.java:138`) /
`Language.getInstance().get(String)`; and `TranslatableContents.decompose()`
(`TranslatableContents.java:98-119`) calls `Language.getInstance().getComponent(this.key)` /
`getOrDefault(...)`.

**Practical recommendation for the knowledge layer:**
```java
// Always available, never null, never a translation key leaking to output:
String id       = BuiltInRegistries.ITEM.getKey(item).toString();   // "create:cogwheel"
String descId   = item.getDescriptionId();                         // "item.create.cogwheel"
// Resolved English name (safe AFTER ServerStartingEvent on dedicated; use the 2-arg form so an
// untranslated modded key degrades to the key rather than throwing):
String english  = net.minecraft.locale.Language.getInstance().getOrDefault(descId, descId);
// Stack-aware (respects CUSTOM_NAME / ITEM_NAME components):
Component hover = stack.getHoverName();
String hoverStr = hover.getString();     // Component.getString(int) at Component.java:48
```
⚠️ **[UNCERTAIN]** `Language.getInstance().getOrDefault(key, fallback)` reads the mutable map NeoForge
fills. `loadModLanguages` is dedicated-server-only in the code above; on an **integrated server**
(local world) the language data comes from the client's `I18n` path instead
(`net/minecraft/client/resources/language/I18n.java:17`: `net.neoforged.fml.i18n.I18nManager.injectTranslations(...)`),
which is *better*, not worse. For a dedicated-server mod you are fine. Guard the pre-`ServerStartingEvent`
window (e.g. during `TagsUpdatedEvent` on first boot) by falling back to the raw key.

**Always emit BOTH the registry id and the resolved English name.** The id is stable forever; the
name is what the LLM should speak. If they disagree (untranslated mod item), the id is ground truth.

---

## 6. Item tags server-side

`Registry.java:153-167` (as quoted in §5.1):
```java
Optional<HolderSet.Named<T>> getTag(TagKey<T> p_206052_);
default Iterable<Holder<T>> getTagOrEmpty(TagKey<T> p_206059_);
Stream<Pair<TagKey<T>, HolderSet.Named<T>>> getTags();
Stream<TagKey<T>> getTagNames();
HolderSet.Named<T> getOrCreateTag(TagKey<T> p_206045_);
void resetTags();
void bindTags(Map<TagKey<T>, List<Holder<T>>> p_205997_);
```

`net/minecraft/tags/TagKey.java:11`
```java
public record TagKey<T>(ResourceKey<? extends Registry<T>> registry, ResourceLocation location) {
    public static <T> TagKey<T> create(ResourceKey<? extends Registry<T>> p_203883_, ResourceLocation p_203884_)
    public static <T> Codec<TagKey<T>> codec(ResourceKey<? extends Registry<T>> p_203878_)
    public static <T> Codec<TagKey<T>> hashedCodec(ResourceKey<? extends Registry<T>> p_203887_)
    public boolean isFor(ResourceKey<? extends Registry<?>> p_207646_)
    public <E> Optional<TagKey<E>> cast(ResourceKey<? extends Registry<E>> p_207648_)
}
```
`hashedCodec` parses the `#namespace:path` form used inside recipe JSON — handy when round-tripping.

`net/minecraft/core/HolderSet.java:165-202`
```java
public static class Named<T> extends HolderSet.ListBacked<T> {
    @Override public Either<TagKey<T>, List<Holder<T>>> unwrap()
    @Override public boolean contains(Holder<T> p_205834_)
}
// ListBacked (HolderSet.java:84-145)
public Either<TagKey<T>, List<Holder<T>>> unwrap()
public boolean contains(Holder<T> p_205816_)
public Stream<Holder<T>> stream()
```

`net/minecraft/core/Holder.java:14-28`
```java
public interface Holder<T> extends net.neoforged.neoforge.common.extensions.IHolderExtension<T> {
    T value();
    boolean isBound();
    boolean is(ResourceLocation p_205713_);
    boolean is(ResourceKey<T> p_205712_);
    boolean is(Predicate<ResourceKey<T>> p_205711_);
    boolean is(TagKey<T> p_205705_);
    boolean is(Holder<T> p_316447_);
}
```
`Item.builtInRegistryHolder()` — `Item.java:102-104`:
```java
public Holder.Reference<Item> builtInRegistryHolder() {
    return this.builtInRegistryHolder;
}
```
(`private final Holder.Reference<Item> builtInRegistryHolder = BuiltInRegistries.ITEM.createIntrusiveHolder(this);`, `Item.java:67`)

**Enumerating all item tags and their members:**
```java
for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
    ResourceLocation tagId = tag.location();
    for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
        Item item = h.value();
        ResourceLocation itemId = h.unwrapKey().map(ResourceKey::location).orElse(null);
    }
}
// With the Named set directly (gives you the tag object back):
BuiltInRegistries.ITEM.getTags().forEach(pair -> {
    TagKey<Item> tag = pair.getFirst();
    HolderSet.Named<Item> set = pair.getSecond();
    set.stream().forEach(h -> { /* ... */ });
});
```

**When are tags bound?** `MappedRegistry.bindTags` — `MappedRegistry.java:395-426`:
```java
public void bindTags(Map<TagKey<T>, List<Holder<T>>> p_205875_) {
    Map<Holder.Reference<T>, List<TagKey<T>>> map = new IdentityHashMap<>();
    this.byKey.values().forEach(p_211801_ -> map.put((Holder.Reference<T>)p_211801_, new ArrayList<>()));
    p_205875_.forEach((p_339332_, p_339333_) -> { ... });
    ...
    synchronized (this.tagAdditionLock) {
        Map<TagKey<T>, HolderSet.Named<T>> map1 = new IdentityHashMap<>(this.tags);
        p_205875_.forEach((p_211797_, p_211798_) -> map1.computeIfAbsent((TagKey<T>)p_211797_, this::createTag).bind((List<Holder<T>>)p_211798_));
        map.forEach(Holder.Reference::bindTags);   // Holder.Reference.bindTags(Set<TagKey<T>>)
        this.tags = map1;
    }
}
```
Bound via `ReloadableServerResources.updateRegistryTags()` (`:139-144`) →
`registryOrThrow(resourcekey).bindTags(map)` (`:152`) → then `TagsUpdatedEvent`.
See §1.3 for the timing warning about reading tags *before* `updateRegistryTags()`.

**How mod recipe ingredients reference tags:** a mod's recipe JSON uses
`{"tag": "c:ingots/steel"}` (NeoForge common tags) or `{"tag": "create:whatever"}`, which decodes
to `Ingredient.TagValue(TagKey<Item>)` (`Ingredient.java:276-281`, `TagKey.codec(Registries.ITEM)`).
Because `TagValue.getItems()` goes through `BuiltInRegistries.ITEM.getTagOrEmpty` (`:292`),
**the tag's contents are resolved against the live static registry**, so every mod's tag entries are
automatically included. A tag that resolves empty yields a single fake `BARRIER` stack named
`"Empty Tag: <id>"` (`:296-300`) — **filter these out by checking `Ingredient.hasNoItems()`
(`:152-162`) before indexing**, or you will inject phantom `minecraft:barrier` inputs into your
`item -> recipes using it` index.

---

## 7. Recipe matching and "craft this" on the server

### 7.1 Checking whether a specific input matches

`RecipeManager.java:83-104`
```java
public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(RecipeType<T> p_44016_, I p_345492_, Level p_44018_)

public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(
    RecipeType<T> p_345895_, I p_345268_, Level p_346336_, @Nullable ResourceLocation p_346260_)

public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(
    RecipeType<T> p_220249_, I p_345826_, Level p_220251_, @Nullable RecipeHolder<T> p_346407_) {
    if (p_345826_.isEmpty()) {
        return Optional.empty();
    } else {
        return p_346407_ != null && p_346407_.value().matches(p_345826_, p_220251_)
            ? Optional.of(p_346407_)
            : this.byType(p_220249_).stream().filter(p_344413_ -> p_344413_.value().matches(p_345826_, p_220251_)).findFirst();
    }
}
```
⚠️ **Linear scan** over all recipes of the type. Calling this in a loop over thousands of candidate
grids is O(n²). For a "what can I craft?" query prefer the `StackedContents` route (§7.3).

`RecipeManager.java:122-135`
```java
public <I extends RecipeInput, T extends Recipe<I>> NonNullList<ItemStack> getRemainingItemsFor(RecipeType<T> p_44070_, I p_345118_, Level p_44072_)
```

Caching helper — `RecipeManager.java:180-202`:
```java
public static <I extends RecipeInput, T extends Recipe<I>> RecipeManager.CachedCheck<I, T> createCheck(final RecipeType<T> p_220268_)

public interface CachedCheck<I extends RecipeInput, T extends Recipe<I>> {
    Optional<RecipeHolder<T>> getRecipeFor(I p_344938_, Level p_220281_);
}
```

### 7.2 `StackedContents` — the fast "can craft / how many" engine

`net/minecraft/world/entity/player/StackedContents.java`:
```java
public class StackedContents {                                                     // :22
    public final Int2IntMap contents = new Int2IntOpenHashMap();                   // :24
    public void accountSimpleStack(ItemStack p_36467_)                             // :26
    public void accountStack(ItemStack p_36492_)                                   // :32
    public void accountStack(ItemStack p_36469_, int p_36470_)                     // :36
    public static int getStackingIndex(ItemStack p_36497_)                         // :44
    public boolean canCraft(Recipe<?> p_36476_, @Nullable IntList p_36477_)        // :66
    public boolean canCraft(Recipe<?> p_36479_, @Nullable IntList p_36480_, int p_36481_)  // :70
    public int getBiggestCraftableStack(RecipeHolder<?> p_301005_, @Nullable IntList p_36474_)  // :74
    public int getBiggestCraftableStack(RecipeHolder<?> p_300888_, int p_300980_, @Nullable IntList p_36495_)  // :78
    public static ItemStack fromStackingIndex(int p_36455_)                        // :82
    public void clear()                                                            // :86
}
```
`Inventory.fillStackedContents(StackedContents)` — `net/minecraft/world/entity/player/Inventory.java:556`
```java
public void fillStackedContents(StackedContents p_36011_)
```

This is the API for **"how many can this player make?"** without touching slots:
```java
StackedContents contents = new StackedContents();
player.getInventory().fillStackedContents(contents);
int max = contents.getBiggestCraftableStack(holder, null);   // 0 if not craftable
```
Implementation note: `getBiggestCraftableStack(holder, null)` delegates to
`getBiggestCraftableStack(holder, Integer.MAX_VALUE, null)` (`:74-76`), and
`canCraft(recipe, list, n)` constructs a `StackedContents.RecipePicker` (`:70-72`) — this is an
exact integer-programming-style pick, not a heuristic.

### 7.3 Reusing vanilla's recipe-book placement (the recommended path)

`net/minecraft/recipebook/ServerPlaceRecipe.java:19-46`
```java
public class ServerPlaceRecipe<I extends RecipeInput, R extends Recipe<I>> implements PlaceRecipe<Integer> {
    private static final int ITEM_NOT_FOUND = -1;
    protected final StackedContents stackedContents = new StackedContents();
    protected Inventory inventory;
    protected RecipeBookMenu<I, R> menu;

    public ServerPlaceRecipe(RecipeBookMenu<I, R> p_135431_) {
        this.menu = p_135431_;
    }

    public void recipeClicked(ServerPlayer p_135435_, @Nullable RecipeHolder<R> p_301150_, boolean p_135437_) {
        if (p_301150_ != null && p_135435_.getRecipeBook().contains(p_301150_)) {
            this.inventory = p_135435_.getInventory();
            if (this.testClearGrid() || p_135435_.isCreative()) {
                this.stackedContents.clear();
                p_135435_.getInventory().fillStackedContents(this.stackedContents);
                this.menu.fillCraftSlotsStackedContents(this.stackedContents);
                if (this.stackedContents.canCraft(p_301150_.value(), null)) {
                    this.handleRecipeClicked(p_301150_, p_135437_);
                } else {
                    this.clearGrid();
                    p_135435_.connection.send(new ClientboundPlaceGhostRecipePacket(p_135435_.containerMenu.containerId, p_301150_));
                }

                p_135435_.getInventory().setChanged();
            }
        }
    }

    protected void handleRecipeClicked(RecipeHolder<R> p_301187_, boolean p_135442_) {
        boolean flag = this.menu.recipeMatches(p_301187_);
        int i = this.stackedContents.getBiggestCraftableStack(p_301187_, null);
        ...
        int j1 = this.getStackSize(p_135442_, i, flag);
        IntList intlist = new IntArrayList();
        if (this.stackedContents.canCraft(p_301187_.value(), intlist, j1)) {
            int k = j1;
            for (int l : intlist) {
                ItemStack itemstack1 = StackedContents.fromStackingIndex(l);
                if (!itemstack1.isEmpty()) {
                    int i1 = itemstack1.getMaxStackSize();
                    if (i1 < k) { k = i1; }
                }
            }
            if (this.stackedContents.canCraft(p_301187_.value(), intlist, k)) {
                this.clearGrid();
                this.placeRecipe(this.menu.getGridWidth(), this.menu.getGridHeight(), this.menu.getResultSlotIndex(), p_301187_, intlist.iterator(), k);
            }
        }
    }

    public void addItemToSlot(Integer p_346390_, int p_346229_, int p_345733_, int p_345812_, int p_346351_)   // :96
    protected int getStackSize(boolean p_135450_, int p_135451_, boolean p_135452_)                          // :111
    protected int moveItemToGrid(Slot p_135439_, ItemStack p_135440_, int p_346157_)                          // :135
    private boolean testClearGrid()                                                                          // :160
    private int getAmountOfFreeSlotsInInventory()                                                            // :197
}
```
Other methods: `protected void clearGrid()` (`:48-58`).

`p_135437_` (`boolean`) is vanilla's `isShiftDown` = **"craft the maximum possible"**.
`getStackSize` (`:111-133`): `true` → `i = maxCraftable`; `false` + grid non-empty → `Integer.MAX_VALUE`
then clamped, i.e. "fill the grid once".

**⚠️ GATE — `p_135435_.getRecipeBook().contains(p_301150_)`** (`ServerPlaceRecipe.java:30`), backed by
`net/minecraft/stats/RecipeBook.java:34-40`:
```java
public boolean contains(@Nullable RecipeHolder<?> p_300981_) {
    return p_300981_ == null ? false : this.known.contains(p_300981_.id());
}
public boolean contains(ResourceLocation p_12712_) {
    return this.known.contains(p_12712_);
}
```
`known` is a `protected final Set<ResourceLocation>`. A player who has **not unlocked** the recipe
cannot use `recipeClicked` at all — it silently no-ops. For a knowledge-layer "craft this for me"
action on an arbitrary item, **you must unlock first**:
`net/minecraft/stats/ServerRecipeBook.java:26-46`
```java
public int addRecipes(Collection<RecipeHolder<?>> p_12792_, ServerPlayer p_12793_) {
    List<ResourceLocation> list = Lists.newArrayList();
    int i = 0;
    for (RecipeHolder<?> recipeholder : p_12792_) {
        ResourceLocation resourcelocation = recipeholder.id();
        if (!this.known.contains(resourcelocation) && !recipeholder.value().isSpecial()) {
            this.add(resourcelocation);
            this.addHighlight(resourcelocation);
            list.add(resourcelocation);
            CriteriaTriggers.RECIPE_UNLOCKED.trigger(p_12793_, recipeholder);
            i++;
        }
    }
    if (list.size() > 0) {
        this.sendRecipes(ClientboundRecipePacket.State.ADD, p_12793_, list);
    }
    return i;
}
```
and the non-sending variant, `RecipeBook.java:24-28`:
```java
public void add(RecipeHolder<?> p_300937_) {
    if (!p_300937_.value().isSpecial()) {
        this.add(p_300937_.id());
    }
}
```
Accessor: `ServerPlayer.getRecipeBook()` — `ServerPlayer.java:1681-1683`
```java
public ServerRecipeBook getRecipeBook() {
    return this.recipeBook;
}
```

**`RecipeBookMenu.handlePlacement` is the clean, exactly-vanilla entry point** —
`net/minecraft/world/inventory/RecipeBookMenu.java:10-52`:
```java
public abstract class RecipeBookMenu<I extends RecipeInput, R extends Recipe<I>> extends AbstractContainerMenu {
    public RecipeBookMenu(MenuType<?> p_40115_, int p_40116_)

    public void handlePlacement(boolean p_40119_, RecipeHolder<?> p_300860_, ServerPlayer p_40121_) {
        RecipeHolder<R> recipeholder = (RecipeHolder<R>)p_300860_;
        this.beginPlacingRecipe();

        try {
            new ServerPlaceRecipe<>(this).recipeClicked(p_40121_, recipeholder, p_40119_);
        } finally {
            this.finishPlacingRecipe((RecipeHolder<R>)p_300860_);
        }
    }

    protected void beginPlacingRecipe() { }
    protected void finishPlacingRecipe(RecipeHolder<R> p_345813_) { }

    public abstract void fillCraftSlotsStackedContents(StackedContents p_40117_);
    public abstract void clearCraftingContent();
    public abstract boolean recipeMatches(RecipeHolder<R> p_301144_);
    public abstract int getResultSlotIndex();
    public abstract int getGridWidth();
    public abstract int getGridHeight();
    public abstract int getSize();
    public abstract RecipeBookType getRecipeBookType();
    public abstract boolean shouldMoveToInventory(int p_150635_);
}
```

**This is exactly how the vanilla client triggers crafting** —
`net/minecraft/server/network/ServerGamePacketListenerImpl.java:1692-1710`:
```java
public void handlePlaceRecipe(ServerboundPlaceRecipePacket p_9882_) {
    PacketUtils.ensureRunningOnSameThread(p_9882_, this, this.player.serverLevel());
    this.player.resetLastActionTime();
    if (!this.player.isSpectator()
        && this.player.containerMenu.containerId == p_9882_.getContainerId()
        && this.player.containerMenu instanceof RecipeBookMenu) {
        if (!this.player.containerMenu.stillValid(this.player)) {
            LOGGER.debug("Player {} interacted with invalid menu {}", this.player, this.player.containerMenu);
        } else {
            this.server
                .getRecipeManager()
                .byKey(p_9882_.getRecipe())
                .ifPresent(
                    p_300787_ -> ((RecipeBookMenu)this.player.containerMenu)
                            .handlePlacement(p_9882_.isShiftDown(), (RecipeHolder<?>)p_300787_, this.player)
                );
        }
    }
}
```
A server-side mod can call these methods directly (they are public) — no packet needed.

`PlaceRecipe` default placement algorithm — `net/minecraft/recipebook/PlaceRecipe.java:8-57`:
```java
public interface PlaceRecipe<T> {
    default void placeRecipe(int p_135409_, int p_135410_, int p_135411_, RecipeHolder<?> p_301225_, Iterator<T> p_135413_, int p_135414_) {
        int i = p_135409_;
        int j = p_135410_;
        if (p_301225_.value() instanceof ShapedRecipe shapedrecipe) {
            i = shapedrecipe.getWidth();
            j = shapedrecipe.getHeight();
        }
        ...
    }
    void addItemToSlot(T p_346420_, int p_135416_, int p_135417_, int p_135418_, int p_135419_);
}
```
(`S` = `ItemStack`) — you could implement this yourself for a custom menu, but `ServerPlaceRecipe`
already does.

### 7.4 Actually TAKING the crafted result — `ResultSlot`, on a real crafting table

Placing the recipe only fills the grid; it does **not** consume ingredients. The grid is then live
(`CraftingMenu.finishPlacingRecipe` re-runs `slotChangedCraftingGrid`, `CraftingMenu.java:106-110`),
and the **result slot** holds the output. Consuming inputs happens in `ResultSlot.onTake`.

`net/minecraft/world/inventory/ResultSlot.java:60-93`
```java
@Override
public void onTake(Player p_150638_, ItemStack p_150639_) {
    this.checkTakeAchievements(p_150639_);
    CraftingInput.Positioned craftinginput$positioned = this.craftSlots.asPositionedCraftInput();
    CraftingInput craftinginput = craftinginput$positioned.input();
    int i = craftinginput$positioned.left();
    int j = craftinginput$positioned.top();
    net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(p_150638_);
    NonNullList<ItemStack> nonnulllist = p_150638_.level().getRecipeManager().getRemainingItemsFor(RecipeType.CRAFTING, craftinginput, p_150638_.level());
    net.neoforged.neoforge.common.CommonHooks.setCraftingPlayer(null);

    for (int k = 0; k < craftinginput.height(); k++) {
        for (int l = 0; l < craftinginput.width(); l++) {
            int i1 = l + i + (k + j) * this.craftSlots.getWidth();
            ItemStack itemstack = this.craftSlots.getItem(i1);
            ItemStack itemstack1 = nonnulllist.get(l + k * craftinginput.width());
            if (!itemstack.isEmpty()) {
                this.craftSlots.removeItem(i1, 1);
                itemstack = this.craftSlots.getItem(i1);
            }
            if (!itemstack1.isEmpty()) {
                if (itemstack.isEmpty()) {
                    this.craftSlots.setItem(i1, itemstack1);
                } else if (ItemStack.isSameItemSameComponents(itemstack, itemstack1)) {
                    itemstack1.grow(itemstack.getCount());
                    this.craftSlots.setItem(i1, itemstack1);
                } else if (!this.player.getInventory().add(itemstack1)) {
                    this.player.drop(itemstack1, false);
                }
            }
        }
    }
}
```
`checkTakeAchievements` (`:46-58`) fires `onCraftedBy` and NeoForge's
`EventHooks.firePlayerCraftingEvent(...)` (`:50`), and awards recipe stats via
`RecipeCraftingHolder.awardUsedRecipes` (`:53-55`).
`ResultSlot.isFake()` returns `true` (`:95-98`) — that is why the **QUICK_MOVE shift-click path is
blocked**: in `AbstractContainerMenu.doClick`, `ClickType.QUICK_MOVE` requires `!slot.isFake()`.

**Consequence:** `ResultSlot.onTake` is only invoked through the normal click path with a real
cursor. To **programmatically harvest** the crafted item you must not just call `onTake`; you must
also place the produced stack into the player's inventory yourself
(`player.getInventory().add(stack)` — used at `ResultSlot.java:87`) and clear the result slot.

**[UNCERTAIN]** — I did not trace `AbstractContainerMenu.doClick` exhaustively; the exact
`remove`/`onTake` pairing for `PICKUP` on a `ResultSlot` (lines ~440-475) was not read line-by-line.
The safe, self-contained approach is documented in §9.3 option B.

---

## 8. Modded recipe types

**Confirmed: modded recipes are ordinary `RecipeHolder<?>` in the same maps.**

Evidence — `RecipeManager.apply` puts every decoded recipe into both maps using only its own
`getType()` (`RecipeManager.java:62-65`):
```java
Recipe<?> recipe = r.carrier();
RecipeHolder<?> recipeholder = new RecipeHolder<>(resourcelocation, recipe);
builder.put(recipe.getType(), recipeholder);
builder1.put(resourcelocation, recipeholder);
```
There is **no type filtering anywhere** in `apply`, and `apply` also skips only names starting with
`_` (`:57`). Therefore:
- `getRecipes()` → **every** recipe from vanilla, datapacks, and all mods.
- `getOrderedRecipes()` → same set, grouped by type.
- A mod's `RecipeType` (e.g. `create:mixing`, `ae2:inscriber`, `mekanism:metallurgic_infusing`) appears
  as a key in `byType` exactly like `RecipeType.CRAFTING`.

NeoForge's decoding path is `Recipe.CONDITIONAL_CODEC` (`Recipe.java:18`), wired through
`SimpleJsonResourceReloadListener` over `Registries.elementsDirPath(Registries.RECIPE)`
(`RecipeManager.java:45`). `Registries.elementsDirPath` — `Registries.java:251-253`:
```java
public static String elementsDirPath(ResourceKey<? extends Registry<?>> p_350960_) {
    return net.neoforged.neoforge.common.CommonHooks.prefixNamespace(p_350960_.location());
}
```
i.e. recipes are loaded from `data/<namespace>/recipe/` (NeoForge's singular prefix).

NeoForge also injects **mod datapacks** into the pack repository —
`MinecraftServer.java:1534-1535`:
```java
DataPackConfig.DEFAULT.addModPacks(net.neoforged.neoforge.common.CommonHooks.getModDataPacks());
datapackconfig.addModPacks(net.neoforged.neoforge.common.CommonHooks.getModDataPacks());
```
so mod recipes are present even without a datapack.

### 8.1 Getting a `RecipeType`'s registry id for display

`BuiltInRegistries.java:158`
```java
public static final Registry<RecipeType<?>> RECIPE_TYPE = registerSimple(Registries.RECIPE_TYPE, p_259086_ -> RecipeType.CRAFTING);
```
```java
ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
// e.g. minecraft:crafting, create:mixing, ae2:inscriber
```
`getKey` is `@Nullable` (`Registry.java:61-62`) — it returns `null` for a `RecipeType` that was never
registered. That happens with `RecipeType.simple(ResourceLocation)` —
`RecipeType.java:25-33`:
```java
public static <T extends Recipe<?>> RecipeType<T> simple(final ResourceLocation name) {
    final String toString = name.toString();
    return new RecipeType<T>() {
        @Override
        public String toString() {
            return toString;
        }
    };
}
```
which creates an **unregistered** anonymous type (used by some mods for dynamic/one-off types).
**Defensive helper:**
```java
public static String typeId(RecipeType<?> type) {
    ResourceLocation rl = BuiltInRegistries.RECIPE_TYPE.getKey(type);
    return rl != null ? rl.toString() : type.toString();   // RecipeType.toString() is the name
}
```
Both `RecipeType.register(...)` (`:16-23`) and `RecipeType.simple(...)` override `toString()` to
return the plain path/name, so `type.toString()` is a usable fallback.

Built-in vanilla `RecipeType` constants — `RecipeType.java:8-14`:
```java
RecipeType<CraftingRecipe> CRAFTING = register("crafting");
RecipeType<SmeltingRecipe> SMELTING = register("smelting");
RecipeType<BlastingRecipe> BLASTING = register("blasting");
RecipeType<SmokingRecipe> SMOKING = register("smoking");
RecipeType<CampfireCookingRecipe> CAMPFIRE_COOKING = register("campfire_cooking");
RecipeType<StonecutterRecipe> STONECUTTING = register("stonecutting");
RecipeType<SmithingRecipe> SMITHING = register("smithing");
```
Note `SMITHING` is typed `RecipeType<SmithingRecipe>` and serves **both** transform and trim
(`SmithingRecipe.getType()` default, `SmithingRecipe.java:7-9`) — distinguish them via
`getSerializer() == RecipeSerializer.SMITHING_TRANSFORM` vs `SMITHING_TRIM`.

Serializer ids (for display) — `RecipeSerializer.java`:
`crafting_shaped`, `crafting_shapeless`, `crafting_special_*`, `smelting`, `blasting`, `smoking`,
`campfire_cooking`, `stonecutting`, `smithing_transform`, `smithing_trim`,
`crafting_decorated_pot`.
```java
ResourceLocation serId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
```

---

# RECOMMENDED IMPLEMENTATION

Design constraints derived from the research above:

1. **Never cache `RecipeManager` or `RecipeHolder` instances** across a reload (§1.4) — the whole
   `ReloadableServerResources` is swapped.
2. **Build the index after tags are bound**, not inside a reload listener's `apply` (§1.3, the
   `CREATE_NEW` window is a correctness trap that can permanently cache empty tags into
   `Ingredient.itemStacks`).
3. **Account for the three holes** where `getIngredients()`/`getResultItem()` give nothing:
   `CustomRecipe` specials (§3.7), smithing (§3.6), and custom `ICustomIngredient`s (§4.3).
   Degrade explicitly with flags rather than silently producing an empty input list.
4. **Filter `Ingredient.hasNoItems()`** to avoid phantom `minecraft:barrier` entries (§6).
5. **Always emit registry ids**; treat resolved English names as a convenience (§5.3).

### Class layout

```
com.example.knowledge
├── RecipeKnowledgeIndex       // the inverted index + lifecycle
├── RecipeJson                 // compact JSON-serializable recipe DTO
├── RecipeJsonBuilder          // Recipe<?> -> RecipeJson
├── ItemNamer                  // id + descriptionId + server-side English name
└── CraftService               // "craft this N times" via ServerPlaceRecipe
```

---

## 9.1 `RecipeKnowledgeIndex` — lifecycle + inverted index

```java
package com.example.knowledge;

import com.google.common.collect.Multimap;
import com.google.common.collect.ArrayListMultimap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full item -> recipe inverted index built from the vanilla server RecipeManager.
 * Rebuilt on every datapack/tag reload, because the RecipeManager INSTANCE is replaced.
 */
public final class RecipeKnowledgeIndex {

    /** Single, atomically-swapped snapshot. Readers never see a half-built index. */
    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();

    /** Identity of the RecipeManager the current snapshot was built from (reload detection). */
    private static volatile RecipeManager builtFrom;

    public record Snapshot(
            Map<ResourceLocation, RecipeJson> byId,
            Multimap<ResourceLocation, ResourceLocation> producing,  // output item id -> recipe ids
            Multimap<ResourceLocation, ResourceLocation> using,      // input  item id -> recipe ids
            Set<ResourceLocation> specialNoInputs,                   // recipes whose inputs are unknown
            Set<ResourceLocation> smithingNoInputs,
            long builtAtEpochMs
    ) {}

    // ---- lifecycle ---------------------------------------------------------

    public static void register() {
        NeoForge.EVENT_BUS.addListener(RecipeKnowledgeIndex::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(RecipeKnowledgeIndex::onServerStarted);
        NeoForge.EVENT_BUS.addListener(RecipeKnowledgeIndex::onServerStopped);
    }

    /**
     * Canonical rebuild point. Fired from ReloadableServerResources.updateRegistryTags() AFTER
     * MappedRegistry.bindTags ran -> every Ingredient.getItems() resolves tag contents correctly.
     * Fires on first boot (before MinecraftServer exists -> getCurrentServer() == null) and on /reload.
     */
    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;               // very first boot: no server yet; ServerStartedEvent covers it
        rebuild(server);
    }

    /** Covers the first-boot case that onTagsUpdated had to skip, and warms the index. */
    private static void onServerStarted(ServerStartedEvent event) {
        rebuild(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        CURRENT.set(null);
        builtFrom = null;
    }

    public static synchronized void rebuild(MinecraftServer server) {
        RecipeManager rm = server.getRecipeManager();
        RegistryAccess registries = server.registryAccess();

        Map<ResourceLocation, RecipeJson> byId = new HashMap<>();
        Multimap<ResourceLocation, ResourceLocation> producing = ArrayListMultimap.create();
        Multimap<ResourceLocation, ResourceLocation> using = ArrayListMultimap.create();
        Set<ResourceLocation> specialNoInputs = new HashSet<>();
        Set<ResourceLocation> smithingNoInputs = new HashSet<>();

        // getRecipes() == byName.values() -> every recipe exactly once, mods included. (RecipeManager:151)
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            ResourceLocation recipeId = holder.id();
            Recipe<?> recipe = holder.value();

            RecipeJson json = RecipeJsonBuilder.build(holder, registries);
            byId.put(recipeId, json);

            if (json.special() || json.inputs().isEmpty()) specialNoInputs.add(recipeId);
            if (json.smithing()) smithingNoInputs.add(recipeId);

            // output side: item id -> recipes producing it
            for (RecipeJson.Out out : json.outputs()) {
                if (out.item() != null && !out.item().isEmpty()) {
                    producing.put(ResourceLocation.parse(out.item()), recipeId);
                }
            }
            // input side: item id -> recipes using it (accepts that tag/custom ingredients
            // contribute MANY item ids, which is exactly the desired behaviour)
            for (RecipeJson.In in : json.inputs()) {
                for (String itemId : in.items()) {
                    using.put(ResourceLocation.parse(itemId), recipeId);
                }
            }
        }

        CURRENT.set(new Snapshot(Map.copyOf(byId), producing, using,
                Set.copyOf(specialNoInputs), Set.copyOf(smithingNoInputs), System.currentTimeMillis()));
        builtFrom = rm;
    }

    // ---- queries -----------------------------------------------------------

    public static @Nullable Snapshot snapshot() {
        return CURRENT.get();
    }

    /** Lazily self-heal if a /reload happened without our event being observed. */
    public static @Nullable Snapshot snapshotFor(MinecraftServer server) {
        Snapshot snap = CURRENT.get();
        if (snap == null || builtFrom != server.getRecipeManager()) rebuild(server);
        return CURRENT.get();
    }

    /** "how do I craft <item>" -- every recipe producing it. */
    public static List<RecipeJson> producing(ResourceLocation itemId) {
        Snapshot snap = CURRENT.get();
        if (snap == null) return List.of();
        return snap.producing().get(itemId).stream()
                .map(snap.byId()::get).filter(Objects::nonNull).toList();
    }

    /** "what is <item> used for" -- every recipe consuming it. */
    public static List<RecipeJson> using(ResourceLocation itemId) {
        Snapshot snap = CURRENT.get();
        if (snap == null) return List.of();
        return snap.using().get(itemId).stream()
                .map(snap.byId()::get).filter(Objects::nonNull).toList();
    }
}
```

Notes on the code above, tied to source:
- `rm.getRecipes()` is the correct iteration choice (`RecipeManager.java:151-153`, `byName.values()`);
  `getOrderedRecipes()` would double-count nothing but orders by type, which is useless here.
- `ItemStack[] getItems()` on a tag ingredient materialises the whole tag (`Ingredient.java:94-105`).
  For 258 mods this is the hot loop — see the optimization note in §9.5.
- `builtFrom != server.getRecipeManager()` is a **reference comparison** and is exactly the right
  staleness test, because reload swaps the instance (`MinecraftServer.java:1504-1505`).

---

## 9.2 `RecipeJson` — compact, JSON-serializable recipe for an LLM

Design goals: fixed schema, no cycles, small, ids not display names, explicit uncertainty.

```java
package com.example.knowledge;

import java.util.List;

/** Flat, JSON-serializable recipe. Emit with Gson/Jackson as-is. */
public record RecipeJson(
        String id,                 // "create:crushing/iron_ore"
        String type,               // "create:crushing"  <- BuiltInRegistries.RECIPE_TYPE.getKey(getType())
        String serializer,         // "create:crushing"  <- RECIPE_SERIALIZER.getKey(getSerializer())
        String group,              // recipe.getGroup(), "" when unset
        boolean special,           // recipe.isSpecial() -> inputs/outputs are NOT authoritative
        boolean smithing,          // true for SmithingTransform/Trim (inputs not exposed by the API)
        boolean incomplete,        // recipe.isIncomplete() -> some ingredient had no resolvable items
        String recipeBookCategory, // ShapedRecipe/ShapelessRecipe .category().getSerializedName(); else null
        List<In> inputs,
        List<Out> outputs,
        Shape shape,               // null unless shaped
        Cook cook,                 // null unless an AbstractCookingRecipe
        List<ItemJson> remainders  // getRemainingItems(...) is per-input; omitted -> null
) {
    /** One ingredient slot. items is a SET: tag ingredients expand to many items. */
    public record In(
            int slot,              // index into getIngredients(); -1 when not positional
            String kind,           // "item" | "tag" | "custom" | "mixed"
            String tag,            // "c:ingots/steel" when kind is tag/mixed (ingredient.getValues())
            String customType,     // IngredientType registry id when kind is custom
            List<String> items,    // ALL matching item ids (may be large for tags!)
            int itemCount,         // items.size() -- lets the LLM judge "any of N"
            boolean truncated      // true if items was capped (see MAX_ITEMS_PER_INGREDIENT)
    ) {}

    /** One output. count is the stack size. item is a registry id. */
    public record Out(String item, int count, String descriptionId) {}

    /** Only for ShapedRecipe. rows uses item/space tokens; key maps token -> In.slot. */
    public record Shape(int width, int height, List<String> rows, List<KeyRef> key) {}
    public record KeyRef(String token, int slot) {}

    /** Only for cooking recipes. */
    public record Cook(float experience, int cookingTimeTicks, String cookCategory) {}

    public record ItemJson(String item, int count) {}
}
```

### `RecipeJsonBuilder` — extraction rules per concrete type

```java
package com.example.knowledge;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class RecipeJsonBuilder {
    /** Cap for tag ingredients on a 258-mod pack. Flag truncation so the LLM knows. */
    public static final int MAX_ITEMS_PER_INGREDIENT = 64;

    public static RecipeJson build(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();

        String type = typeId(recipe.getType());
        String serializer = idOrToString(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));

        List<RecipeJson.In> inputs = new ArrayList<>();
        List<RecipeJson.Out> outputs = new ArrayList<>();
        RecipeJson.Shape shape = null;
        RecipeJson.Cook cook = null;
        String bookCategory = null;

        boolean smithing = recipe instanceof SmithingRecipe;

        // ---- OUTPUT ----
        // getResultItem may legitimately be EMPTY (CustomRecipe:12-14) or a placeholder
        // (SmithingTrimRecipe:59-68 -- a sample iron chestplate, NOT the real output).
        ItemStack result = recipe.getResultItem(registries);
        if (!result.isEmpty() && !(recipe instanceof SmithingTrimRecipe)) {
            outputs.add(toOut(result));
        }

        // ---- INPUTS ----
        NonNullList<Ingredient> ingredients = recipe.getIngredients();   // Recipe:43-45
        int slot = 0;
        for (Ingredient ing : ingredients) {
            // hasNoItems() (Ingredient:152-162) catches the fake BARRIER stack that TagValue.getItems()
            // injects for an empty tag (Ingredient:296-300). Drop it.
            if (ing.isEmpty() || ing.hasNoItems()) { slot++; continue; }
            inputs.add(toIn(slot, ing));
            slot++;
        }

        // ---- SHAPED extras: real pattern + key (ShapedRecipePattern.Data, :238) ----
        if (recipe instanceof ShapedRecipe shaped) {
            bookCategory = shaped.category().getSerializedName();
            ShapedRecipePattern p = shaped.pattern;                    // ShapedRecipe:15 (public)
            List<String> rows = p.data()
                    .map(d -> d.pattern())                              // Optional<Data>, populated from JSON
                    .orElse(null);
            if (rows != null) {
                // map char -> ingredient slot by walking the same order ShapedRecipePattern.unpack used (:95-108)
                Map<Character, Integer> slotOf = new LinkedHashMap<>();
                ShapedRecipePattern.Data d = p.data().orElseThrow();
                List<RecipeJson.KeyRef> key = new ArrayList<>();
                int idx = 0;
                for (Map.Entry<Character, Ingredient> e : new LinkedHashMap<>(d.key()).entrySet()) {
                    key.add(new RecipeJson.KeyRef(String.valueOf(e.getKey()), idx++));
                    slotOf.put(e.getKey(), idx - 1);
                }
                List<String> outRows = rows.stream()
                        .map(r -> r.chars().mapToObj(c -> " ".equals(String.valueOf((char) c)) ? " " : String.valueOf((char) c))
                                .reduce("", String::concat))
                        .toList();
                shape = new RecipeJson.Shape(p.width(), p.height(), outRows, key);
            } else {
                // network-constructed pattern (no Data): emit dimensions only
                shape = new RecipeJson.Shape(p.width(), p.height(), List.of(), List.of());
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            bookCategory = shapeless.category().getSerializedName();
        }

        // ---- COOKING extras ----
        if (recipe instanceof AbstractCookingRecipe c) {
            cook = new RecipeJson.Cook(c.getExperience(), c.getCookingTime(),
                    c.category().getSerializedName());
        }

        return new RecipeJson(
                holder.id().toString(), type, serializer, recipe.getGroup(),
                recipe.isSpecial(),          // Recipe:47-49 -- true for all CustomRecipe
                smithing,
                recipe.isIncomplete(),       // Recipe:67-70
                bookCategory, inputs, outputs, shape, cook, List.of());
    }

    /** Map an Ingredient (1.21.1: Value[] of ItemValue|TagValue, plus NeoForge custom) to JSON. */
    private static RecipeJson.In toIn(int slot, Ingredient ing) {
        String kind;
        String tagId = null;
        String customType = null;
        List<String> items = new ArrayList<>();
        boolean truncated = false;

        if (ing.isCustom()) {                                   // Ingredient:197
            kind = "custom";
            var custom = ing.getCustomIngredient();              // Ingredient:193  @Nullable
            if (custom != null) {
                // getItems() on a custom ingredient is documented as a HINT, not a guarantee
                // (ICustomIngredient javadoc). Emit the type id so consumers know what they got.
                // IngredientType is registered in the NeoForge registry:
                //   NeoForgeRegistries.INGREDIENT_TYPES  (NeoForgeRegistries.java:40)
                customType = idOrToString(
                        net.neoforged.neoforge.registries.NeoForgeRegistries.INGREDIENT_TYPES
                                .getKey(custom.getType()));
                try {
                    for (ItemStack s : custom.getItems().toList()) {
                        if (items.size() >= MAX_ITEMS_PER_INGREDIENT) { truncated = true; break; }
                        items.add(idOrToString(BuiltInRegistries.ITEM.getKey(s.getItem())));
                    }
                } catch (RuntimeException ex) {
                    truncated = true;   // a misbehaving custom ingredient must not break the whole index
                }
            }
        } else {
            Ingredient.Value[] values = ing.getValues();         // Ingredient:181
            boolean anyTag = false, anyItem = false;
            for (Ingredient.Value v : values) {
                if (v instanceof Ingredient.TagValue tv) anyTag = true;
                else if (v instanceof Ingredient.ItemValue iv) anyItem = true;
            }
            kind = anyTag && anyItem ? "mixed" : anyTag ? "tag" : "item";
            if (values.length == 1 && values[0] instanceof Ingredient.TagValue tv) {
                tagId = tv.tag().location().toString();          // TagValue is a record
            }
            for (ItemStack s : ing.getItems()) {                 // Ingredient:94-105
                if (items.size() >= MAX_ITEMS_PER_INGREDIENT) { truncated = true; break; }
                items.add(idOrToString(BuiltInRegistries.ITEM.getKey(s.getItem())));
            }
        }

        return new RecipeJson.In(slot, kind, tagId, customType,
                List.copyOf(new LinkedHashSet<>(items)), items.size(), truncated);
    }

    private static RecipeJson.Out toOut(ItemStack s) {
        return new RecipeJson.Out(
                idOrToString(BuiltInRegistries.ITEM.getKey(s.getItem())),
                s.getCount(),
                s.getDescriptionId());                            // Item.getDescriptionId():247
    }

    /** RecipeType registry id, with fallback for RecipeType.simple() unregistered types (RecipeType:25). */
    public static String typeId(RecipeType<?> type) {
        ResourceLocation rl = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return rl != null ? rl.toString() : type.toString();
    }

    private static String idOrToString(@Nullable ResourceLocation rl) {
        return rl == null ? "unknown" : rl.toString();
    }
}
```

**What the LLM sees** (example, shaped recipe):
```json
{
  "id": "minecraft:crafting_table",
  "type": "minecraft:crafting",
  "serializer": "minecraft:crafting_shaped",
  "group": "",
  "special": false,
  "smithing": false,
  "incomplete": false,
  "recipeBookCategory": "building",
  "shape": { "width": 2, "height": 2, "rows": ["##", "##"],
             "key": [{"token":"#","slot":0}] },
  "inputs": [ { "slot": 0, "kind": "tag", "tag": "minecraft:planks",
                "customType": null, "items": ["minecraft:oak_planks", "..."],
                "itemCount": 6, "truncated": false } ],
  "outputs": [ { "item": "minecraft:crafting_table", "count": 1,
                 "descriptionId": "block.minecraft.crafting_table" } ],
  "cook": null,
  "remainders": []
}
```

### Human-readable names for the LLM

```java
package com.example.knowledge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ItemNamer {
    /** Registry id -- stable, always correct, never translated. */
    public static String id(Item item) {
        var rl = BuiltInRegistries.ITEM.getKey(item);      // Registry:62, @Nullable
        return rl != null ? rl.toString() : "unknown";
    }

    /** "item.create.cogwheel" -- derived from the registry id, always available server-side. */
    public static String descriptionId(Item item) {
        return item.getDescriptionId();                    // Item:247-249
    }

    /**
     * English display name. NeoForge loads vanilla + NeoForge + ALL mod lang/en_us.json into
     * Language's mutable map (LanguageHook.loadBuiltinLanguages / loadModLanguages, invoked from
     * ServerLifecycleHooks.handleServerStarting), so this resolves on a dedicated server once the
     * server is starting. The 2-arg getOrDefault degrades to the key instead of throwing for
     * untranslated items.
     */
    public static String englishName(Item item) {
        String key = descriptionId(item);
        return Language.getInstance().getOrDefault(key, key);   // Language:138
    }

    /** Stack-aware: honours CUSTOM_NAME / ITEM_NAME components (ItemStack.getHoverName:731). */
    public static String englishName(ItemStack stack) {
        return stack.getHoverName().getString();                // Component.getString(int):48
    }

    /** Canonical triple to hand the LLM. */
    public static String describe(Item item) {
        return id(item) + " | " + englishName(item) + " | " + descriptionId(item);
    }
}
```

**Emit both `id` and `englishName`** in your JSON. On a 258-mod pack, some mod items ship no `en_us`
entry (or ship it only in a client-only resource pack); `englishName` then equals the raw
translation key, and `id` is the only trustworthy handle.

---

## 9.3 "Craft this item N times" — server-side, reusing vanilla machinery

### Choosing a strategy

| Situation | Use |
|---|---|
| Player has a crafting table UI open | **A.** `RecipeBookMenu.handlePlacement` (exact vanilla path) |
| Player has no container open; you want N crafts | **B.** headless `ServerPlaceRecipe` + manual harvest |
| You only need "can they?" / "how many?" | **C.** `StackedContents` only (no side effects) |

### C. Cheapest query — no side effects

```java
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.RecipeHolder;

/** How many times can this player craft `holder` right now? 0 == cannot. */
public static int maxCraftable(ServerPlayer player, RecipeHolder<?> holder) {
    StackedContents contents = new StackedContents();          // StackedContents:22
    player.getInventory().fillStackedContents(contents);       // Inventory:556
    // includes items already sitting in the open menu's craft grid, if any:
    if (player.containerMenu instanceof RecipeBookMenu<?, ?> rbm) {
        rbm.fillCraftSlotsStackedContents(contents);           // RecipeBookMenu:32
    }
    return contents.getBiggestCraftableStack(holder, null);    // StackedContents:74
}
```
This mirrors exactly what `ServerPlaceRecipe.recipeClicked` does at `:33-36`.

### A. With an open crafting menu — mirror `ServerGamePacketListenerImpl`

```java
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Equivalent to the client sending ServerboundPlaceRecipePacket.
 * `all` == vanilla's isShiftDown (craft max).
 * RETURNS false when the player has not unlocked the recipe -- see the gate below.
 */
public static boolean placeRecipe(ServerPlayer player, RecipeHolder<?> holder, boolean all) {
    if (!(player.containerMenu instanceof RecipeBookMenu<?, ?> menu)) return false;
    if (!menu.stillValid(player)) return false;                // mirrors SGPLI:1698
    menu.handlePlacement(all, holder, player);                 // RecipeBookMenu:15-24
    return true;
}
```

**The unlock gate.** `ServerPlaceRecipe.recipeClicked` first checks
`p_135435_.getRecipeBook().contains(p_301150_)` (`ServerPlaceRecipe.java:30`), which is a pure
`Set<ResourceLocation>` membership test (`RecipeBook.java:34-40`). A recipe the player never unlocked
makes the call a **silent no-op**. Unlock first:

```java
import net.minecraft.stats.ServerRecipeBook;

/** Make `holder` placeable for this player. No-op for special recipes (RecipeBook.add:24-28). */
public static void unlock(ServerPlayer player, RecipeHolder<?> holder) {
    // addRecipes also fires CriteriaTriggers.RECIPE_UNLOCKED and sends the client packet
    // (ServerRecipeBook:26-46). Prefer it over RecipeBook.add so the client stays in sync.
    player.getRecipeBook().addRecipes(java.util.List.of(holder), player);   // ServerPlayer:1681
}
```
This is a real gameplay side effect (the recipe appears in the player's recipe book and grants the
advancement criterion). **If that is not acceptable, use strategy B instead**, which bypasses the
recipe-book gate entirely by calling `ServerPlaceRecipe`'s protected internals — but note those are
`protected`, so B requires a subclass in package `net.minecraft.recipebook` or reflection.

### B. Headless, no recipe-book side effects, N crafts

`ServerPlaceRecipe.handleRecipeClicked` and `placeRecipe` are `protected`; `recipeClicked` is public
but gated. The clean no-side-effect route is to drive the **same primitives** directly:

```java
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Craft `holder` up to `times` times, consuming from the player's inventory and delivering the
 * results into the player's inventory. No container/menu required, no recipe-book gate.
 *
 * Reuses the exact primitives ServerPlaceRecipe uses:
 *   StackedContents.fill / canCraft(recipe, list, n) / fromStackingIndex   (ServerPlaceRecipe:34-36, :76-89)
 *
 * Returns the number of completed crafts.
 */
public static int craftDirectly(ServerPlayer player, RecipeHolder<?> holder, int times) {
    Recipe<?> recipe = holder.value();

    StackedContents contents = new StackedContents();
    player.getInventory().fillStackedContents(contents);

    IntList picked = new IntArrayList();
    // canCraft(recipe, out, n) does an exact pick and fills `picked` with stacking indices,
    // one entry per required item (StackedContents:70-72 -> RecipePicker.tryPick).
    if (!contents.canCraft(recipe, picked, times)) {
        // fall back to the largest achievable count, exactly like ServerPlaceRecipe:62
        int max = contents.getBiggestCraftableStack(holder, null);
        if (max <= 0) return 0;
        picked.clear();
        if (!contents.canCraft(recipe, picked, max)) return 0;
        times = max;
    }

    // 1) consume ingredients from the inventory, matching each picked stacking index.
    for (int idx : picked) {
        ItemStack need = StackedContents.fromStackingIndex(idx);   // StackedContents:82
        if (need.isEmpty()) continue;
        int remaining = need.getCount() * times;                   // `picked` is per single craft
        remaining = consumeFromInventory(player, need, remaining);
        if (remaining > 0) return 0;                               // should not happen after canCraft
    }

    // 2) deliver the output, mirroring ResultSlot:87 (inventory.add, else drop).
    //    EventHooks.firePlayerCraftingEvent(Player, ItemStack, Container) (EventHooks.java:926-928)
    //    needs a craft-matrix Container. For a headless craft, a throwaway SimpleContainer is enough:
    //      public SimpleContainer(int p_19150_)            (SimpleContainer.java:22)
    //    (If you implement remainder handling you need a CraftingContainer instead, because
    //     CraftingContainer.asCraftInput() is what CraftingInput needs -- CraftingContainer.java:13-16.)
    Container grid = new net.minecraft.world.SimpleContainer(9);
    for (int n = 0; n < times; n++) {
        ItemStack out = recipe.getResultItem(player.level().registryAccess()).copy();
        if (out.isEmpty()) continue;                               // special recipes -> no item output
        out.onCraftedBy(player.level(), player, out.getCount());   // ResultSlot:49
        net.neoforged.neoforge.event.EventHooks.firePlayerCraftingEvent(player, out, grid);
        if (!player.getInventory().add(out)) player.drop(out, false);   // ResultSlot:87-88
    }

    player.getInventory().setChanged();
    return times;
}

/** Consume `count` items matching `proto` (Item-only match, as Ingredient.test does: Ingredient:116). */
private static int consumeFromInventory(ServerPlayer player, ItemStack proto, int count) {
    var inv = player.getInventory();
    for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
        ItemStack slot = inv.getItem(i);
        if (slot.isEmpty() || !slot.is(proto.getItem())) continue;
        int take = Math.min(count, slot.getCount());
        slot.shrink(take);
        if (slot.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        count -= take;
    }
    return count;
}
```

⚠️ **Caveats on B, stated honestly:**
- **Crafting-remainder items are not handled.** `Recipe.getRemainingItems(T)` (`Recipe.java:30-41`)
  returns the bucket/bottle-style leftovers and is *type-parameterised* on the input
  (`CraftingInput`, `SingleRecipeInput`, `SmithingRecipeInput`). To honour them you must build the
  right `RecipeInput` for the recipe's type, which is exactly what `ResultSlot.onTake`
  (`ResultSlot.java:63-92`) does for `CraftingInput`. **[UNCERTAIN]** I did not implement this path;
  the accurate way is the `ResultSlot.onTake` algorithm quoted in §7.4 — reuse it verbatim with a
  temporary `CraftingInput`.
- **The `picked` semantics.** `RecipePicker.tryPick(int, IntList)` (`StackedContents.java:119`) fills
  the list with stacking indices for the requested number of crafts. The loop above multiplies each
  index's count by `times`; **[UNCERTAIN]** for count-sensitive custom ingredients this may
  over- or under-consume. A conservative alternative is to loop `times` times, calling
  `canCraft(recipe, picked, 1)` and consuming per iteration — same correctness guarantee as vanilla,
  at `times`× the cost.
- **Special recipes have no enumerable ingredients or output** (§3.7). `craftDirectly` cannot handle
  them; route those to strategy A with an unlock, or refuse with a clear error.
- **No `ResultSlot`, no `PlayerCraftingEvent` grid context, no stat awarding.** If you need
  `awardUsedRecipes` / stat increments, go through a real menu (strategy A).
- **NeoForge's `CommonHooks.setCraftingPlayer(...)`.** `ResultSlot.onTake` brackets its
  `getRemainingItemsFor` call with `CommonHooks.setCraftingPlayer(p_150638_)`
  (`ResultSlot.java:67-69`) so that mods can see who is crafting during remainder computation. Do the
  same around any `getRemainingItemsFor` call you make.

### Recommendation

For a knowledge-layer "craft this for me" action, **use strategy A with an explicit unlock** when the
player has a crafting menu open — it is byte-for-byte the vanilla path, handles remainders,
achievements and stats, and needs no reimplementation. Reserve strategy B for headless/testing use,
and route `isSpecial()` recipes through A only.

---

## 9.4 Where to hook, summarized

```
Server boot:
  ReloadableServerResources.loadResources()   -> RecipeManager populated   [RecipeManager.apply]
  WorldLoader: updateRegistryTags()           -> ITEM tags bound           [MappedRegistry.bindTags]
  TagsUpdatedEvent(SERVER_DATA_LOAD)          -> <-- REBUILD #1 (server may still be null on 1st boot)
  new MinecraftServer(...)
  ServerAboutToStartEvent
  ServerStartingEvent                         -> LanguageHook.loadModLanguages  (English names live)
  ServerStartedEvent                          -> <-- REBUILD #2 (covers 1st boot) + warm queries

/reload:
  MinecraftServer.reloadResources()
    new ReloadableServerResources  -> NEW RecipeManager instance (old one is garbage)
    this.resources = p_335203_     -> swap
    updateRegistryTags()           -> tags rebound
    TagsUpdatedEvent(SERVER_DATA_LOAD)-> <-- REBUILD #3   (RecipeKnowledgeIndex.builtFrom != rm -> rebuild)
    PlayerList.reloadResources()   -> recipes pushed to clients
```

## 9.5 Performance notes for a 258-mod pack

- `Ingredient.getItems()` (`Ingredient.java:94-105`) **materialises every item of every tag** and
  caches it in the `Ingredient` instance. On big common tags (`c:ingots`, `c:ores`,
  `c:raw_materials`, `forge:tools/*`) this is easily thousands of `ItemStack` allocations per recipe.
  - Cache by `Ingredient` identity (`Ingredient` implements `equals`/`hashCode` over `values`,
    `Ingredient.java:164-175`, and `TagValue.equals` compares `tag.location()` only, `:284-286`) —
    a `HashMap<Ingredient, List<String>>` dedupes the repeated identical ingredients that dominate
    crafting recipes.
  - Consider capping expansion per ingredient (`MAX_ITEMS_PER_INGREDIENT`) and flagging
    `truncated: true`, storing the `tag` id alongside so the LLM can still reason about the intent.
- Rebuild off-thread (the state is immutable once `apply` returns), then publish via the single
  `AtomicReference.set` — readers never block and never see a torn index.
- Do not rebuild on every `TagsUpdatedEvent` indiscriminately: filter on
  `UpdateCause.SERVER_DATA_LOAD` (`TagsUpdatedEvent.java:20-25`) — `TagsUpdatedEvent` also fires on
  the client with `CLIENT_PACKET_RECEIVED`.
- `RecipeManager.getRecipeFor(RecipeType, I, Level, ...)` is a **linear scan**
  (`RecipeManager.java:94-104`). Never call it in a loop over candidates; use the prebuilt index, or
  `RecipeManager.createCheck` (`:180-198`) if you are repeatedly testing the same type with a moving
  input.

## 9.6 Open items / explicit uncertainties

1. **[UNCERTAIN]** Are item tags bound during a custom `PreparableReloadListener`'s `apply`?
   Evidence (the `CREATE_NEW` → `FAIL` flip at `ReloadableServerResources.java:46`/`123-125` and the
   post-reload `updateRegistryTags()` at `WorldLoader.java:64`) suggests tags are *empty-but-created*
   during the reload window, and `Ingredient.getItems()` caches the empty result permanently.
   **Mitigation is in the design: rebuild from `TagsUpdatedEvent`, not from a listener.** Verify
   empirically by logging `Ingredient.hasNoItems()` counts from a listener vs. from the event.
2. **[UNCERTAIN]** Exact `AbstractContainerMenu.doClick` PICKUP-on-`ResultSlot` interleaving
   (`AbstractContainerMenu.java` ~368-475) — only the QUICK_MOVE-requires-`!isFake()` fact
   (`ResultSlot.java:95-98`) was confirmed. Strategy B avoids depending on this.
3. **[UNCERTAIN]** `RecipePicker.tryPick(int, IntList)` multi-craft count semantics
   (`StackedContents.java:119`, `:273`). The conservative per-craft loop is always safe.
4. **Smithing inputs are not reachable through any public API** (§3.6). Options: access transformer,
   or emit `smithing: true` with empty inputs.
5. **Special recipes have no enumerable inputs or output** (§3.7) — emit
   `special: true` and let the LLM say "special crafting recipe; exact inputs depend on the items
   used" rather than inventing a recipe.
6. **Brewing is not a `RecipeType`** (NeoForge `BrewingRecipeRegistry`) and will not appear in
   `RecipeManager` — index it separately if potions matter.
