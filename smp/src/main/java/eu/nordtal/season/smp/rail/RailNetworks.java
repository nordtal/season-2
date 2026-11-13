package eu.nordtal.season.smp.rail;

import eu.nordtal.season.papercommon.hud.PillHologram;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Tracks the rail networks of every world and keeps the store in step.
 * A changed block re-checks only the track near it, a little every tick; only a restart reads every network again.
 * Only rails joined to a station rail are ever read.
 */
public final class RailNetworks {

    /** The most rails a re-check reads in one tick. */
    static final int RAILS_PER_TICK = 2_000;

    /** How long a re-check may take of one tick, in nanoseconds. */
    static final long NANOS_PER_TICK = 2_000_000;

    private record Running(String world, RailRecheck job, BukkitRails view) {}

    private final Plugin plugin;
    private final ChunkTickets tickets;
    private final RailStore store;
    private final Clock clock;
    private final StationMarks marks;
    private final StationRailItem item;
    private final NamespacedKey displayKey;
    private final StationHolograms<Optional<PillHologram>> holograms =
            new StationHolograms<>(new StationHologramDrawing());
    private final Map<String, WorldNetworks> worlds = new HashMap<>();
    private final Map<String, Set<RailPos>> changed = new LinkedHashMap<>();
    private final Map<UUID, StationRow> crooked = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> favourites = new HashMap<>();
    private final Set<UUID> unseen = new HashSet<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<String, ChunkRetries> retries = new HashMap<>();
    private final Set<String> whole = new LinkedHashSet<>();
    private Consumer<StationRow> renamedByMerge = _ -> {};
    private @Nullable Running running;
    private boolean restored;

    /** The network under a cart: its graph, where on it the cart is, and every station of it. */
    record Ride(RailGraph graph, RailPos at, List<StationRow> stations) {}

    public RailNetworks(final Plugin plugin, final ChunkTickets tickets, final RailStore store, final Clock clock) {
        this.plugin = plugin;
        this.tickets = tickets;
        this.store = store;
        this.clock = clock;
        this.marks = new StationMarks(plugin);
        this.item = new StationRailItem(plugin);
        this.displayKey = new NamespacedKey(plugin, "station_rail");
    }

    StationRailItem item() {
        return item;
    }

    /**
     * Adds the recipe and reads the stored networks, building them off the main thread.
     * Changes wait until they are back; then every network is read again once, as the store keeps no spur.
     */
    public void start(final Executor main) {
        Bukkit.addRecipe(item.recipe());
        store.load(
                loaded -> {
                    final Map<String, WorldNetworks> built = new HashMap<>();
                    loaded.worlds()
                            .forEach((world, part) -> built.put(
                                    world, WorldNetworks.restored(part.stations(), part.junctions(), part.edges())));
                    main.execute(() -> restore(loaded, built));
                },
                Runnable::run);
    }

    private void restore(final RailStore.Loaded loaded, final Map<String, WorldNetworks> built) {
        loaded.favourites()
                .forEach(row -> favourites
                        .computeIfAbsent(row.player(), _ -> new HashSet<>())
                        .add(row.station()));
        unseen.addAll(loaded.unseen());
        hidden.addAll(loaded.hidden());
        built.forEach((world, networks) -> {
            final @Nullable WorldNetworks before = worlds.put(world, networks);
            if (before != null) {
                networks.takeOver(before);
            }
        });
        whole.addAll(worlds.keySet());
        restored = true;
        worlds.forEach((world, networks) -> reconcileHolograms(world));
        plugin.getLogger()
                .info("station rails restored: count="
                        + worlds.values().stream()
                                .mapToInt(w -> w.stations().size())
                                .sum());
    }

    /** Hands {@code networks} to {@code world} as if the store held them, and reads them again on the next ticks. */
    void install(final String world, final WorldNetworks networks) {
        worlds.put(world, networks);
        whole.add(world);
        restored = true;
    }

    public void stop() {
        final Running job = running;
        if (job != null) {
            job.view().release();
            running = null;
        }
        holograms.clear();
        Bukkit.removeRecipe(item.recipeKey());
    }

    boolean restored() {
        return restored;
    }

    private WorldNetworks networks(final String world) {
        return worlds.computeIfAbsent(world, _ -> new WorldNetworks());
    }

