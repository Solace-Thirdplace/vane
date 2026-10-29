package org.oddlama.vane.trifles.items;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.oddlama.vane.annotation.item.VaneItem;
import org.oddlama.vane.core.config.recipes.RecipeList;
import org.oddlama.vane.core.config.recipes.ShapedRecipeDefinition;
import org.oddlama.vane.core.module.Context;
import org.oddlama.vane.trifles.Trifles;

@VaneItem(
    name = "home_scroll",
    base = Material.WARPED_FUNGUS_ON_A_STICK,
    durability = 25,
    model_data = 0x760000,
    version = 1
)
public class HomeScroll extends Scroll {

    public HomeScroll(Context<Trifles> context) {
        super(context, 10000);
    }

    @Override
    public RecipeList default_recipes() {
        return RecipeList.of(
            new ShapedRecipeDefinition("generic")
                .shape("abc", "epe")
                .set_ingredient('p', "vane_trifles:papyrus_scroll")
                .set_ingredient('e', Material.ENDER_PEARL)
                .set_ingredient('a', Material.CAMPFIRE)
                .set_ingredient('b', Material.GOAT_HORN)
                .set_ingredient('c', Tag.BEDS)
                .result(key().toString())
        );
    }

    // @Override
    // public LootTableList default_loot_tables() {
    //	// TODO spawn scroll with 1 usage! possible with nbt nice.
    // }

    @Override
    public Location teleport_location(final ItemStack scroll, Player player, boolean imminent_teleport) {
        // The stored respawn point only. Whether its bed or anchor is still usable needs a block
        // read, which happens in resolve_destination() on the thread that owns that block.
        final var respawn_point = player.getRespawnLocation(false);
        if (imminent_teleport && respawn_point == null) {
            // "Sleep in a bed to change your respawn point."
            player.sendActionBar(Component.translatable("advancements.adventure.sleep_in_bed.description"));
        }
        return respawn_point;
    }

    @Override
    public Location resolve_destination(final Player player, final Location destination) {
        // Runs in the respawn point's region: validate the bed or anchor and find where to stand.
        final var to_location = player.getRespawnLocation(true);
        if (to_location == null) {
            // "You have no home bed or charged respawn anchor, or it was obstructed"
            // The most cursed sentence in minecraft.
            player.sendActionBar(Component.translatable("block.minecraft.spawn.not_valid"));
        }
        return to_location;
    }
}
