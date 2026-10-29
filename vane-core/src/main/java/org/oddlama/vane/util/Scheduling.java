package org.oddlama.vane.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Region-aware scheduling on top of Paper's threaded-region schedulers.
 *
 * <p>These schedulers exist on Paper/Purpur and on Folia (and its forks), so there is one code
 * path for both. On Paper every region scheduler and the global region scheduler run on the main
 * thread, so the behaviour is the same as the old {@code BukkitScheduler} calls. On Folia:
 *
 * <ul>
 *   <li>global tasks run on the global region thread (world-agnostic state, world time, spawn),
 *   <li>location tasks run on the thread that owns that block's region,
 *   <li>entity tasks follow the entity across regions and are dropped when it is removed,
 *   <li>async tasks run off every tick thread.
 * </ul>
 *
 * <p>The region schedulers reject delays below one tick, so a delay of zero or less means "next
 * tick", which is what {@code BukkitScheduler#runTaskLater} did with such delays.
 */
public final class Scheduling {

    private Scheduling() {}

    // --- Global region ----------------------------------------------------------------------

    public static ScheduledTask global(@NotNull Plugin plugin, @NotNull Runnable task, long delay_ticks) {
        final var scheduler = Bukkit.getGlobalRegionScheduler();
        if (delay_ticks <= 0) {
            return scheduler.run(plugin, t -> task.run());
        }
        return scheduler.runDelayed(plugin, t -> task.run(), delay_ticks);
    }

    public static ScheduledTask global_timer(
        @NotNull Plugin plugin,
        @NotNull Consumer<ScheduledTask> task,
        long delay_ticks,
        long period_ticks
    ) {
        return Bukkit.getGlobalRegionScheduler()
            .runAtFixedRate(plugin, task, Math.max(1, delay_ticks), Math.max(1, period_ticks));
    }

    /** Runs the task now if this thread is the global region thread, otherwise on its next tick. */
    public static void run_global(@NotNull Plugin plugin, @NotNull Runnable task) {
        if (Bukkit.isGlobalTickThread()) {
            task.run();
        } else {
            global(plugin, task, 0);
        }
    }

    // --- Location (region) ------------------------------------------------------------------

    public static ScheduledTask at(
        @NotNull Plugin plugin,
        @NotNull Location location,
        @NotNull Runnable task,
        long delay_ticks
    ) {
        final var scheduler = Bukkit.getRegionScheduler();
        if (delay_ticks <= 0) {
            return scheduler.run(plugin, location, t -> task.run());
        }
        return scheduler.runDelayed(plugin, location, t -> task.run(), delay_ticks);
    }

    public static ScheduledTask at_timer(
        @NotNull Plugin plugin,
        @NotNull Location location,
        @NotNull Consumer<ScheduledTask> task,
        long delay_ticks,
        long period_ticks
    ) {
        return Bukkit.getRegionScheduler()
            .runAtFixedRate(plugin, location, task, Math.max(1, delay_ticks), Math.max(1, period_ticks));
    }

    /**
     * Runs the task now if this thread owns the location's region, otherwise on the owning region
     * on its next tick. On Paper the main thread owns everything, so this always runs inline there.
     */
    public static void run_at(@NotNull Plugin plugin, @NotNull Location location, @NotNull Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            task.run();
        } else {
            at(plugin, location, task, 0);
        }
    }

    // --- Entity ----------------------------------------------------------------------------

    /**
     * Schedules a task on the entity's scheduler. Returns null (and never runs the task) if the
     * entity has already been removed. {@code retired} runs instead if the entity is removed
     * before the task gets to run; it may be null.
     */
    public static @Nullable ScheduledTask entity(
        @NotNull Plugin plugin,
        @NotNull Entity entity,
        @NotNull Runnable task,
        @Nullable Runnable retired,
        long delay_ticks
    ) {
        final var scheduler = entity.getScheduler();
        if (delay_ticks <= 0) {
            return scheduler.run(plugin, t -> task.run(), retired);
        }
        return scheduler.runDelayed(plugin, t -> task.run(), retired, delay_ticks);
    }

    public static @Nullable ScheduledTask entity_timer(
        @NotNull Plugin plugin,
        @NotNull Entity entity,
        @NotNull Consumer<ScheduledTask> task,
        @Nullable Runnable retired,
        long delay_ticks,
        long period_ticks
    ) {
        return entity
            .getScheduler()
            .runAtFixedRate(plugin, task, retired, Math.max(1, delay_ticks), Math.max(1, period_ticks));
    }

    /**
     * Runs the task now if this thread owns the entity, otherwise on the entity's scheduler on its
     * next tick (dropped if the entity is removed first). On Paper this always runs inline.
     */
    public static void run_for(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            entity(plugin, entity, task, null, 0);
        }
    }

    // --- Async -----------------------------------------------------------------------------

    public static ScheduledTask async(@NotNull Plugin plugin, @NotNull Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public static ScheduledTask async_timer(
        @NotNull Plugin plugin,
        @NotNull Runnable task,
        long delay_ticks,
        long period_ticks
    ) {
        return Bukkit.getAsyncScheduler()
            .runAtFixedRate(
                plugin,
                t -> task.run(),
                Math.max(0, delay_ticks) * 50L,
                Math.max(1, period_ticks) * 50L,
                TimeUnit.MILLISECONDS
            );
    }
}