    static RailPos pos(final Block block) {
        return new RailPos(block.getX(), block.getY(), block.getZ());
    }

    /** Whether {@code block} is a station rail, by its marker, which holds before the store answers. */
    boolean isStation(final Block block) {
        return marks.marked(block);
    }

    /** Returns the station at {@code block}, once the store answered. */
    Optional<StationRow> station(final Block block) {
        return networks(block.getWorld().getName()).station(pos(block));
    }

    /** Notes that the rail at {@code block} may have changed; a re-check reads the track near it soon. */
    void changed(final Block block) {
        final String world = block.getWorld().getName();
        changed.computeIfAbsent(world, _ -> new LinkedHashSet<>()).add(pos(block));
        // Vanilla turns the rails next to a changed one after the event, so the stations are straightened a tick on.
        networks(world).stationsNear(pos(block)).forEach(station -> crooked.put(station.id(), station));
    }

    /** Re-checks the rails a re-check gave up on in {@code chunk}, now that it loaded. */
    void loaded(final Chunk chunk) {
        final String world = chunk.getWorld().getName();
        final @Nullable ChunkRetries waiting = retries.get(world);
        final Set<RailPos> rails = waiting == null ? Set.of() : waiting.loaded(chunk.getChunkKey());
        if (!rails.isEmpty()) {
            changed.computeIfAbsent(world, _ -> new LinkedHashSet<>()).addAll(rails);
        }
        if (restored) {
            marks.positions(chunk).forEach(at -> networks(world).station(at).ifPresent(holograms::show));
        }
    }

    /** Removes the holograms of {@code chunk}, which unloads. */
    void unloaded(final Chunk chunk) {
        holograms.unloaded(chunk.getWorld().getName(), chunk.getChunkKey());
    }

