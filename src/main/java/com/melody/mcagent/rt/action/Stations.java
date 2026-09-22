package com.melody.mcagent.rt.action;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The two machines a player drives through a GUI that are not containers: the anvil and the
 * enchanting table.
 *
 * <p>Neither is a {@link net.minecraft.world.Container}, so {@link Containers} cannot see them, and
 * a right-click only gets as far as installing the vanilla menu on a player who has no client to
 * click it. That gap used to end with the model being told "used item on Anvil" and nothing having
 * happened, which is the worst possible outcome: the bot reports success and the pickaxe it was
 * meant to save breaks on the next block.
 *
 * <p>So both operations are performed here, in the order a player performs them - put the item in
 * the input slot, put the second item in the other input slot, press the button or take the result,
 * take everything back out - and reported with the numbers that make the outcome checkable:
 * durability before and after, the levels actually spent, and the enchantment actually applied.
 *
 * <p>Every path puts the bot's items back where they came from. A failed repair that ate the pickaxe
 * would be worse than not having the tool at all.
 */
public final class Stations {

    /** Vanilla charges one lapis per offer slot, so the third offer needs three. */
    private static final int MAX_OFFERS = 3;

    private Stations() {
    }

    /** Is this a machine {@link #repair} or {@link #enchant} can drive? */
    public static boolean isStation(BlockState state) {
        return state.getBlock() instanceof AnvilBlock || state.getBlock() instanceof EnchantingTableBlock;
    }

    // --- anvil ----------------------------------------------------------------------------------

