package eu.nordtal.season.smp.rail;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
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
 * It walks the rails again after a tracked rail changes, a little every tick.
 * Only rails joined to a station rail are ever walked.
 */
public final class RailNetworks {

    /** The most rails one network holds; a walk stops growing a network beyond it. */
    static final int RAILS_PER_NETWORK = 50_000;

    /** The most rails a walk reads in one tick. */
    static final int RAILS_PER_TICK = 2_000;

    private record Running(String world, WorldNetworks.Walk walk, RailWalk rails, BukkitRails view) {}

    private final Plugin plugin;
    private final ChunkTickets tickets;
    private final RailStore store;
    private final Clock clock;
    private final StationMarks marks;
    private final StationRailItem item;
    private final NamespacedKey displayKey;
    private final Map<String, WorldNetworks> worlds = new HashMap<>();
    private final Map<String, Set<RailPos>> changed = new LinkedHashMap<>();
    private final Map<UUID, StationRow> crooked = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> favourites = new HashMap<>();
    private final Set<UUID> unseen = new HashSet<>();
    private final Map<String, ChunkRetries> retries = new HashMap<>();
    private final Map<String, Integer> versions = new HashMap<>();
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

    /** Adds the recipe and reads the stored networks off the main thread. Changes wait until they are back. */
    public void start(final Executor main) {
        Bukkit.addRecipe(item.recipe());
        store.load(
                loaded -> {
                    loaded.favourites()
                            .forEach(row -> favourites
                                    .computeIfAbsent(row.player(), _ -> new HashSet<>())
                                    .add(row.station()));
                    unseen.addAll(loaded.unseen());
                    loaded.worlds().forEach((world, part) -> {
                        networks(world).restore(part.stations(), part.junctions(), part.edges());
                        // The store keeps no track that ends nowhere, so each network is walked again once.
                        part.stations()
                                .forEach(station -> changed.computeIfAbsent(world, _ -> new LinkedHashSet<>())
                                        .add(station.pos()));
                    });
                    restored = true;
                    plugin.getLogger()
                            .info("restored "
                                    + worlds.values().stream()
                                            .mapToInt(w -> w.stations().size())
                                            .sum() + " station rail(s)");
                },
                main);
    }

