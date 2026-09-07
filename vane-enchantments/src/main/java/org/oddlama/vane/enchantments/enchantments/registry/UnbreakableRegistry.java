package org.oddlama.vane.enchantments.enchantments.registry;

import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
import org.bukkit.enchantments.Enchantment;
import org.oddlama.vane.enchantments.CustomEnchantmentRegistry;

public class UnbreakableRegistry extends CustomEnchantmentRegistry {

    public UnbreakableRegistry(RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> composeEvent) {
        super("unbreakable", ItemTypeTagKeys.ENCHANTABLE_DURABILITY, 1);
        // Deliberately NOT exclusive with Unbreaking/Mending: Unbreakable supersedes them.
        // The Unbreakable listener strips both from the anvil result instead of the anvil
        // refusing the combination, so players don't need a grindstone first.
        this.register(composeEvent);
    }
}