    /**
     * Repair a worn item on an anvil.
     *
     * <p>Two things repair an item there and both are offered: a repair material (a diamond for a
     * diamond pickaxe, leather for leather armour) and a second copy of the same item, which merges
     * the two durabilities. The material is preferred because it does not consume a second tool, and
     * {@code material} overrides the choice entirely.
     *
     * @param itemQuery     the item to repair, by id or name
     * @param materialQuery what to repair it with, or blank to pick from what the bot carries
     */
    public static Actions.Result repair(ServerPlayer bot, BlockPos pos, String itemQuery,
                                        String materialQuery) {
        if (!(bot.level().getBlockState(pos).getBlock() instanceof AnvilBlock)) {
            return Actions.Result.fail("there is no anvil at " + pos.toShortString());
        }
        if (!Actions.canReach(bot, pos)) {
            return Actions.Result.fail("the anvil is out of reach");
        }
        if (itemQuery == null || itemQuery.isBlank()) {
            return Actions.Result.fail("say which item to repair");
        }

        int slot = findSlot(bot, itemQuery);
        if (slot < 0) {
            return Actions.Result.fail("you are not carrying any '" + itemQuery + "'");
        }
        ItemStack tool = bot.getInventory().getItem(slot).copy();
        String name = tool.getHoverName().getString();
        if (!tool.isDamageableItem()) {
            return Actions.Result.fail(name + " has no durability, so an anvil cannot repair it");
        }
        int damageBefore = tool.getDamageValue();
        if (damageBefore <= 0) {
            return Actions.Result.ok(name + " is already at full durability ("
                    + tool.getMaxDamage() + "/" + tool.getMaxDamage() + "); nothing to repair");
        }

        ItemStack addition;
        if (materialQuery != null && !materialQuery.isBlank()) {
            int materialSlot = findSlot(bot, materialQuery);
            if (materialSlot < 0) {
                return Actions.Result.fail("you are not carrying any '" + materialQuery + "'");
            }
            if (materialSlot == slot) {
                // Same stack named twice. Taking it out for both slots would hand the anvil two
                // copies of one item and produce a duplicate, so this is refused rather than
                // resolved: say what to carry instead.
                return Actions.Result.fail("that is the item you asked to repair; name the material "
                        + "you want to repair it with (or a second copy of it) instead");
            }
            addition = bot.getInventory().getItem(materialSlot).copy();
            bot.getInventory().setItem(materialSlot, ItemStack.EMPTY);
        } else {
            int additionSlot = findAddition(bot, tool, slot);
            if (additionSlot < 0) {
                return Actions.Result.fail("you have nothing to repair " + name + " with: carry a "
                        + "second one, or the material it is made of (a diamond repairs diamond "
                        + "tools, leather repairs leather armour)");
            }
            addition = bot.getInventory().getItem(additionSlot).copy();
            bot.getInventory().setItem(additionSlot, ItemStack.EMPTY);
        }

        AnvilMenu menu = open(bot, pos) instanceof AnvilMenu anvil ? anvil : null;
        if (menu == null) {
            bot.getInventory().setItem(slot, tool);
            give(bot, addition);
            return Actions.Result.fail("the anvil did not open (another menu may be in the way)");
        }

        try {
            bot.getInventory().setItem(slot, ItemStack.EMPTY);
            menu.getSlot(AnvilMenu.INPUT_SLOT).set(tool);
            menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(addition);

            ItemStack produced = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().copy();
            if (produced.isEmpty()) {
                return Actions.Result.fail("the anvil has nothing to produce from " + name + " and "
                        + addition.getHoverName().getString() + " - that material does not repair it");
            }
            int cost = menu.getCost();
            if (!bot.getAbilities().instabuild && bot.experienceLevel < cost) {
                return Actions.Result.fail("that repair costs " + cost + " experience level(s) and you "
                        + "have " + bot.experienceLevel + "; the work is set up on the anvil, so come "
                        + "back with the levels");
            }

            int levelsBefore = bot.experienceLevel;
            // QUICK_MOVE is shift-click: it moves the result into the inventory and runs the anvil's
            // own onTake, which is where the levels are charged and the materials consumed.
            menu.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, bot);

            // A successful shift-click leaves the result in the inventory. If there was no room for
            // it vanilla leaves it in the input slot instead, so recover it by hand rather than
            // reporting a repair that ate the tool.
            ItemStack repaired = firstMatching(bot, produced);
            if (repaired.isEmpty()) {
                repaired = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().copy();
                menu.getSlot(AnvilMenu.INPUT_SLOT).set(ItemStack.EMPTY);
                give(bot, repaired);
            }
            if (repaired.isEmpty()) {
                return Actions.Result.fail("the anvil took the items but the result went nowhere; "
                        + "check what you are carrying");
            }
            if (repaired.getDamageValue() >= damageBefore) {
                return Actions.Result.fail("the anvil took the items but " + name + " is no less "
                        + "damaged than before; nothing was repaired");
            }
            return Actions.Result.ok("repaired " + name + ": durability "
                    + (tool.getMaxDamage() - damageBefore) + " -> "
                    + (repaired.getMaxDamage() - repaired.getDamageValue()) + "/"
                    + repaired.getMaxDamage() + ", using " + addition.getHoverName().getString()
                    + ", for " + (levelsBefore - bot.experienceLevel) + " experience level(s)");
        } finally {
            sweepBack(bot, menu, AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT);
            close(bot);
        }
    }

    // --- enchanting table -----------------------------------------------------------------------

    /**
     * Enchant an item at an enchanting table.
     *
     * <p>The table shows three offers and vanilla only decides which enchantment each one is when it
     * is taken, so the model chooses by price: {@code offer} 1 is the cheapest, 3 the strongest, and
     * the offers are reported either way. Costs experience levels and one lapis per offer slot.
     */
    public static Actions.Result enchant(ServerPlayer bot, BlockPos pos, String itemQuery, int offer) {
        if (!(bot.level().getBlockState(pos).getBlock() instanceof EnchantingTableBlock)) {
            return Actions.Result.fail("there is no enchanting table at " + pos.toShortString());
        }
        if (!Actions.canReach(bot, pos)) {
            return Actions.Result.fail("the enchanting table is out of reach");
        }
        if (itemQuery == null || itemQuery.isBlank()) {
            return Actions.Result.fail("say which item to enchant");
        }
        int wanted = offer <= 0 ? 1 : Math.min(offer, MAX_OFFERS);

        int slot = findSlot(bot, itemQuery);
        if (slot < 0) {
            return Actions.Result.fail("you are not carrying any '" + itemQuery + "'");
        }
        ItemStack item = bot.getInventory().getItem(slot).copy();
        String name = item.getHoverName().getString();
        if (!item.isEnchantable()) {
            return Actions.Result.fail(name + " cannot be enchanted at a table");
        }

        int lapisSlot = findSlot(bot, "minecraft:lapis_lazuli");
        int lapis = lapisSlot < 0 ? 0 : bot.getInventory().getItem(lapisSlot).getCount();
        if (lapis < wanted) {
            return Actions.Result.fail("the " + ordinal(wanted) + " offer needs " + wanted
                    + " lapis lazuli and you are carrying " + lapis);
        }

        EnchantmentMenu menu = open(bot, pos) instanceof EnchantmentMenu table ? table : null;
        if (menu == null) {
            return Actions.Result.fail("the enchanting table did not open (another menu may be in the way)");
        }

        try {
            bot.getInventory().setItem(slot, ItemStack.EMPTY);
            menu.getSlot(0).set(item);
            ItemStack gem = bot.getInventory().getItem(lapisSlot).copyWithCount(wanted);
            bot.getInventory().getItem(lapisSlot).shrink(wanted);
            if (bot.getInventory().getItem(lapisSlot).isEmpty()) {
                bot.getInventory().setItem(lapisSlot, ItemStack.EMPTY);
            }
            menu.getSlot(1).set(gem);

            // slotsChanged recomputes the offers when the inputs change, so this reads what the
            // table is offering for this exact item right now.
            List<String> offers = describeOffers(menu);
            int cost = menu.costs[wanted - 1];
            if (cost <= 0) {
                return Actions.Result.fail("the table offers nothing for " + name + " in that slot "
                        + "(offers cost " + String.join(", ", offers) + " levels)");
            }
            if (bot.experienceLevel < cost) {
                return Actions.Result.fail("the " + ordinal(wanted) + " offer costs " + cost
                        + " experience level(s) and you have " + bot.experienceLevel);
            }

            int levelsBefore = bot.experienceLevel;
            // This is the button a client presses; the server rolls the actual enchantment here.
            menu.clickMenuButton(bot, wanted - 1);

            ItemStack enchanted = menu.getSlot(0).getItem().copy();
            if (!enchanted.isEnchanted()) {
                return Actions.Result.fail("the table charged nothing and applied nothing to " + name);
            }
            return Actions.Result.ok("enchanted " + name + " with " + describeEnchantments(enchanted)
                    + " for " + (levelsBefore - bot.experienceLevel) + " experience level(s)"
                    + " (the three offers cost " + String.join(", ", offers) + " levels)");
        } finally {
            sweepBack(bot, menu, 0, 1);
            close(bot);
        }
    }

    // --- shared ---------------------------------------------------------------------------------

    /**
     * Right-click the block and hand back whatever menu it installed.
     *
     * <p>This is the same call the {@code use} tool makes; the difference is that something here
     * knows what to do with the menu afterwards.
     */
    @Nullable
    private static AbstractContainerMenu open(ServerPlayer bot, BlockPos pos) {
        Actions.useOnBlock(bot, pos, Actions.faceToward(bot, pos));
        return bot.containerMenu;
    }

    /** Put the station away again, so the bot is not left standing at a GUI it cannot see. */
    private static void close(ServerPlayer bot) {
        try {
            bot.closeContainer();
        } catch (Throwable ignored) {
            // Nothing useful to do: the menu closes itself when the bot walks out of reach.
        }
    }

    /**
     * Move whatever is left in the given menu slots back into the bot's inventory.
     *
     * <p>The anvil returns unused material to its second slot by itself and the enchanting table
     * leaves the item where it was; neither is visible to a player who never looks in the GUI, and an
     * item left in a menu that closes is an item destroyed.
     */
    private static void sweepBack(ServerPlayer bot, AbstractContainerMenu menu, int... slots) {
        for (int slot : slots) {
            if (slot >= menu.slots.size()) {
                continue;
            }
            ItemStack left = menu.getSlot(slot).getItem().copy();
            if (left.isEmpty()) {
                continue;
            }
            menu.getSlot(slot).set(ItemStack.EMPTY);
            give(bot, left);
        }
    }

    /** Hand an item back to the bot, or drop it at its feet if there is no room. */
    private static void give(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!bot.getInventory().add(stack)) {
            bot.drop(stack, false);
        }
    }

    /** A copy of the first carried stack identical to this one, or empty. */
    private static ItemStack firstMatching(ServerPlayer bot, ItemStack wanted) {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack candidate = bot.getInventory().getItem(i);
            if (!candidate.isEmpty() && ItemStack.isSameItemSameComponents(candidate, wanted)) {
                return candidate.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * The best thing the bot is carrying to repair this item with.
     *
     * <p>Preference order is deliberate: a repair material first, because it does not consume a
     * second tool, and a duplicate of the item second, because merging two worn tools is what a
     * player does when they have no material. Vanilla decides whether a material counts
     * ({@code Item.isValidRepairItem}), so this follows the anvil rather than a table of its own.
     *
     * @return the inventory slot, or -1 when nothing suitable is carried
     */
    private static int findAddition(ServerPlayer bot, ItemStack tool, int skipSlot) {
        int duplicate = -1;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (i == skipSlot) {
                continue;
            }
            ItemStack candidate = bot.getInventory().getItem(i);
            if (candidate.isEmpty()) {
                continue;
            }
            if (tool.getItem().isValidRepairItem(tool, candidate)) {
                return i;
            }
            if (duplicate < 0 && ItemStack.isSameItemSameComponents(candidate, tool)) {
                duplicate = i;
            }
        }
        return duplicate;
    }

    /** The first inventory slot holding something that answers to this id or name, or -1. */
    private static int findSlot(ServerPlayer bot, String itemQuery) {
        var index = com.melody.mcagent.rt.knowledge.KnowledgeManager.get();
        String itemId = index != null ? index.resolveItem(itemQuery) : itemQuery;
        String wanted = itemId == null ? itemQuery : itemId;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (Actions.matchesItem(bot.getInventory().getItem(i), wanted.toLowerCase(java.util.Locale.ROOT),
                    itemQuery)) {
                return i;
            }
        }
        return -1;
    }

    /** What the three offers cost, for the model to choose between. */
    private static List<String> describeOffers(EnchantmentMenu menu) {
        List<String> out = new ArrayList<>(MAX_OFFERS);
        for (int i = 0; i < MAX_OFFERS; i++) {
            out.add(menu.costs[i] <= 0 ? "unavailable" : String.valueOf(menu.costs[i]));
        }
        return out;
    }

    /** The enchantments on a stack, as a player reads them off the tooltip. */
    private static String describeEnchantments(ItemStack stack) {
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (enchantments.isEmpty()) {
            return "nothing";
        }
        List<String> named = new ArrayList<>();
        for (var entry : enchantments.entrySet()) {
            named.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
        }
        return String.join(", ", named);
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 2 -> "second";
            case 3 -> "third";
            default -> "first";
        };
    }
}