    /**
     * Makes {@code block}, a placed rail, a station rail of {@code placer}, straight along {@code axis}.
     * Without a placer it is the spawn station.
     */
    void place(final Block block, final @Nullable UUID placer, final RailShape axis) {
        straighten(block, axis);
        final StationRow station = new StationRow(
                UUID.randomUUID(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                axis.name(),
                placer,
                null,
                null);
        marks.mark(block);
        networks(station.world()).station(station.pos()).ifPresent(old -> {
            forget(old.id());
            holograms.hide(old.id());
        });
        networks(station.world()).addStation(station);
        erase(block);
        draw(block, axis);
        store.placed(station);
        changed(block);
    }

    /** Removes the station rail at {@code block}: its marker, its model and its row. */
    void remove(final Block block) {
        marks.unmark(block);
        erase(block);
        networks(block.getWorld().getName()).removeStation(pos(block)).ifPresent(row -> {
            forget(row.id());
            holograms.hide(row.id());
            store.removed(row.id());
        });
        changed(block);
    }

    /** Removes {@code station}, whose world is not loaded, without touching its block. */
    void removeUnloaded(final StationRow station) {
        networks(station.world()).removeStation(station.pos()).ifPresent(row -> {
            forget(row.id());
            holograms.hide(row.id());
            store.removed(row.id());
        });
    }

    /** Drops what the plugin holds of a removed station; the store deletes its favourites with its row. */
    private void forget(final UUID station) {
        favourites.values().forEach(stations -> stations.remove(station));
        unseen.remove(station);
        hidden.remove(station);
    }

    /** Runs {@code told} on the main thread for each station a merge gave a new name. */
    void whenRenamedByMerge(final Consumer<StationRow> told) {
        this.renamedByMerge = told;
    }

    /** Returns every station of the network {@code station} is on, itself included. */
    List<StationRow> network(final StationRow station) {
        final WorldNetworks networks = networks(station.world());
        return networks.networkOf(station.pos())
                .map(network -> network.graph().stations().stream()
                        .map(networks::station)
                        .flatMap(Optional::stream)
                        .toList())
                .orElse(List.of(station));
    }

    /**
     * Names {@code station} as {@code typed} when the rules allow it; empty when the station is gone.
     * The spawn station counts as each of {@code spawnNames}.
     */
    Optional<StationNames.Verdict> rename(
            final StationRow station, final String typed, final Collection<String> spawnNames) {
        final WorldNetworks networks = networks(station.world());
        final Optional<StationRow> current =
                networks.station(station.pos()).filter(row -> row.id().equals(station.id()));
        if (current.isEmpty()) {
            return Optional.empty();
        }
        final StationNames.Verdict verdict =
                StationNames.judgeTyped(typed, station.id(), network(current.get()), spawnNames);
        if (verdict == StationNames.Verdict.ACCEPTED) {
            final StationRow named = current.get().renamed(typed.strip(), clock.instant());
            networks.addStation(named);
            unseen.remove(named.id());
            if (chunkLoaded(named)) {
                holograms.show(named);
            }
            store.named(named);
        }
        return Optional.of(verdict);
    }

    /** Returns the network under a cart on {@code rail}, when the rail is on its track between two stops. */
    Optional<Ride> ride(final Block rail) {
        final WorldNetworks networks = networks(rail.getWorld().getName());
        final RailPos at = pos(rail);
        return networks.networkOf(at)
                .filter(network -> network.graph().onTrack(at))
                .map(network -> new Ride(
                        network.graph(),
                        at,
                        network.graph().stations().stream()
                                .map(networks::station)
                                .flatMap(Optional::stream)
                                .toList()));
    }

    /** Returns the graph of the network {@code rail} is on in {@code world}, when it is on its track. */
    Optional<RailGraph> graph(final String world, final RailPos rail) {
        return networks(world).networkOf(rail).map(WorldNetworks.Network::graph).filter(graph -> graph.onTrack(rail));
    }

    /** Returns the station with {@code id} in {@code world}, while it exists. */
    Optional<StationRow> station(final String world, final UUID id) {
        return networks(world).station(id);
    }

    Set<UUID> favourites(final UUID player) {
        return Set.copyOf(favourites.getOrDefault(player, Set.of()));
    }

    /** Marks or unmarks {@code station} as a favourite of {@code player}, and returns whether it is one now. */
    boolean toggleFavourite(final UUID player, final UUID station) {
        final Set<UUID> own = favourites.computeIfAbsent(player, _ -> new HashSet<>());
        final boolean favourite = !own.contains(station);
        if (favourite) {
            own.add(station);
        } else {
            own.remove(station);
        }
        store.favoured(player, station, favourite);
        return favourite;
    }

    /** Returns the stations of {@code placer} whose new name from a merge they were not told yet. */
    List<StationRow> unseenOf(final UUID placer) {
        return worlds.values().stream()
                .flatMap(networks -> networks.stations().stream())
                .filter(row -> unseen.contains(row.id()) && placer.equals(row.placedBy()))
                .toList();
    }

    /** Returns the spawn stations: one, or more for the moment a setting moved it, or none. */
    List<StationRow> spawns() {
        return worlds.values().stream()
                .flatMap(networks -> networks.stations().stream())
                .filter(StationRow::spawn)
                .toList();
    }

    /** Names the spawn station's hologram with what {@code name} returns at the time it is shown. */
    void whenSpawnNamed(final Supplier<String> name) {
        holograms.spawnNamed(name);
    }

    /** Returns the name {@code station} goes by in a message, empty for an unnamed one. */
    String nameOf(final StationRow station) {
        return Objects.requireNonNullElse(holograms.nameOf(station), "");
    }

    /** Whether {@code station} is hidden from the map. It still shows in the ride menu. */
    boolean hidden(final UUID station) {
        return hidden.contains(station);
    }

    /** Hides {@code station} from the map or shows it there again, and returns whether it is hidden now. */
    boolean toggleHidden(final UUID station) {
        final boolean hide = hidden.add(station);
        if (!hide) {
            hidden.remove(station);
        }
        store.hidden(station, hide);
        return hide;
    }

    /** Notes that the placer of {@code station} was told of its new name. */
    void seen(final UUID station) {
        if (unseen.remove(station)) {
            store.seen(station);
        }
    }

    /** Brings the holograms of {@code world} level with its stations, after they were restored or read again. */
    private void reconcileHolograms(final String world) {
        holograms.reconcile(world, networks(world).stations(), RailNetworks::chunkLoaded);
    }

    private static boolean chunkLoaded(final StationRow station) {
        final @Nullable World world = Bukkit.getWorld(station.world());
        return world != null && world.isChunkLoaded(station.x() >> 4, station.z() >> 4);
    }

    private static void straighten(final Block block, final RailShape axis) {
        if (block.getBlockData() instanceof Rail rail && !rail.getShape().name().equals(axis.name())) {
            rail.setShape(Rail.Shape.valueOf(axis.name()));
            block.setBlockData(rail, false);
        }
    }

    private void draw(final Block block, final RailShape axis) {
        final Location at = block.getLocation().add(0.5, 0.5, 0.5);
        at.setYaw(axis == RailShape.EAST_WEST ? 90f : 0f);
        block.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(item.create());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
        });
    }

    private void erase(final Block block) {
        block
                .getWorld()
                .getNearbyEntitiesByType(ItemDisplay.class, block.getLocation().add(0.5, 0.5, 0.5), 0.4)
                .stream()
                .filter(display -> display.getPersistentDataContainer().has(displayKey, PersistentDataType.BOOLEAN))
                .forEach(ItemDisplay::remove);
    }

    /** Runs every tick: starts the next re-check, or reads on in the running one. */
    public void tick() {
        straightenCrooked();
        if (!restored) {
            return;
        }
        final long deadline = System.nanoTime() + NANOS_PER_TICK;
        if (running == null) {
            begin();
        }
        final Running job = running;
        if (job != null) {
            job.view().load(job.job().trace().takeWanted());
            if (job.job().step(RAILS_PER_TICK, deadline)) {
                finish(job);
            }
        }
        for (final WorldNetworks networks : worlds.values()) {
            networks.tidy(RAILS_PER_TICK);
        }
    }

    private void straightenCrooked() {
        for (final StationRow station : crooked.values()) {
            final @Nullable World world = Bukkit.getWorld(station.world());
            if (world != null
                    && world.isChunkLoaded(station.x() >> 4, station.z() >> 4)
                    && isStation(world.getBlockAt(station.x(), station.y(), station.z()))) {
                straighten(world.getBlockAt(station.x(), station.y(), station.z()), RailShape.valueOf(station.axis()));
            }
        }
        crooked.clear();
    }

    /** Starts the next re-check: every network of a restored world first, then the changes, a world at a time. */
    private void begin() {
        final Iterator<String> restoring = whole.iterator();
        while (restoring.hasNext()) {
            final String name = restoring.next();
            restoring.remove();
            final @Nullable World world = Bukkit.getWorld(name);
            if (world != null) {
                final BukkitRails view = new BukkitRails(plugin, tickets, world);
                running = new Running(name, networks(name).retrace(view, clock), view);
                return;
            }
        }
        final Iterator<Map.Entry<String, Set<RailPos>>> pending =
                changed.entrySet().iterator();
        while (pending.hasNext()) {
            final Map.Entry<String, Set<RailPos>> next = pending.next();
            pending.remove();
            final @Nullable World world = Bukkit.getWorld(next.getKey());
            if (world == null) {
                continue;
            }
            final BukkitRails view = new BukkitRails(plugin, tickets, world);
            final @Nullable RailRecheck job = networks(next.getKey()).recheck(next.getValue(), view, clock);
            if (job != null) {
                running = new Running(next.getKey(), job, view);
                return;
            }
        }
    }

    private void finish(final Running job) {
        running = null;
        job.view().release();
        final WorldNetworks.Change change = job.job().change();
        final @Nullable World world = Bukkit.getWorld(job.world());
        for (final StationRow lost : change.lost()) {
            if (world != null) {
                final Block block = world.getBlockAt(lost.x(), lost.y(), lost.z());
                marks.unmark(block);
                erase(block);
            }
            forget(lost.id());
            holograms.hide(lost.id());
        }
        store.changed(job.world(), change);
        if (job.job().whole()) {
            reconcileHolograms(job.world());
        } else {
            change.renamed().stream().filter(RailNetworks::chunkLoaded).forEach(holograms::show);
        }
        final Set<RailPos> again = retries.computeIfAbsent(job.world(), _ -> new ChunkRetries())
                .gaveUp(job.job().trace().givenUp(), job.view()::loaded);
        if (!again.isEmpty()) {
            changed.computeIfAbsent(job.world(), _ -> new LinkedHashSet<>()).addAll(again);
        }
        change.renamed().forEach(row -> {
            unseen.add(row.id());
            renamedByMerge.accept(row);
        });
    }
}