    public void stop() {
        final Running walk = running;
        if (walk != null) {
            walk.view().release();
            running = null;
        }
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

    /** Notes that the rail at {@code block} may have changed; the next tick walks the networks near it. */
    void changed(final Block block) {
        final String world = block.getWorld().getName();
        changed.computeIfAbsent(world, _ -> new LinkedHashSet<>()).add(pos(block));
        // Vanilla turns the rails next to a changed one after the event, so the stations are straightened a tick on.
        networks(world).stationsNear(pos(block)).forEach(station -> crooked.put(station.id(), station));
    }

    /** Walks again the networks whose walk gave up on {@code chunk}, now that it loaded. */
    void loaded(final Chunk chunk) {
        final String world = chunk.getWorld().getName();
        final @Nullable ChunkRetries waiting = retries.get(world);
        final Set<RailPos> starts = waiting == null ? Set.of() : waiting.loaded(chunk.getChunkKey());
        if (!starts.isEmpty()) {
            changed.computeIfAbsent(world, _ -> new LinkedHashSet<>()).addAll(starts);
        }
    }

    /** Makes {@code block}, a placed rail, a station rail of {@code placer}, straight along {@code axis}. */
    void place(final Block block, final UUID placer, final RailShape axis) {
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
        networks(station.world()).station(station.pos()).ifPresent(old -> forget(old.id()));
        networks(station.world()).addStation(station);
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
            store.removed(row.id());
        });
        changed(block);
    }

    /** Drops what the plugin holds of a removed station; the store deletes its favourites with its row. */
    private void forget(final UUID station) {
        favourites.values().forEach(stations -> stations.remove(station));
        unseen.remove(station);
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

    /** Names {@code station} as {@code typed} when the rules allow it; empty when the station is gone. */
    Optional<StationNames.Verdict> rename(final StationRow station, final String typed) {
        final WorldNetworks networks = networks(station.world());
        final Optional<StationRow> current =
                networks.station(station.pos()).filter(row -> row.id().equals(station.id()));
        if (current.isEmpty()) {
            return Optional.empty();
        }
        final StationNames.Verdict verdict = StationNames.judgeTyped(typed, station.id(), network(current.get()));
        if (verdict == StationNames.Verdict.ACCEPTED) {
            final StationRow named = current.get().renamed(typed.strip(), clock.instant());
            networks.addStation(named);
            unseen.remove(named.id());
            store.named(named);
        }
        return Optional.of(verdict);
    }

    /** Returns the network under a cart on {@code rail}, when the rail is on its track between two stops. */
    Optional<Ride> ride(final Block rail) {
        final WorldNetworks networks = networks(rail.getWorld().getName());
        final RailPos at = pos(rail);
        return networks.networkOf(at)
                .filter(network -> RideList.onTrack(network.graph(), at))
                .map(network -> new Ride(
                        network.graph(),
                        at,
                        network.graph().stations().stream()
                                .map(networks::station)
                                .flatMap(Optional::stream)
                                .toList()));
    }

    /** Returns how often the networks of {@code world} were walked again; a ride plans again when it changes. */
    int version(final String world) {
        return versions.getOrDefault(world, 0);
    }

    /** Returns the graph of the network {@code rail} is on in {@code world}, when it is on its track. */
    Optional<RailGraph> graph(final String world, final RailPos rail) {
        return networks(world)
                .networkOf(rail)
                .map(WorldNetworks.Network::graph)
                .filter(graph -> RideList.onTrack(graph, rail));
    }

    /** Returns the station with {@code id} in {@code world}, while it exists. */
    Optional<StationRow> station(final String world, final UUID id) {
        return networks(world).stations().stream()
                .filter(row -> row.id().equals(id))
                .findFirst();
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
                .filter(row -> unseen.contains(row.id()) && row.placedBy().equals(placer))
                .toList();
    }

    /** Notes that the placer of {@code station} was told of its new name. */
    void seen(final UUID station) {
        if (unseen.remove(station)) {
            store.seen(station);
        }
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

    /** Runs every tick: starts the next walk, or reads on in the running one. */
    public void tick() {
        straightenCrooked();
        if (!restored) {
            return;
        }
        if (running == null) {
            begin();
        }
        final Running walk = running;
        if (walk == null) {
            return;
        }
        walk.view().load(walk.rails().takeWanted());
        if (walk.rails().step(RAILS_PER_TICK)) {
            finish(walk);
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

    private void begin() {
        final Iterator<Map.Entry<String, Set<RailPos>>> pending =
                changed.entrySet().iterator();
        while (pending.hasNext()) {
            final Map.Entry<String, Set<RailPos>> next = pending.next();
            pending.remove();
            final World world = Bukkit.getWorld(next.getKey());
            if (world == null) {
                continue;
            }
            final WorldNetworks networks = networks(next.getKey());
            final WorldNetworks.Walk walk = networks.walkFor(next.getValue());
            if (walk.starts().isEmpty() && walk.replaced().isEmpty()) {
                continue;
            }
            final BukkitRails view = new BukkitRails(plugin, tickets, world);
            running = new Running(next.getKey(), walk, new RailWalk(view, walk.starts(), RAILS_PER_NETWORK), view);
            return;
        }
    }

    private void finish(final Running walk) {
        running = null;
        walk.view().release();
        final WorldNetworks networks = networks(walk.world());
        final WorldNetworks.Replacement replacement =
                networks.replace(walk.walk(), walk.rails().found(), walk.rails().missing(), clock.instant());
        final World world = Bukkit.getWorld(walk.world());
        if (world != null) {
            for (final StationRow lost : replacement.lost()) {
                final Block block = world.getBlockAt(lost.x(), lost.y(), lost.z());
                marks.unmark(block);
                erase(block);
            }
        }
        replacement.lost().forEach(row -> forget(row.id()));
        store.replaced(walk.world(), replacement);
        versions.merge(walk.world(), 1, Integer::sum);
        final Set<RailPos> again = retries.computeIfAbsent(walk.world(), _ -> new ChunkRetries())
                .gaveUp(walk.view().givenUpChunks(), walk.walk().starts(), walk.view()::loaded);
        if (!again.isEmpty()) {
            changed.computeIfAbsent(walk.world(), _ -> new LinkedHashSet<>()).addAll(again);
        }
        replacement.renamed().forEach(row -> {
            unseen.add(row.id());
            renamedByMerge.accept(row);
        });
        if (walk.rails().found().stream().anyMatch(RailWalk.Found::bounded)) {
            plugin.getLogger()
                    .warning("a rail network in " + walk.world() + " holds more than " + RAILS_PER_NETWORK
                            + " rails and was walked only that far");
        }
    }
}
