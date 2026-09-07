package org.oddlama.vane.enchantments.enchantments;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.loot.LootTables;
import org.oddlama.vane.annotation.enchantment.Rarity;
import org.oddlama.vane.annotation.enchantment.VaneEnchantment;
import org.oddlama.vane.core.config.loot.LootDefinition;
import org.oddlama.vane.core.config.loot.LootTableList;
import org.oddlama.vane.core.config.recipes.RecipeList;
import org.oddlama.vane.core.config.recipes.ShapedRecipeDefinition;
import org.oddlama.vane.core.enchantments.CustomEnchantment;
import org.oddlama.vane.core.module.Context;
import org.oddlama.vane.enchantments.Enchantments;

@VaneEnchantment(name = "unbreakable", rarity = Rarity.RARE, treasure = true, allow_custom = true)
public class Unbreakable extends CustomEnchantment<Enchantments> {

    // Enchantments that become pointless once an item can no longer take damage.
    // Unbreakable replaces these rather than conflicting with them (see UnbreakableRegistry).
    private static final List<Enchantment> SUPERSEDED = List.of(Enchantment.UNBREAKING, Enchantment.MENDING);

    public Unbreakable(Context<Enchantments> context) {
        super(context);
    }

    /**
     * Removes superseded enchantments (regular and book-stored) from the given stack.
     * Only acts if the stack actually carries Unbreakable.
     *
     * @return true if anything was removed
     */
    private boolean strip_superseded(final ItemStack stack) {
        final var meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }

        boolean changed = false;
        if (meta.hasEnchant(this.bukkit())) {
            for (final var ench : SUPERSEDED) {
                changed |= meta.removeEnchant(ench);
            }
        }
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchant(this.bukkit())) {
            for (final var ench : SUPERSEDED) {
                changed |= storage.removeStoredEnchant(ench);
            }
        }

        if (changed) {
            stack.setItemMeta(meta);
        }
        return changed;
    }

    /**
     * Once superseded enchantments are stripped, the anvil operation may have become a no-op
     * (e.g. an Unbreaking book placed on an already unbreakable tool). Vanilla shows no result
     * for incompatible combinations, so mirror that instead of offering a paid identity operation.
     */
    private static boolean is_noop(final ItemStack base, final ItemStack result) {
        final var probe = base.clone();
        final var result_meta = result.getItemMeta();
        if (result_meta instanceof Repairable repairable_result) {
            probe.editMeta(meta -> {
                if (meta instanceof Repairable repairable_probe) {
                    repairable_probe.setRepairCost(repairable_result.getRepairCost());
                }
            });
        }
        return probe.isSimilar(result);
    }

    // Unbreakable supersedes Unbreaking and Mending: when it ends up on an anvil result
    // together with them, drop them instead of refusing the combination.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void on_prepare_anvil(final PrepareAnvilEvent event) {
        final var result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        final var stripped = result.clone();
        if (!strip_superseded(stripped)) {
            return;
        }

        final var base = event.getInventory().getFirstItem();
        if (base != null && is_noop(base, stripped)) {
            event.setResult(null);
            return;
        }
        event.setResult(stripped);
    }

    @Override
    public RecipeList default_recipes() {
        return RecipeList.of(
            new ShapedRecipeDefinition("generic")
                .shape("waw", "nbn", "tst")
                .set_ingredient('b', "vane_enchantments:ancient_tome_of_the_gods")
                .set_ingredient('w', Material.WITHER_ROSE)
                .set_ingredient('a', Material.ENCHANTED_GOLDEN_APPLE)
                .set_ingredient('n', Material.NETHERITE_INGOT)
                .set_ingredient('t', Material.TOTEM_OF_UNDYING)
                .set_ingredient('s', Material.NETHER_STAR)
                .result(on("vane_enchantments:enchanted_ancient_tome_of_the_gods"))
        );
    }

    @Override
    public LootTableList default_loot_tables() {
        return LootTableList.of(
            new LootDefinition("generic")
                .in(LootTables.ABANDONED_MINESHAFT)
                .add(1.0 / 120, 1, 1, on("vane_enchantments:enchanted_ancient_tome_of_the_gods")),
            new LootDefinition("bastion")
                .in(LootTables.BASTION_TREASURE)
                .add(1.0 / 30, 1, 1, on("vane_enchantments:enchanted_ancient_tome_of_the_gods"))
        );
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void on_player_item_damage(final PlayerItemDamageEvent event) {
        // Check enchantment
        final var item = event.getItem();
        if (item.getEnchantmentLevel(this.bukkit()) == 0) {
            return;
        }

        // Set item unbreakable to prevent further event calls
        final var meta = item.getItemMeta();
        meta.setUnbreakable(true);
        // Also hide the internal unbreakable tag on the client
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);

        // Prevent damage
        event.setDamage(0);
        event.setCancelled(true);
    }
}
