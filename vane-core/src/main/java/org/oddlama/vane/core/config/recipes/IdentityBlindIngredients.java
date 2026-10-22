package org.oddlama.vane.core.config.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.oddlama.vane.core.Core;
import org.oddlama.vane.core.Listener;
import org.oddlama.vane.core.module.Context;

/**
 * Crafting ingredients that must be one exact unstackable item. ThirdPlaceMcTweaks stamps every
 * unstackable item with an identity (four thirdplacetweaks: keys), and the server's exact
 * ingredient match compares the whole item, so a stamped item never matched (the cursed book in
 * the Soulbound recipe). Such ingredients are registered by material instead, and this listener
 * clears the result unless every grid item of that material is one of the exact items, ignoring
 * the identity keys.
 */
public class IdentityBlindIngredients extends Listener<Core> {

    private static final List<NamespacedKey> IDENTITY_KEYS = List.of(
        new NamespacedKey("thirdplacetweaks", "iid"),
        new NamespacedKey("thirdplacetweaks", "origin"),
        new NamespacedKey("thirdplacetweaks", "ts"),
        new NamespacedKey("thirdplacetweaks", "by")
    );

    // Recipe key -> the exact items its loosened ingredients stand for
    private static final Map<NamespacedKey, List<ItemStack>> exact_items = new ConcurrentHashMap<>();

    public IdentityBlindIngredients(Context<Core> context) {
        super(context);
    }

    /**
     * Returns the choice to register for a crafting ingredient of the given recipe. An exact choice
     * of one unstackable item becomes a material choice, and the item is remembered for the check.
     */
    public static RecipeChoice choice(final NamespacedKey recipe_key, final RecipeChoice choice) {
        if (!(choice instanceof RecipeChoice.ExactChoice exact) || exact.getChoices().size() != 1) {
            return choice;
        }

        final var item = exact.getChoices().get(0);
        if (item.getMaxStackSize() != 1) {
            return choice;
        }

        exact_items.computeIfAbsent(recipe_key, k -> new ArrayList<>()).add(item.clone());
        return new RecipeChoice.MaterialChoice(item.getType());
    }

    /** Drops what was remembered for a recipe, before it is removed or registered again. */
    public static void forget(final NamespacedKey recipe_key) {
        exact_items.remove(recipe_key);
    }

    private static boolean matches(final ItemStack item, final ItemStack exact) {
        var plain = item;
        final var pdc = item.getPersistentDataContainer();
        if (IDENTITY_KEYS.stream().anyMatch(pdc::has)) {
            plain = item.clone();
            plain.editPersistentDataContainer(p -> IDENTITY_KEYS.forEach(p::remove));
        }
        return plain.isSimilar(exact);
    }

    private static boolean satisfied(final List<ItemStack> exacts, final ItemStack[] grid) {
        for (final var item : grid) {
            if (item == null || item.isEmpty()) {
                continue;
            }

            boolean constrained = false;
            boolean matched = false;
            for (final var exact : exacts) {
                if (exact.getType() != item.getType()) {
                    continue;
                }
                constrained = true;
                if (matches(item, exact)) {
                    matched = true;
                    break;
                }
            }

            if (constrained && !matched) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void on_prepare_item_craft(final PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }

        final var exacts = exact_items.get(keyed.getKey());
        if (exacts != null && !satisfied(exacts, event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void on_crafter_craft(final CrafterCraftEvent event) {
        final var exacts = exact_items.get(event.getRecipe().getKey());
        if (exacts == null) {
            return;
        }

        if (
            !(event.getBlock().getState(false) instanceof Crafter crafter) ||
            !satisfied(exacts, crafter.getInventory().getContents())
        ) {
            event.setCancelled(true);
        }
    }
}
