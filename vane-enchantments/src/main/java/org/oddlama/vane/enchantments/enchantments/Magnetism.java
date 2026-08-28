package org.oddlama.vane.enchantments.enchantments;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.oddlama.vane.annotation.config.ConfigInt;
import org.oddlama.vane.annotation.enchantment.Rarity;
import org.oddlama.vane.annotation.enchantment.VaneEnchantment;
import org.oddlama.vane.core.config.recipes.RecipeList;
import org.oddlama.vane.core.config.recipes.ShapedRecipeDefinition;
import org.oddlama.vane.core.enchantments.CustomEnchantment;
import org.oddlama.vane.core.module.Context;
import org.oddlama.vane.enchantments.Enchantments;
import org.oddlama.vane.util.StorageUtil;

@VaneEnchantment(name = "magnetism", max_level = 2, rarity = Rarity.UNCOMMON, treasure = true, target = EnchantmentTarget.ARMOR_FEET)
public class Magnetism extends CustomEnchantment<Enchantments> {

    // Leave items this close to the player alone; vanilla pickup already handles them
    // and nudging them further would fight the pickup-delay/merge mechanics.
    private static final double MIN_PULL_DISTANCE = 1.0;

    // Velocity added toward the player each pull tick, before clamping.
    private static final double PULL_ACCEL = 0.12;

    // Hard clamp on the resulting velocity so items never orbit, overshoot or jitter.
    private static final double MAX_PULL_SPEED = 0.9;

    // PDC keys used to attribute a dropped item to the player who caused the drop.
    private static final NamespacedKey OWNER_KEY = StorageUtil.namespaced_key("vane_enchantments", "magnetism_owner");
    private static final NamespacedKey DROPPED_AT_KEY = StorageUtil.namespaced_key(
        "vane_enchantments",
        "magnetism_dropped_at"
    );

    @ConfigInt(
        def = 10,
        min = 1,
        max = 200,
        desc = "How often (in ticks) the magnet pull task scans for eligible items. Lower values pull more smoothly at a higher performance cost."
    )
    public int config_pull_period_ticks;

    @ConfigInt(def = 6, min = 1, max = 64, desc = "Pull radius in blocks at enchantment level 1.")
    public int config_pull_radius_level_1;

    @ConfigInt(def = 10, min = 1, max = 64, desc = "Pull radius in blocks at enchantment level 2.")
    public int config_pull_radius_level_2;

    @ConfigInt(
        def = 30,
        min = 1,
        max = 3600,
        desc = "How long (in seconds) a dropped item stays attributed to the player who caused its drop and eligible for magnet pull. Once this window elapses the item is permanently ineligible, even for its owner."
    )
    public int config_attribution_window_seconds;

    private BukkitTask pull_task;

    public Magnetism(Context<Enchantments> context) {
        super(context);
    }

    @Override
    public RecipeList default_recipes() {
        return RecipeList.of(
            new ShapedRecipeDefinition("generic")
                .shape("ipi", "pbp", "ipi")
                .set_ingredient('b', "vane_enchantments:ancient_tome_of_the_gods")
                .set_ingredient('i', Material.IRON_BLOCK)
                .set_ingredient('p', Material.ENDER_PEARL)
                .result(on("vane_enchantments:enchanted_ancient_tome_of_the_gods"))
        );
    }

    @Override
    protected void on_enable() {
        super.on_enable();
        start_pull_task();
    }

    @Override
    protected void on_disable() {
        stop_pull_task();
        super.on_disable();
    }

    @Override
    public void on_config_change() {
        super.on_config_change();
        // Restart with the new period in case it changed.
        stop_pull_task();
        start_pull_task();
    }

    private void start_pull_task() {
        pull_task = schedule_task_timer(this::pull_tick, config_pull_period_ticks, config_pull_period_ticks);
    }

    private void stop_pull_task() {
        if (pull_task != null) {
            pull_task.cancel();
            pull_task = null;
        }
    }

