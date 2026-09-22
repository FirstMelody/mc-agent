package com.melody.mcagent.rt.knowledge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side equivalent of what JEI shows a client: what every item is, how it is made, and what
 * it is used for — including every modded item and recipe.
 *
 * <p>JEI itself cannot provide this. It loads on both sides, but its recipe data is built entirely
 * on the client: {@code mezz.jei.common.config.IServerConfig} is an empty marker interface and the
 * server registers no recipe categories at all. What the server <em>does</em> have is the vanilla
 * {@link net.minecraft.world.item.crafting.RecipeManager}, which contains every recipe from every
 * loaded mod, because mods register recipes through datapacks and {@code RecipeType}s. That is a
 * better source anyway: it is the same data the game actually uses to craft.
 *
 * <p>The index is a pair of inverted maps, so "how do I make X" and "what is X for" are both a
 * single lookup rather than a scan. Both directions are needed: a bot asked to obtain an item wants
 * recipes that produce it, and a bot holding something wants to know what it could build.
 *
 * <p>Rebuildable by design. A datapack reload replaces the entire {@code ReloadableServerResources},
 * so holding a {@code RecipeManager} or {@code RecipeHolder} across a reload would leave the index
 * pointing at dead objects.
 */
public final class RecipeKnowledgeIndex {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/knowledge");

    /** item id -> recipes that produce it. */
    private final Map<String, List<Entry>> producers = new HashMap<>();
    /** item id -> recipes that consume it. */
    private final Map<String, List<Entry>> consumers = new HashMap<>();
    /** Every item id in the game, including modded ones. */
    private final Set<String> allItems = new HashSet<>();
    /** Lowercased display name -> item ids, for loose lookups. */
    private final Map<String, List<String>> namesToIds = new HashMap<>();

    private int recipeCount;
    private int skipped;

    /** One recipe, pre-rendered into the compact form we show a model. */
    public record Entry(String recipeId, String type, List<String> inputs, List<String> outputs, int outputCount) {
    }

    private RecipeKnowledgeIndex() {
    }

