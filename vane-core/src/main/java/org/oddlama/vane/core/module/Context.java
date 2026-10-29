package org.oddlama.vane.core.module;

import java.io.IOException;
import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.oddlama.vane.util.Scheduling;
import org.json.JSONObject;
import org.oddlama.vane.core.functional.Consumer1;
import org.oddlama.vane.core.resourcepack.ResourcePackGenerator;

/**
 * A ModuleContext is an association to a specific Module and also a grouping of config and language
 * variables with a common namespace.
 */
public interface Context<T extends Module<T>> {
    public static String append_yaml_path(String ns1, String ns2, String separator) {
        if (ns1.isEmpty()) {
            return ns2;
        }
        return ns1 + separator + ns2;
    }

    /** create a sub-context namespace */
    public default ModuleContext<T> namespace(String name) {
        return new ModuleContext<T>(this, name, null, ".");
    }

    /** create a sub-context namespace */
    public default ModuleContext<T> namespace(String name, String description) {
        return new ModuleContext<T>(this, name, description, ".");
    }

    /** create a sub-context namespace */
    public default ModuleContext<T> namespace(String name, String description, String separator) {
        return new ModuleContext<T>(this, name, description, separator);
    }

    /** create a sub-context group */
    public default ModuleGroup<T> group(String group, String description) {
        return new ModuleGroup<T>(this, group, description);
    }

    public default ModuleGroup<T> group(String group, String description, boolean default_enabled) {
        final var g = new ModuleGroup<T>(this, group, description);
        g.config_enabled_def = default_enabled;
        return g;
    }

    /** create a sub-context group */
    public default ModuleGroup<T> group_default_disabled(String group, String description) {
        final var g = group(group, description);
        g.config_enabled_def = false;
        return g;
    }

    /**
     * Compile the given component (processes lang and config definitions) and registers it for
     * on_enable, on_disable and on_config_change events.
     */
    public void compile(ModuleComponent<T> component);

    public void add_child(Context<T> subcontext);

    public Context<T> get_context();

    public T get_module();

    public String yaml_path();

    public String variable_yaml_path(String variable);

    public boolean enabled();

    public void enable();

    public void disable();

    public void config_change();

    public void generate_resource_pack(final ResourcePackGenerator pack) throws IOException;

    public void for_each_module_component(final Consumer1<ModuleComponent<?>> f);

    public default void on_enable() {}

    public default void on_disable() {}

    public default void on_config_change() {}

    public default void on_generate_resource_pack(final ResourcePackGenerator pack) throws IOException {}

    // Scheduling. Paper's region schedulers exist on Paper and Folia alike: on Paper all of them
    // run on the main thread, on Folia each runs on the thread that owns its region. See
    // org.oddlama.vane.util.Scheduling.

    /** Repeating task on the global region (world-agnostic state only, no entities or blocks). */
    public default ScheduledTask schedule_task_timer(Runnable task, long delay_ticks, long period_ticks) {
        return Scheduling.global_timer(get_module(), t -> task.run(), delay_ticks, period_ticks);
    }

    /** Delayed task on the global region (world-agnostic state only, no entities or blocks). */
    public default ScheduledTask schedule_task(Runnable task, long delay_ticks) {
        return Scheduling.global(get_module(), task, delay_ticks);
    }

    /** Next-tick task on the global region (world-agnostic state only, no entities or blocks). */
    public default ScheduledTask schedule_next_tick(Runnable task) {
        return Scheduling.global(get_module(), task, 0);
    }

    /** Delayed task on the thread owning the location's region. */
    public default ScheduledTask schedule_at(Location location, Runnable task, long delay_ticks) {
        return Scheduling.at(get_module(), location, task, delay_ticks);
    }

    /** Delayed task on the entity's scheduler; null if the entity is already removed. */
    public default ScheduledTask schedule_for(Entity entity, Runnable task, long delay_ticks) {
        return Scheduling.entity(get_module(), entity, task, null, delay_ticks);
    }

    /** Runs now if this thread owns the entity, otherwise on the entity's next tick. */
    public default void run_for(Entity entity, Runnable task) {
        Scheduling.run_for(get_module(), entity, task);
    }

    /** Runs now if this thread owns the location, otherwise on its region's next tick. */
    public default void run_at(Location location, Runnable task) {
        Scheduling.run_at(get_module(), location, task);
    }

    /** Runs now on the global region thread, otherwise on its next tick. */
    public default void run_global(Runnable task) {
        Scheduling.run_global(get_module(), task);
    }

    public default void add_storage_migration_to(long to, String name, Consumer<JSONObject> migrator) {
        get_module().persistent_storage_manager.add_migration_to(to, name, migrator);
    }

    public default String storage_path_of(String field) {
        if (!field.startsWith("storage_")) {
            throw new RuntimeException("Configuration fields must be prefixed storage_. This is a bug.");
        }
        return variable_yaml_path(field.substring("storage_".length()));
    }

    public default void mark_persistent_storage_dirty() {
        get_module().mark_persistent_storage_dirty();
    }
}
