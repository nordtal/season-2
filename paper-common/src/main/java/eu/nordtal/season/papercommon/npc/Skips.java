package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.NpcStore;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The NPCs each online player skips, read at join and kept here, so a right-click asks no database.
 * Reads and writes run off the main thread, one player's writes in order; a skip not read yet counts as none.
 */
public final class Skips implements Listener {

    private final Plugin plugin;
    private final NpcStore store;
    private final Map<UUID, Map<UUID, String>> known = new ConcurrentHashMap<>();
    // The NPCs whose skip a hand-off changed since the join, which the read at join must leave as they are.
    private final Map<UUID, Set<UUID>> changed = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writes = new ConcurrentHashMap<>();

    public Skips(final Plugin plugin, final NpcStore store) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Reads the skips of every player online already, as after a reload. */
    public void start() {
        Bukkit.getOnlinePlayers().forEach(player -> load(player.getUniqueId()));
    }

    /** Returns the menu {@code player} skips to at {@code npc}, or null. */
    public @Nullable String of(final UUID player, final UUID npc) {
        final Map<UUID, String> skips = known.get(player);
        return skips == null ? null : skips.get(npc);
    }

    /** Writes what a hand-off to {@code menu} changed. */
    public void apply(final UUID player, final UUID npc, final String menu, final SkipChoice.Change change) {
        final Map<UUID, String> skips = known.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
        if (change != SkipChoice.Change.NONE) {
            changed.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet())
                    .add(npc);
        }
        switch (change) {
            case NONE -> {}
            case SKIP -> {
                skips.put(npc, menu);
                write("skip", player, npc, () -> store.skip(npc, player, menu));
            }
            case UNSKIP -> {
                skips.remove(npc);
                write("unskip", player, npc, () -> store.unskip(npc, player));
            }
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        load(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        known.remove(event.getPlayer().getUniqueId());
        changed.remove(event.getPlayer().getUniqueId());
        writes.computeIfPresent(event.getPlayer().getUniqueId(), (key, last) -> last.isDone() ? null : last);
    }

    private void load(final UUID player) {
        PaperScheduler.of(plugin).execute(() -> {
            try {
                final Map<UUID, String> read = new ConcurrentHashMap<>(store.skips(player));
                final Player online = Bukkit.getPlayer(player);
                if (online != null && online.isOnline()) {
                    final Set<UUID> touched = changed.getOrDefault(player, Set.of());
                    known.merge(player, read, (now, earlier) -> {
                        earlier.forEach((npc, menu) -> {
                            if (!touched.contains(npc)) {
                                now.put(npc, menu);
                            }
                        });
                        return now;
                    });
                }
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .warning("npc skips not read, dialogues play: player=" + player + " cause="
                                + failure.getMessage());
            }
        });
    }

    // Chains the write after the player's last one, so a skip and a quick unskip land in that order.
    private void write(final String what, final UUID player, final UUID npc, final Runnable step) {
        final Runnable guarded = () -> {
            try {
                step.run();
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .warning("npc " + what + " not saved: player=" + player + " npc=" + npc + " cause="
                                + failure.getMessage());
            }
        };
        writes.compute(
                player,
                (key, last) -> (last == null ? CompletableFuture.<Void>completedFuture(null) : last)
                        .thenRunAsync(guarded, PaperScheduler.of(plugin)));
    }
}
