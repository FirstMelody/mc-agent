package com.melody.mcagent.rt.action;

import java.util.ArrayList;
import java.util.List;

import com.melody.mcagent.rt.knowledge.RecipeKnowledgeIndex;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

/**
 * Crafting, using the game's own recipe matcher rather than a reimplementation of it.
 *
 * <p>A headless bot cannot use vanilla's recipe-book path: {@code ServerPlaceRecipe} needs an open
 * {@code RecipeBookMenu} and a real client to drive the slot clicks. What a bot needs is simpler:
 * find a recipe its inventory satisfies, consume the ingredients, hand back the result.
 *
 * <p>The authority is always the game. For a candidate recipe we lay one matching item into each
 * of the recipe's own ingredient positions — <b>positionally</b>, because a shaped recipe's pattern
 * is what makes it match — and then ask
 * {@code RecipeManager.getRecipeFor} to confirm. Only a recipe the server agrees is craftable gets
 * its ingredients consumed. That means modded recipes and their custom ingredient predicates work
 * without us understanding them.
 *
 * <p>Scope is the crafting grid (shaped and shapeless). Furnace, smoker, campfire and stonecutter
 * recipes are not grid recipes; they are reported as needing their own block rather than quietly
 * producing nothing.
 */
public final class Crafting {

    private Crafting() {
    }

    /**
     * The recipe's ingredients laid out positionally, paired with the inventory slot each one was
     * taken from. Empty positions are preserved so shaped patterns stay intact.
     */
    private record Plan(RecipeHolder<CraftingRecipe> recipe, List<Ingredient> layout, int[] sourceSlots) {
    }

    /**
     * Work out how (and whether) the bot can craft the requested item right now.
     *
     * @param maxSide the largest grid the bot can currently reach: 2 with bare hands, 3 at a table
     * @return the plan, or null when the inventory cannot satisfy any known recipe for it
     */
    @Nullable
    private static Plan plan(ServerPlayer bot, String itemId, int maxSide) {
        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        if (index == null) {
            return null;
        }

        var inventory = bot.getInventory();

        for (RecipeKnowledgeIndex.Entry entry : index.recipesProducing(itemId)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.recipeId());
            if (id == null) {
                continue;
            }
            var holder = bot.server.getRecipeManager().byKey(id).orElse(null);
            if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) holder;
            Plan candidate = tryLayout(bot, typed, recipe, inventory, maxSide);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Try to satisfy one recipe from the inventory.
     *
     * <p>Ingredients are matched greedily against a <b>per-slot budget</b> rather than a
     * one-item-per-slot claim. A recipe needing nine iron ingots must be able to take all nine from
     * a single stack of nine, exactly as a player would experience it; treating each slot as
     * usable only once would wrongly reject every recipe whose ingredients stack together. That
     * was a real bug: a single plank could make a button, but nine ingots could never make a block.
     *
     * <p><b>The grid must be exactly the recipe's own shape.</b> {@code ShapedRecipePattern.matches}
     * requires the input's width and height to equal the pattern's, so padding a non-square recipe
     * out to a square silently makes it unmatchable. This was a serious bug: a 1x2 stick recipe was
     * laid out as 2x2 (planks on the top row), which is a different pattern, so the game correctly
     * refused — and so did every other non-square recipe, which is every door, ladder, fence, tool
     * and piece of armour in the game.
     */
    @Nullable
    private static Plan tryLayout(ServerPlayer bot, RecipeHolder<CraftingRecipe> holder,
                                  CraftingRecipe recipe, net.minecraft.world.entity.player.Inventory inventory,
                                  int maxSide) {
        List<Ingredient> raw = recipe.getIngredients();
        if (raw.isEmpty()) {
            return null;
        }

        int width;
        int height;
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else {
            // Shapeless: the arrangement is irrelevant, but the number of occupied slots is not -
            // the matcher compares it against the recipe's ingredient count.
            int count = 0;
            for (Ingredient ingredient : raw) {
                if (ingredient != null && !ingredient.isEmpty() && !ingredient.hasNoItems()) {
                    count++;
                }
            }
            if (count == 0) {
                return null;
            }
            width = (int) Math.ceil(Math.sqrt(count));
            height = (int) Math.ceil((double) count / width);
        }

        if (Math.max(width, height) > maxSide) {
            // A real player could not make this either without somewhere to lay it out.
            return null;
        }

        // Remaining usable count per inventory slot.
        int[] budget = new int[inventory.getContainerSize()];
        for (int slot = 0; slot < budget.length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            budget[slot] = stack.isEmpty() ? 0 : stack.getCount();
        }

        List<ItemStack> grid = new ArrayList<>(width * height);
        int[] chosenSlots = new int[width * height];
        java.util.Arrays.fill(chosenSlots, -1);

        for (int position = 0; position < width * height; position++) {
            // Shaped recipes list width*height entries in row-major order, which is exactly how the
            // grid indexes them, so position maps straight across and the pattern is preserved.
            Ingredient ingredient = position < raw.size() ? raw.get(position) : Ingredient.EMPTY;
            if (ingredient == null || ingredient.isEmpty() || ingredient.hasNoItems()) {
                // A deliberate gap in a shaped pattern must be preserved.
                grid.add(ItemStack.EMPTY);
                continue;
            }

            int found = -1;
            for (int slot = 0; slot < budget.length; slot++) {
                if (budget[slot] <= 0) {
                    continue;
                }
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    found = slot;
                    break;
                }
            }
            if (found < 0) {
                return null; // this ingredient is not available in sufficient quantity
            }

            budget[found]--;
            chosenSlots[position] = found;
            grid.add(inventory.getItem(found).copyWithCount(1));
        }

