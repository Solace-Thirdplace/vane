package org.oddlama.vane.core.resourcepack;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.*;
import java.nio.file.*;
import java.util.Iterator;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.oddlama.vane.util.Scheduling;
import org.jetbrains.annotations.NotNull;

public class ResourcePackFileWatcher {

    private final ResourcePackDistributor resource_pack_distributor;
    private final File file;

    public ResourcePackFileWatcher(ResourcePackDistributor resource_pack_distributor, File file)
        throws IOException, InterruptedException {
        this.resource_pack_distributor = resource_pack_distributor;
        this.file = file;
    }

    public void watch_for_changes() throws IOException {
        var eyes = FileSystems.getDefault().newWatchService();
        var lang_file_match = FileSystems.getDefault().getPathMatcher("glob:**/lang-*.yml");
        register_directories(Paths.get("plugins"), eyes, this::is_vane_module_folder);

        // The watch loop blocks on WatchService#take, so it lives on an async thread.
        Scheduling.async(resource_pack_distributor.get_module(), () ->
            watch(eyes, lang_file_match, this::update_and_send_resource_pack)
        );
    }

    private void update_and_send_resource_pack() {
        resource_pack_distributor.counter++;
        resource_pack_distributor.get_module().generate_resource_pack();
        resource_pack_distributor.update_sha1(file);
        // Sending a resource pack request only queues packets, which is safe from any thread.
        for (Player player : Bukkit.getOnlinePlayers()) {
            resource_pack_distributor.send_resource_pack(player);
        }
    }

    private boolean is_vane_module_folder(Path p) {
        return p.getFileName().toString().startsWith("vane-");
    }

    private void watch(WatchService eyes, PathMatcher match_lang, Runnable on_hit) {
        boolean should_schedule = false;
        ScheduledTask runner = null;
        for (;;) {
            final WatchKey key;
            try {
                key = eyes.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // process events
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) continue;

                // This generic is always Path for WatchEvent kinds other than OVERFLOW
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path dir = (Path) key.watchable();
                Path filename = ev.context();
                if (!match_lang.matches(dir.resolve(filename))) continue;
                should_schedule = true;
            }

            if (should_schedule) {
                // Debounce: drop a pending regeneration that has not started yet.
                // Cancelling a task that already started (or finished) is a no-op.
                if (runner != null) {
                    runner.cancel();
                }
                runner = Scheduling.global(resource_pack_distributor.get_module().core, on_hit, 20L);
                should_schedule = false;
            }

            // reset the key
            boolean valid = key.reset();
            if (!valid) {
                return;
            }
        }
    }

    private void register_directories(Path root, WatchService watcher, Predicate<Path> path_match) throws IOException {
        // register vane sub-folders.
        final Iterator<Path> interesting_paths = Files.walk(root)
            .filter(Files::isDirectory)
            .filter(path_match)
            .iterator();
        // quirky, but checked exceptions inside streams suck.
        while (interesting_paths.hasNext()) {
            Path p = interesting_paths.next();
            p.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        }
    }
}
