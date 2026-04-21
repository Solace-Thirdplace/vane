package org.oddlama.vane.enchantments.enchantments;

import static org.oddlama.vane.util.ItemUtil.damage_item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.oddlama.vane.annotation.enchantment.Rarity;
import org.oddlama.vane.annotation.enchantment.VaneEnchantment;
import org.oddlama.vane.core.config.recipes.RecipeList;
import org.oddlama.vane.core.config.recipes.ShapedRecipeDefinition;
import org.oddlama.vane.core.enchantments.CustomEnchantment;
import org.oddlama.vane.core.module.Context;
import org.oddlama.vane.enchantments.Enchantments;

@VaneEnchantment(name = "tree_felling", max_level = 3, rarity = Rarity.UNCOMMON, treasure = true, target = EnchantmentTarget.TOOL)
public class TreeFelling extends CustomEnchantment<Enchantments> {

    private static final int MAX_LOG_COUNT = 256;

    // Ticks per block break at each level (1-indexed: index 0 unused)
    private static final int[] TICKS_PER_LEVEL = { 0, 4, 2, 1 };
    // Max concurrent tree felling operations per player at each level
    private static final int[] MAX_CONCURRENT_PER_LEVEL = { 0, 1, 3, 5 };

    // Track active felling count per player (for concurrency limiting)
    private final Map<UUID, Integer> active_felling_count = new HashMap<>();

    // Blocks currently queued for felling — skip these in the event handler
    private final Set<Block> blocks_being_felled = new HashSet<>();