        CraftingInput input = CraftingInput.of(width, height, grid);

        // Let the game decide. Confirming with the real matcher means modded recipes and their
        // custom ingredient predicates work without us reimplementing them.
        boolean matches = bot.server.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, bot.level())
                .map(found -> found.id().equals(holder.id()))
                .orElse(false);

        return matches ? new Plan(holder, raw, chosenSlots) : null;
    }

    /**
     * Craft an item: consume the ingredients and give the bot the results.
     *
     * @param times how many times to apply the recipe
     */
    public static Actions.Result craft(ServerPlayer bot, String itemQuery, int times) {
        if (times < 1) {
            return Actions.Result.fail("count must be at least 1");
        }
        if (times > 64) {
            return Actions.Result.fail("cannot craft more than 64 at once");
        }

        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        String itemId = index != null ? index.resolveItem(itemQuery) : itemQuery;
        if (itemId == null) {
            return Actions.Result.fail("unknown item '" + itemQuery + "'");
        }

        var inventory = bot.getInventory();
        int maxSide = availableGridSide(bot);
        int crafted = 0;
        ItemStack lastResult = ItemStack.EMPTY;

        for (int n = 0; n < times; n++) {
            // Re-plan each iteration: the inventory changed, so a fresh match is required.
            Plan plan = plan(bot, itemId, maxSide);
            if (plan == null) {
                break;
            }

            for (int slot : plan.sourceSlots()) {
                if (slot < 0) {
                    continue;
                }
                ItemStack stack = inventory.getItem(slot);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }

            ItemStack result = plan.recipe().value().getResultItem(bot.server.registryAccess());
            if (result.isEmpty()) {
                break;
            }
            lastResult = result;

            ItemStack leftover = Containers.addToInventory(bot, result.copy());
            if (!leftover.isEmpty()) {
                // Inventory full: drop the result rather than destroying it.
                bot.drop(leftover, false);
            }
            crafted++;
        }

        if (crafted == 0) {
            return explainFailure(bot, itemId);
        }

        return Actions.Result.ok(String.format("crafted %dx %s",
                crafted * lastResult.getCount(),
                BuiltInRegistries.ITEM.getKey(lastResult.getItem())));
    }

    /**
     * The largest crafting grid the bot may use.
     *
     * <p>Always grant the 3x3 grid. This is an intentional latency compromise: making a slow LLM
     * first find, path to and face a crafting table costs another observation/decision round trip
     * without adding an interesting choice. The game still validates the real recipe and consumes
     * every ingredient; only the table-presence ceremony is skipped.
     */
    private static int availableGridSide(ServerPlayer bot) {
        return 3;
    }

    /** Say precisely what is missing, which is what a model needs in order to recover. */
    private static Actions.Result explainFailure(ServerPlayer bot, String itemId) {
        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        if (index == null) {
            return Actions.Result.fail("cannot craft " + itemId + ": the recipe index is not built");
        }

        List<RecipeKnowledgeIndex.Entry> producers = index.recipesProducing(itemId);
        if (producers.isEmpty()) {
            return Actions.Result.fail(itemId + " has no crafting recipe - it must be obtained "
                    + "another way (mining, mobs, trading, or a machine).");
        }

        RecipeKnowledgeIndex.Entry entry = producers.get(0);
        if (!entry.type().contains("crafting")) {
            return Actions.Result.fail("cannot craft " + itemId + " in a crafting grid: its recipe "
                    + "type is " + entry.type() + ", which needs its own block or machine.");
        }

        var inventory = bot.getInventory();
        List<String> missing = new ArrayList<>();

        for (String input : entry.inputs()) {
            if (input.startsWith("(")) {
                continue;
            }
            // An input may list alternatives as "a | b | c"; any one satisfies it.
            boolean have = false;
            for (String option : input.split("\\|")) {
                String candidate = option.trim();
                // Ignore the "(N options)" annotation added for readability.
                if (candidate.isEmpty() || candidate.startsWith("...")) {
                    continue;
                }
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (id.equals(candidate) || id.endsWith(":" + candidate)) {
                        have = true;
                        break;
                    }
                }
                if (have) {
                    break;
                }
            }
            if (!have) {
                missing.add(input.split("\\|")[0].trim());
            }
        }

        if (missing.isEmpty()) {
            return Actions.Result.fail("cannot craft " + itemId + ": no crafting-grid recipe matched. "
                    + "Check the recipe with find_item - it may need a different ingredient or a machine.");
        }
        return Actions.Result.fail("cannot craft " + itemId + ": you are missing "
                + String.join(", ", missing));
    }

    /** What the bot could make right now. Bounded work: only recipes that consume held items. */
    public static List<String> craftableNow(ServerPlayer bot, int limit) {
        List<String> out = new ArrayList<>();
        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        if (index == null) {
            return out;
        }

        var inventory = bot.getInventory();
        java.util.Set<String> holding = new java.util.HashSet<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                holding.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        // Worked out once rather than per candidate: it is a few hundred block lookups.
        int maxSide = availableGridSide(bot);

        java.util.LinkedHashSet<String> tried = new java.util.LinkedHashSet<>();
        for (String held : holding) {
            for (RecipeKnowledgeIndex.Entry entry : index.recipesUsing(held)) {
                if (!entry.outputs().isEmpty()) {
                    tried.add(entry.outputs().get(0));
                }
            }
        }

        for (String candidate : tried) {
            if (plan(bot, candidate, maxSide) != null) {
                out.add(candidate);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
