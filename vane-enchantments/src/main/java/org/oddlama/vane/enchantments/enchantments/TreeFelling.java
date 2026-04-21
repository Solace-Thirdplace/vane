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
    private static final int[] MAX_CONCURRENT_PER_LEVEL = { 0, 1, 2, 3 };

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

        // Collect the tree
        final var tree = collect_tree(block);
        if (tree == null) {
            return;
        }

        // Sort logs bottom to top by Y coordinate
        tree.logs.sort(Comparator.comparingInt(b -> b.getY()));

        // Remove the initially broken block from the list (it's already being broken by the event)
        tree.logs.remove(block);

        if (tree.logs.isEmpty() && tree.leaves.isEmpty()) {
            return;
        }

        // Determine speed from level
        final int ticks_per_block = level < TICKS_PER_LEVEL.length ? TICKS_PER_LEVEL[level] : TICKS_PER_LEVEL[TICKS_PER_LEVEL.length - 1];

        // Start progressive felling — increment active count
        active_felling_count.put(player_id, current_count + 1);

        final var all_blocks = new ArrayList<Block>();
        all_blocks.addAll(tree.logs);
        // Sort leaves by Y so they break top-down (natural look)
        tree.leaves.sort(Comparator.comparingInt(b -> b.getY()));
        all_blocks.addAll(tree.leaves);

        // Register all blocks as being felled to prevent recursive triggers
        blocks_being_felled.addAll(all_blocks);

        schedule_task_timer(new Runnable() {
            int index = 0;

            @Override
            public void run() {
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

                if (index >= all_blocks.size()) {
                    finish();
                    return;
                }

                final var target = all_blocks.get(index);
                index++;

                // Skip if block was already broken (by another plugin, decay, etc.)
                if (target.getType() == Material.AIR) {
                    return;
                }

                final boolean is_log = Tag.LOGS.isTagged(target.getType()) && !is_stripped(target.getType());

                // Fire a BlockBreakEvent for protection plugin compatibility
                final var break_event = new BlockBreakEvent(target, player);
                Bukkit.getPluginManager().callEvent(break_event);
                if (break_event.isCancelled()) {
                    return;
                }

                // Break the block with the tool so enchantments like silk touch are applied
                final boolean has_silk_touch = current_item.containsEnchantment(Enchantment.SILK_TOUCH);
                if (is_log) {
                    target.breakNaturally(current_item);
                    damage_item(player, current_item, 1);
                } else {
                    // Leaf block
                    if (has_silk_touch) {
                        target.breakNaturally(current_item);
                    } else {
                        // Break naturally without tool — drops saplings, sticks, apples as if decayed
                        target.breakNaturally();
                    }
                    // Leaves don't cost durability (consistent with natural leaf decay)
                }
            }

            private void finish() {
                // Clean up felling tracking
                blocks_being_felled.removeAll(all_blocks);
                final int count = active_felling_count.getOrDefault(player_id, 1);
                if (count <= 1) {
                    active_felling_count.remove(player_id);
                } else {
                    active_felling_count.put(player_id, count - 1);
                }
            }
        }, 1L, ticks_per_block);
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
     * Natural tree detection:
     * 1. All connected logs must be the same wood family
     * 2. Trunk must connect downward to solid ground
     * 3. Must have non-persistent leaves of the matching type adjacent to logs
     * 4. No stripped logs connected
     * 5. Maximum 256 logs
     *
     * Only logs at or above the origin's Y level are included in the result
     * for felling. Logs below are used only for tree validation.
     */
    private TreeResult collect_tree(Block origin) {
        final var origin_type = origin.getType();
        final var wood_family = get_wood_family(origin_type);
        final int origin_y = origin.getY();

        // BFS to find ALL connected logs of the same wood family (full tree for validation)
        final var visited = new HashSet<Block>();
        final var queue = new ArrayDeque<Block>();
        final var all_logs = new ArrayList<Block>();

        queue.add(origin);
        visited.add(origin);

        boolean has_ground_connection = false;

        while (!queue.isEmpty()) {
            if (all_logs.size() > MAX_LOG_COUNT) {
                return null;
            }

            final var current = queue.poll();
            all_logs.add(current);

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
        }

        // Must connect to ground
        if (!has_ground_connection) {
            return null;
        }

        // Filter: only logs at or above the break point are felled
        final var logs = new ArrayList<Block>();
        for (final var log : all_logs) {
            if (log.getY() >= origin_y) {
                logs.add(log);
            }
        }

        // Collect only leaves that belong to THIS tree and are attached to
        // logs at or above the break point.
        final var log_set = new HashSet<>(logs);
        final var leaf_visited = new HashSet<Block>();
        final var leaves = new ArrayList<Block>();

        for (final var log : logs) {
            collect_own_leaves(log, wood_family, log_set, leaf_visited, leaves);
        }

        // Must have at least some leaves to be considered a natural tree
        // (check against the full tree's logs, not just the upper portion)
        if (leaves.isEmpty()) {
            // Re-check with all logs in case the leaves are only on the lower portion
            final var full_log_set = new HashSet<>(all_logs);
            final var full_leaf_visited = new HashSet<Block>();
            final var full_leaves = new ArrayList<Block>();
            for (final var log : all_logs) {
                collect_own_leaves(log, wood_family, full_log_set, full_leaf_visited, full_leaves);
            }
            if (full_leaves.isEmpty()) {
                return null;
            }
        }

        return new TreeResult(logs, leaves);
    }

    /**
     * Collects non-persistent leaf blocks that belong to THIS tree only.
     *
     * Only follows leaves whose distance is increasing (away from the log).
     * Stops if a leaf's neighbor at a lower distance is adjacent to a log
     * that doesn't belong to this tree — that leaf belongs to the other tree.
     */
    private void collect_own_leaves(Block log, String wood_family, Set<Block> our_logs,
                                     Set<Block> visited, List<Block> leaves) {
        final var queue = new ArrayDeque<Block>();
        final var cardinal = new BlockFace[] {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
        };

        // Seed: direct leaf neighbors of this log
        for (final var face : cardinal) {
            final var neighbor = log.getRelative(face);
            if (visited.contains(neighbor) || !is_leaf_material(neighbor.getType())) {
                continue;
            }
            if (!leaf_matches_wood(neighbor.getType(), wood_family)) {
                continue;
            }
            final var data = neighbor.getBlockData();
            if (data instanceof Leaves leaf_data && !leaf_data.isPersistent() && leaf_data.getDistance() == 1) {
                visited.add(neighbor);
                leaves.add(neighbor);
                queue.add(neighbor);
            }
        }

        // BFS through leaves, only following increasing distance and checking ownership
        while (!queue.isEmpty()) {
            final var current = queue.poll();
            final var current_data = (Leaves) current.getBlockData();
            final int current_dist = current_data.getDistance();

            for (final var face : cardinal) {
                final var neighbor = current.getRelative(face);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (!is_leaf_material(neighbor.getType())) {
                    continue;
                }
                if (!leaf_matches_wood(neighbor.getType(), wood_family)) {
                    continue;
                }

                final var data = neighbor.getBlockData();
                if (!(data instanceof Leaves leaf_data) || leaf_data.isPersistent()) {
                    continue;
                }

                final int neighbor_dist = leaf_data.getDistance();

                // Only follow leaves at the same or greater distance (moving away from trunk)
                if (neighbor_dist < current_dist) {
                    continue;
                }

                // If this leaf is at distance 1, it must be adjacent to one of OUR logs
                if (neighbor_dist == 1) {
                    if (!is_adjacent_to_our_log(neighbor, our_logs, cardinal)) {
                        continue;
                    }
                }

                // Max distance 7 — beyond that, leaves would decay anyway
                if (neighbor_dist > 7) {
                    continue;
                }

                visited.add(neighbor);
                leaves.add(neighbor);
                queue.add(neighbor);
            }
        }
    }

    /**
     * Checks if a leaf block is cardinally adjacent to one of our tree's log blocks.
     */
    private static boolean is_adjacent_to_our_log(Block leaf, Set<Block> our_logs, BlockFace[] cardinal) {
        for (final var face : cardinal) {
            final var adj = leaf.getRelative(face);
            if (our_logs.contains(adj)) {
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