    // All 26 directions (3x3x3 cube minus center) for full connectivity
    private static final int[][] NEIGHBORS = new int[26][3];
    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    NEIGHBORS[idx][0] = dx;
                    NEIGHBORS[idx][1] = dy;
                    NEIGHBORS[idx][2] = dz;
                    idx++;
                }
            }
        }
    }

    // Cardinal faces for leaf BFS (leaves only connect cardinally)
    private static final BlockFace[] CARDINAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
        BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    public TreeFelling(Context<Enchantments> context) {
        super(context);
    }

    @Override
    public RecipeList default_recipes() {
        return RecipeList.of(
            new ShapedRecipeDefinition("generic")
                .shape("aga", "gbg", "aga")
                .set_ingredient('b', "vane_enchantments:ancient_tome_of_the_gods")
                .set_ingredient('a', Material.GOLDEN_AXE)
                .set_ingredient('g', Material.GOLD_BLOCK)
                .result(on("vane_enchantments:enchanted_ancient_tome_of_the_gods"))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on_block_break(BlockBreakEvent event) {
        final var player = event.getPlayer();

        // Don't work in creative mode
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        final var block = event.getBlock();
        final var block_type = block.getType();

        // Skip blocks that are part of an ongoing felling operation
        if (blocks_being_felled.contains(block)) {
            return;
        }

        // Only trigger on log blocks (not stripped)
        if (!is_natural_log(block_type)) {
            return;
        }

        // Check enchantment level on the held axe
        final var item = player.getEquipment().getItemInMainHand();
        final var level = item.getEnchantmentLevel(this.bukkit());
        if (level == 0) {
            return;
        }

        // Check concurrency limit for this level
        final var player_id = player.getUniqueId();
        final int current_count = active_felling_count.getOrDefault(player_id, 0);
        final int max_concurrent = level < MAX_CONCURRENT_PER_LEVEL.length ? MAX_CONCURRENT_PER_LEVEL[level] : MAX_CONCURRENT_PER_LEVEL[MAX_CONCURRENT_PER_LEVEL.length - 1];
        if (current_count >= max_concurrent) {
            return;
        }

        // Immediately increment the count to prevent races from rapid block breaking
        active_felling_count.put(player_id, current_count + 1);

        // Collect the tree
        final var tree = collect_tree(block);
        if (tree == null) {
            // Decrement the count since we're not actually felling
            decrement_felling_count(player_id);
            return;
        }

        // Sort logs bottom to top by Y coordinate
        tree.logs.sort(Comparator.comparingInt(b -> b.getY()));

        // Remove the initially broken block from the list (it's already being broken by the event)
        tree.logs.remove(block);

        if (tree.logs.isEmpty() && tree.leaves.isEmpty()) {
            decrement_felling_count(player_id);
            return;
        }

        // Determine speed from level
        final int ticks_per_block = level < TICKS_PER_LEVEL.length ? TICKS_PER_LEVEL[level] : TICKS_PER_LEVEL[TICKS_PER_LEVEL.length - 1];

        // Start progressive felling
        final var all_blocks = new ArrayList<Block>();
        all_blocks.addAll(tree.logs);
        // Sort leaves by Y so they break top-down (natural look)
        tree.leaves.sort(Comparator.comparingInt(b -> b.getY()));
        all_blocks.addAll(tree.leaves);

        // Register all blocks as being felled to prevent recursive triggers
        blocks_being_felled.addAll(all_blocks);

        // Start progressive felling with a cancellable timer
        new BukkitRunnable() {
            int index = 0;
            boolean finished = false;

            @Override
            public void run() {
                if (finished) return;

                // Check if axe still exists
                final var current_item = player.getEquipment().getItemInMainHand();
                if (current_item.getType() == Material.AIR || current_item.getAmount() <= 0) {
                    finish();
                    return;
                }

                // Check enchantment still present (axe wasn't swapped)
                if (current_item.getEnchantmentLevel(TreeFelling.this.bukkit()) == 0) {
                    finish();
                    return;
                }

                // Skip consecutive already-broken blocks (e.g. leaves that decayed naturally)
                while (index < all_blocks.size() && all_blocks.get(index).getType() == Material.AIR) {
                    index++;
                }

                if (index >= all_blocks.size()) {
                    finish();
                    return;
                }

                final var target = all_blocks.get(index);
                index++;

                final boolean is_log = is_natural_log(target.getType());

                // Fire a BlockBreakEvent for protection plugin compatibility
                final var break_event = new BlockBreakEvent(target, player);
                Bukkit.getPluginManager().callEvent(break_event);
                if (break_event.isCancelled()) {
                    return;
                }

                // Break the block
                final boolean has_silk_touch = current_item.containsEnchantment(Enchantment.SILK_TOUCH);
                if (is_log) {
                    target.breakNaturally(current_item);
                    damage_item(player, current_item, 1);
                } else if (has_silk_touch) {
                    target.breakNaturally(current_item);
                } else {
                    // Break naturally without tool — drops saplings, sticks, apples as if decayed
                    target.breakNaturally();
                }
            }

            private void finish() {
                if (finished) return;
                finished = true;
                cancel();
                for (final var b : all_blocks) {
                    blocks_being_felled.remove(b);
                }
                decrement_felling_count(player_id);
            }
        }.runTaskTimer(get_module(), 1L, ticks_per_block);
    }

    private void decrement_felling_count(UUID player_id) {
        final int count = active_felling_count.getOrDefault(player_id, 1);
        if (count <= 1) {
            active_felling_count.remove(player_id);
        } else {
            active_felling_count.put(player_id, count - 1);
        }
    }

    /**
     * Checks if a material is a natural (non-stripped) log type.
     */
    private static boolean is_natural_log(Material material) {
        return Tag.LOGS.isTagged(material) && !is_stripped(material);
    }

    /**
     * Checks if a material is a stripped log/wood variant.
     */
    private static boolean is_stripped(Material material) {
        return material.name().startsWith("STRIPPED_");
    }

    /**
     * Gets the wood type family for a log material (e.g., OAK_LOG and OAK_WOOD share the same family).
     */
    private static String get_wood_family(Material material) {
        final var name = material.name();
        if (name.endsWith("_LOG")) {
            return name.substring(0, name.length() - 4);
        } else if (name.endsWith("_WOOD")) {
            return name.substring(0, name.length() - 5);
        } else if (name.endsWith("_STEM")) {
            return name.substring(0, name.length() - 5);
        } else if (name.endsWith("_HYPHAE")) {
            return name.substring(0, name.length() - 7);
        }
        return name;
    }

    /**
     * Checks if two log materials belong to the same wood family.
     */
    private static boolean same_wood_family(Material a, Material b) {
        return get_wood_family(a).equals(get_wood_family(b));
    }

    /**
     * Checks if a material is a leaf type.
     */
    private static boolean is_leaf_material(Material material) {
        return Tag.LEAVES.isTagged(material);
    }

    /**
     * Gets the leaf type family matching a wood family.
     * E.g., "OAK" → "OAK", "BIRCH" → "BIRCH", "DARK_OAK" → "DARK_OAK".
     */
    private static String get_leaf_family(Material leaf_material) {
        final var name = leaf_material.name();
        if (name.endsWith("_LEAVES")) {
            return name.substring(0, name.length() - 7);
        }
        return name;
    }

    /**
     * Maps wood families to their corresponding leaf family.
     * Most are 1:1 except some special cases.
     */
    private static boolean leaf_matches_wood(Material leaf, String wood_family) {
        final var leaf_family = get_leaf_family(leaf);
        return leaf_family.equals(wood_family);
    }

    /**
     * Result of tree collection containing logs and leaves.
     */
    private static class TreeResult {
        final List<Block> logs;
        final List<Block> leaves;

        TreeResult(List<Block> logs, List<Block> leaves) {
            this.logs = logs;
            this.leaves = leaves;
        }
    }

    /**
     * Collects all blocks belonging to a natural tree starting from the given log block.
     * Returns null if the structure doesn't appear to be a natural tree.
     *
     * Validates: same wood family, ground connection, matching non-persistent leaves,
     * no stripped logs, max 256 logs. Leaf presence is checked during the log BFS
     * to avoid a separate full-tree scan. Only returns logs at or above origin Y.
     */
    private TreeResult collect_tree(Block origin) {
        final var origin_type = origin.getType();
        final var wood_family = get_wood_family(origin_type);
        final int origin_y = origin.getY();

        final var visited = new HashSet<Block>();
        final var queue = new ArrayDeque<Block>();
        final var logs_above = new ArrayList<Block>();
        int total_log_count = 0;

        queue.add(origin);
        visited.add(origin);

        boolean has_ground_connection = false;
        boolean has_matching_leaves = false;

        while (!queue.isEmpty()) {
            if (total_log_count > MAX_LOG_COUNT) {
                return null;
            }

            final var current = queue.poll();
            total_log_count++;

            if (current.getY() >= origin_y) {
                logs_above.add(current);
            }

            // Check for ground connection beneath this log
            final var below = current.getRelative(BlockFace.DOWN);
            if (is_ground_block(below.getType())) {
                has_ground_connection = true;
            }

            // Explore all 26 neighbors for connected logs
            for (final var offset : NEIGHBORS) {
                final var neighbor = current.getRelative(offset[0], offset[1], offset[2]);
                if (visited.contains(neighbor)) {
                    continue;
                }

                final var neighbor_type = neighbor.getType();

                // Reject the tree if we find a stripped log connected to it
                if (is_stripped(neighbor_type) && Tag.LOGS.isTagged(neighbor_type)) {
                    return null;
                }

                if (is_natural_log(neighbor_type) && same_wood_family(origin_type, neighbor_type)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }

            // Lightweight leaf check during log scan (avoids separate full-tree leaf BFS for validation)
            if (!has_matching_leaves) {
                for (final var face : CARDINAL_FACES) {
                    final var adj = current.getRelative(face);
                    final var adj_type = adj.getType();
                    if (is_leaf_material(adj_type) && leaf_matches_wood(adj_type, wood_family)) {
                        if (adj.getBlockData() instanceof Leaves ld && !ld.isPersistent()) {
                            has_matching_leaves = true;
                            break;
                        }
                    }
                }
            }
        }

        if (!has_ground_connection || !has_matching_leaves) {
            return null;
        }

        // Single-pass leaf collection from all logs at/above break point
        final var leaves = collect_leaves(logs_above, wood_family);

        return new TreeResult(logs_above, leaves);
    }

    /**
     * Collects non-persistent leaves belonging to the given logs via single-pass BFS.
     * Seeds from all logs at once (instead of per-log), follows increasing leaf distance.
     * Uses visited.add() return value to avoid redundant contains+add hash lookups.
     */
    private List<Block> collect_leaves(List<Block> logs, String wood_family) {
        final var log_set = new HashSet<>(logs);
        final var visited = new HashSet<Block>();
        final var queue = new ArrayDeque<Block>();
        final var leaves = new ArrayList<Block>();

        // Seed: distance-1 leaves adjacent to any of our logs
        for (final var log : logs) {
            for (final var face : CARDINAL_FACES) {
                final var neighbor = log.getRelative(face);
                if (!visited.add(neighbor)) continue;
                final var neighbor_type = neighbor.getType();
                if (!is_leaf_material(neighbor_type) || !leaf_matches_wood(neighbor_type, wood_family)) continue;
                if (neighbor.getBlockData() instanceof Leaves ld && !ld.isPersistent() && ld.getDistance() == 1) {
                    leaves.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        // BFS through leaves following increasing distance
        while (!queue.isEmpty()) {
            final var current = queue.poll();
            final int current_dist = ((Leaves) current.getBlockData()).getDistance();

            for (final var face : CARDINAL_FACES) {
                final var neighbor = current.getRelative(face);
                if (!visited.add(neighbor)) continue;
                final var neighbor_type = neighbor.getType();
                if (!is_leaf_material(neighbor_type) || !leaf_matches_wood(neighbor_type, wood_family)) continue;
                if (!(neighbor.getBlockData() instanceof Leaves ld) || ld.isPersistent()) continue;
                final int nd = ld.getDistance();
                if (nd < current_dist || nd > 7) continue;
                if (nd == 1 && !is_adjacent_to_our_log(neighbor, log_set)) continue;
                leaves.add(neighbor);
                queue.add(neighbor);
            }
        }

        return leaves;
    }

    /**
     * Checks if a leaf block is cardinally adjacent to one of our tree's log blocks.
     */
    private static boolean is_adjacent_to_our_log(Block leaf, Set<Block> our_logs) {
        for (final var face : CARDINAL_FACES) {
            if (our_logs.contains(leaf.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a material is a valid ground block that a tree can grow on.
     */
    private static boolean is_ground_block(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, PODZOL, MYCELIUM, ROOTED_DIRT, COARSE_DIRT, MUD,
                 MUDDY_MANGROVE_ROOTS, MOSS_BLOCK, CLAY -> true;
            default -> false;
        };
    }
}