    // --- Attribution tagging -------------------------------------------------
    //
    // Every drop caused by a player is tagged with their UUID and the drop
    // timestamp, regardless of whether the causing player is currently wearing
    // Magnetism boots. This lets a player who equips the boots shortly after
    // the drop still benefit, while items tagged for someone else (or nobody)
    // stay completely invisible to the magnet and behave as 100% vanilla items
    // (hoppers, mob farms, normal pickup, merging, despawn are all unaffected).

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on_block_drop_item(final BlockDropItemEvent event) {
        final var owner = event.getPlayer().getUniqueId();
        for (final var item : event.getItems()) {
            tag_item(item, owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on_entity_drop_item(final EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }

        final var killer = living.getKiller();
        if (killer == null) {
            return;
        }

        tag_item(event.getItemDrop(), killer.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on_player_drop_item(final PlayerDropItemEvent event) {
        tag_item(event.getItemDrop(), event.getPlayer().getUniqueId());
    }

    private void tag_item(final Item item, final java.util.UUID owner) {
        final var pdc = item.getPersistentDataContainer();
        pdc.set(OWNER_KEY, PersistentDataType.STRING, owner.toString());
        pdc.set(DROPPED_AT_KEY, PersistentDataType.LONG, System.currentTimeMillis());
    }

    // --- Pull loop -------------------------------------------------------------

    private void pull_tick() {
        final long window_ms = config_attribution_window_seconds * 1000L;
        final long now = System.currentTimeMillis();

        for (final var player : Bukkit.getOnlinePlayers()) {
            if (!player.isValid() || player.isDead()) {
                continue;
            }

            final var mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                continue;
            }

            final var boots = player.getInventory().getBoots();
            if (boots == null) {
                continue;
            }

            final int level = boots.getEnchantmentLevel(this.bukkit());
            if (level == 0) {
                continue;
            }

            pull_nearby_items(player, radius_for_level(level), now, window_ms);
        }
    }

    private int radius_for_level(final int level) {
        if (level <= 1) {
            return config_pull_radius_level_1;
        }
        return config_pull_radius_level_2;
    }

    private void pull_nearby_items(final Player player, final int radius, final long now, final long window_ms) {
        final var owner_id = player.getUniqueId().toString();
        final var loc = player.getLocation();

        final var nearby = player
            .getWorld()
            .getNearbyEntitiesByType(Item.class, loc, radius, radius, radius, item -> item.isValid() && !item.isDead());

        for (final var item : nearby) {
            final var pdc = item.getPersistentDataContainer();
            final var tagged_owner = pdc.get(OWNER_KEY, PersistentDataType.STRING);
            if (tagged_owner == null || !tagged_owner.equals(owner_id)) {
                // Untagged, or tagged for a different player: never touched by any magnet.
                continue;
            }

            final long dropped_at = pdc.getOrDefault(DROPPED_AT_KEY, PersistentDataType.LONG, 0L);
            if (now - dropped_at > window_ms) {
                // Settled: permanently ineligible, we never re-tag or refresh this.
                continue;
            }

            pull_item(player, item);
        }
    }

    private void pull_item(final Player player, final Item item) {
        final var to_player = player
            .getLocation()
            .toVector()
            .add(new Vector(0, 1.0, 0))
            .subtract(item.getLocation().toVector());
        final double distance = to_player.length();
        if (distance < MIN_PULL_DISTANCE) {
            // Let vanilla pickup take it from here.
            return;
        }

        final var pull = to_player.normalize().multiply(PULL_ACCEL);
        var new_velocity = item.getVelocity().add(pull);
        if (new_velocity.lengthSquared() > MAX_PULL_SPEED * MAX_PULL_SPEED) {
            new_velocity = new_velocity.normalize().multiply(MAX_PULL_SPEED);
        }
        item.setVelocity(new_velocity);
    }
}