    /**
     * Build the index from an already-snapshotted recipe list.
     *
     * <p>The list has to have been read on the server thread, because reading the recipe manager is
     * world state. Everything below is computation over immutable holders, which is what lets the
     * caller run it off-thread - see {@link KnowledgeManager#rebuild}.
     *
     * <p>Must not run before tags are bound — at server start, not inside a datapack reload's
     * {@code apply} — because reading {@code Ingredient.getItems()} too early can permanently
     * cache empty tag contents.
     *
     * <p>A single malformed recipe from any of the hundreds of installed mods must not break the
     * index, so each recipe is converted defensively and failures are counted, not thrown.
     *
     * <p>The interrupt flag is checked between recipes: this runs on a worker thread that a reload
     * may have to stop before it closes the runtime class loader out from under it.
     */
    public static RecipeKnowledgeIndex build(net.minecraft.core.HolderLookup.Provider registries,
                                             List<RecipeHolder<?>> recipes) {
        RecipeKnowledgeIndex index = new RecipeKnowledgeIndex();
        long start = System.nanoTime();

        index.indexItems();

        for (RecipeHolder<?> holder : recipes) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.warn("Knowledge index build interrupted after {} recipe(s); the next load rebuilds it",
                        index.recipeCount);
                return index;
            }
            try {
                index.indexRecipe(holder, registries);
            } catch (Throwable t) {
                index.skipped++;
                LOG.debug("Skipping recipe {}: {}", holder.id(), t.toString());
            }
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        LOG.info("Knowledge index built in {} ms: {} items, {} recipes ({} skipped)",
                ms, index.allItems.size(), index.recipeCount, index.skipped);
        return index;
    }

    private void indexItems() {
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            String id = key.toString();
            this.allItems.add(id);
            this.allItems.add(key.getPath());

            String name = item.getDescription().getString();
            if (name != null && !name.isBlank()) {
                this.namesToIds.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>()).add(id);
            }
        }
    }

    private void indexRecipe(RecipeHolder<?> holder, net.minecraft.core.HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        String recipeId = holder.id().toString();
        String type = recipeTypeId(recipe);

        List<String> inputs = describeIngredients(recipe);
        List<String> outputs = new java.util.ArrayList<>();

        ItemStack result;
        try {
            result = recipe.getResultItem(registries);
        } catch (Throwable t) {
            result = ItemStack.EMPTY;
        }
        if (result != null && !result.isEmpty()) {
            outputs.add(itemId(result.getItem()));
        }

        // Special/custom recipes legitimately have no inspectable inputs (dyeing, map cloning,
        // banner copying...). Record them so a caller can tell "unknown" from "nothing needed".
        if (inputs.isEmpty() && !recipe.isSpecial()) {
            inputs.add("(inputs not expressible)");
        } else if (recipe.isSpecial()) {
            inputs.add("(special recipe - inputs depend on the items used)");
        }

        Entry entry = new Entry(recipeId, type, inputs, outputs, result == null ? 0 : result.getCount());
        this.recipeCount++;

        for (String out : outputs) {
            this.producers.computeIfAbsent(out, k -> new java.util.ArrayList<>()).add(entry);
            this.producers.computeIfAbsent(bare(out), k -> new java.util.ArrayList<>()).add(entry);
        }
        for (String in : inputs) {
            // Inputs may be tag names, which we keep distinct from item ids.
            if (in.startsWith("#")) {
                continue;
            }
            this.consumers.computeIfAbsent(in, k -> new java.util.ArrayList<>()).add(entry);
            this.consumers.computeIfAbsent(bare(in), k -> new java.util.ArrayList<>()).add(entry);
        }
    }

    /**
     * Render a recipe's inputs as item ids.
     *
     * <p>An {@code Ingredient} in 1.21.1 is a list of either concrete items or item tags. We render
     * tags as {@code #namespace:tag} and concrete items as their id; a shapeless recipe with three
     * slots therefore reads as three entries, which is what a model needs to know.
     */
    private List<String> describeIngredients(Recipe<?> recipe) {
        List<String> out = new java.util.ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty() || ingredient.hasNoItems()) {
                // Empty slots are meaningful in shaped recipes: they are gaps in the pattern.
                continue;
            }
            out.add(describeIngredient(ingredient));
        }
        return out;
    }

    /** Describe one ingredient, preferring a tag name when the whole tag is used. */
    private String describeIngredient(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) {
            return "(unknown)";
        }
        if (items.length == 1) {
            return itemId(items[0].getItem());
        }
        // More than one option: show the first few so the text stays readable.
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(items.length, 3);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(itemId(items[i].getItem()));
        }
        if (items.length > shown) {
            sb.append(" | ... (").append(items.length).append(" options)");
        }
        return sb.toString();
    }

    private static String recipeTypeId(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return key == null ? "unknown" : key.toString();
    }

    private static String itemId(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    /** Strip a {@code namespace:} prefix, so bare names match too. */
    private static String bare(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // --- queries --------------------------------------------------------------------------------

    public int indexedItemCount() {
        return this.allItems.size();
    }

    public int recipeCount() {
        return this.recipeCount;
    }

    /** Look up an item id from a loose query (full id, bare path, or display name). */
    @Nullable
    public String resolveItem(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();

        if (this.allItems.contains(q)) {
            // Prefer the namespaced form when we were given a bare path.
            if (q.contains(":")) {
                return q;
            }
            ResourceLocation key = ResourceLocation.tryParse("minecraft:" + q);
            if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
                return key.toString();
            }
            return q;
        }

        List<String> byName = this.namesToIds.get(q.toLowerCase(Locale.ROOT));
        if (byName != null && !byName.isEmpty()) {
            return byName.get(0);
        }

        // Fall back to a substring match over ids, so "iron_ingot" finds "minecraft:iron_ingot"
        // and "ingot" finds something plausible.
        String lower = q.toLowerCase(Locale.ROOT);
        String best = null;
        for (String id : this.allItems) {
            if (!id.contains(":")) {
                continue;
            }
            if (id.toLowerCase(Locale.ROOT).contains(lower)) {
                if (best == null || id.length() < best.length()) {
                    best = id;
                }
            }
        }
        return best;
    }

    /** Recipes that produce an item. */
    public List<Entry> recipesProducing(String itemId) {
        List<Entry> direct = this.producers.get(itemId);
        if (direct != null && !direct.isEmpty()) {
            return List.copyOf(direct);
        }
        List<Entry> bare = this.producers.get(bare(itemId));
        return bare == null ? List.of() : List.copyOf(bare);
    }

    /** Recipes that consume an item. */
    public List<Entry> recipesUsing(String itemId) {
        List<Entry> direct = this.consumers.get(itemId);
        if (direct != null && !direct.isEmpty()) {
            return List.copyOf(direct);
        }
        List<Entry> bare = this.consumers.get(bare(itemId));
        return bare == null ? List.of() : List.copyOf(bare);
    }

    /** Every item id in the game. */
    public Set<String> allItemIds() {
        return Set.copyOf(this.allItems);
    }

    /** Search item ids and names by substring, for a "what is this called" query. */
    public List<String> searchItems(String query, int limit) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();

        for (Map.Entry<String, List<String>> entry : this.namesToIds.entrySet()) {
            if (entry.getKey().contains(lower)) {
                for (String id : entry.getValue()) {
                    if (!out.contains(id)) {
                        out.add(id);
                    }
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
        }

        for (String id : this.allItems) {
            if (id.contains(":") && id.toLowerCase(Locale.ROOT).contains(lower) && !out.contains(id)) {
                out.add(id);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    // --- LLM-facing descriptions ----------------------------------------------------------------

    /**
     * Describe an item and how to make it, as plain text for a model prompt.
     *
     * <p>Text rather than JSON: this goes straight into a conversation, and a recipe list a model
     * can read at a glance beats a nested structure it has to parse.
     */
    public String describeItemAndRecipes(String query, int limit) {
        String resolved = resolveItem(query);
        if (resolved == null) {
            List<String> similar = searchItems(query, 8);
            if (similar.isEmpty()) {
                return "No item matches '" + query + "'.";
            }
            return "No exact item '" + query + "'. Did you mean: " + String.join(", ", similar) + "?";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(resolved);
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(resolved));
        String display = item.getDescription().getString();
        if (display != null && !display.isBlank() && !display.equals(resolved)) {
            sb.append(" (\"").append(display).append("\")");
        }
        sb.append('\n');

        List<Entry> producers = recipesProducing(resolved);
        if (producers.isEmpty()) {
            sb.append("Not craftable - it has no crafting recipe (world-generated, mob drop, or obtained another way).\n");
        } else {
            sb.append("Made by ").append(producers.size()).append(" recipe(s); showing ")
              .append(Math.min(limit, producers.size())).append(":\n");
            for (int i = 0; i < Math.min(limit, producers.size()); i++) {
                sb.append("  - ").append(format(producers.get(i))).append('\n');
            }
        }

        List<Entry> users = recipesUsing(resolved);
        sb.append("Used in ").append(users.size()).append(" recipe(s).\n");
        return sb.toString();
    }

    /** Describe what an item is used for, as plain text for a model prompt. */
    public String describeUses(String query, int limit) {
        String resolved = resolveItem(query);
        if (resolved == null) {
            List<String> similar = searchItems(query, 8);
            if (similar.isEmpty()) {
                return "No item matches '" + query + "'.";
            }
            return "No exact item '" + query + "'. Did you mean: " + String.join(", ", similar) + "?";
        }

        List<Entry> users = recipesUsing(resolved);
        if (users.isEmpty()) {
            sbHeader();
            return resolved + " is not used as an ingredient in any recipe "
                    + "(it may still be useful as a tool, fuel, or placeable block).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(resolved).append(" is used in ").append(users.size()).append(" recipe(s); showing ")
          .append(Math.min(limit, users.size())).append(":\n");
        for (int i = 0; i < Math.min(limit, users.size()); i++) {
            sb.append("  - ").append(format(users.get(i))).append('\n');
        }
        return sb.toString();
    }

    private static void sbHeader() {
        // Placeholder to keep symmetric structure; intentionally empty.
    }

    /** One line, model-readable: outputs <- inputs (type). */
    private static String format(Entry entry) {
        String outs = entry.outputs().isEmpty()
                ? "(no item output)"
                : entry.outputCount() + "x " + entry.outputs().get(0);
        String ins = entry.inputs().isEmpty() ? "(no inputs)" : String.join(" + ", entry.inputs());
        return outs + "  <-  " + ins + "  [" + entry.type() + "]";
    }

    /** A compact overview for a system prompt. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", this.allItems.size());
        out.put("recipes", this.recipeCount);
        out.put("indexedProducers", this.producers.size());
        out.put("indexedConsumers", this.consumers.size());
        return out;
    }
}
