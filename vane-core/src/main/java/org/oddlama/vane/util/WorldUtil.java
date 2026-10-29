package org.oddlama.vane.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public class WorldUtil {

    private static final HashMap<UUID, ScheduledTask> running_time_change_tasks = new HashMap<>();

    /**
     * Smoothly moves the world time to the given time of day. World time is global state, so the
     * interpolation runs on the global region scheduler (the main thread on Paper).
     */
    public static boolean change_time_smoothly(
        final World world,
        final Plugin plugin,
        final long world_ticks,
        final long interpolation_ticks
    ) {
        synchronized (running_time_change_tasks) {
            if (running_time_change_tasks.containsKey(world.getUID())) {
                return false;
            }

            // Calculate relative time from and to
            var rel_to = world_ticks;
            var rel_from = world.getTime();
            if (rel_to <= rel_from) {
                rel_to += 24000;
            }

            // Calculate absolute values
            final var delta_ticks = rel_to - rel_from;
            final var absolute_from = world.getFullTime();
            final var absolute_to = absolute_from - rel_from + rel_to;

            // Task to advance time every tick
            ScheduledTask task = Scheduling.global_timer(
                plugin,
                new Consumer<ScheduledTask>() {
                    private long elapsed = 0;

                    @Override
                    public void accept(ScheduledTask self) {
                        // Remove a task if we finished interpolation
                        if (elapsed > interpolation_ticks) {
                            synchronized (running_time_change_tasks) {
                                running_time_change_tasks.remove(world.getUID());
                            }
                            self.cancel();
                        }

                        // Make the transition smooth by applying a cosine
                        var lin_delta = (float) elapsed / interpolation_ticks;
                        var delta = (1f - (float) Math.cos(Math.PI * lin_delta)) / 2f;

                        var cur_ticks = absolute_from + (long) (delta_ticks * delta);
                        world.setFullTime(cur_ticks);
                        ++elapsed;
                    }
                },
                1,
                1
            );

            running_time_change_tasks.put(world.getUID(), task);
        }

        return true;
    }
}
