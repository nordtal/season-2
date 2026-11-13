package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** Writes the station rails and networks in the order they changed, one write after the other, off the main thread. */
public final class RailStore {

    /** What the store holds for one world. */
    public record Stored(List<StationRow> stations, Set<RailPos> junctions, Map<UUID, RailEdge> edges) {}

    /** Everything the store holds. {@code hidden} are the stations hidden from the map. */
    public record Loaded(
            Map<String, Stored> worlds, List<FavouriteRow> favourites, Set<UUID> unseen, Set<UUID> hidden) {}

    private final Jdbi jdbi;
    private final Executor background;
    private final Logger log;
    private CompletableFuture<Void> last = CompletableFuture.completedFuture(null);

    public RailStore(final Jdbi jdbi, final Executor background, final Logger log) {
        this.jdbi = jdbi;
        this.background = background;
        this.log = log;
    }

    /** Reads everything off the main thread and hands it to {@code done} through {@code main}. */
    public void load(final Consumer<Loaded> done, final Executor main) {
        last = last.thenRunAsync(
                        () -> {
                            final Map<String, Stored> worlds = read();
                            final RailDao dao = jdbi.onDemand(RailDao.class);
                            final Loaded loaded = new Loaded(
                                    worlds,
                                    dao.favourites(),
                                    new HashSet<>(dao.unseenRenames()),
                                    new HashSet<>(dao.hiddenStations()));
                            main.execute(() -> done.accept(loaded));
                        },
                        background)
                .exceptionally(this::logged);
    }

    private Map<String, Stored> read() {
        final RailDao dao = jdbi.onDemand(RailDao.class);
        final Map<String, List<StationRow>> stations = new HashMap<>();
        dao.stations()
                .forEach(row -> stations.computeIfAbsent(row.world(), _ -> new ArrayList<>())
                        .add(row));
        final Map<String, Set<RailPos>> junctions = new HashMap<>();
        dao.junctions()
                .forEach(row -> junctions
                        .computeIfAbsent(row.world(), _ -> new TreeSet<>())
                        .add(row.pos()));
        final Map<UUID, List<RailPos>> rails = new HashMap<>();
        dao.edgeRails()
                .forEach(row -> rails.computeIfAbsent(row.edge(), _ -> new ArrayList<>())
                        .add(new RailPos(row.x(), row.y(), row.z())));
        final Map<String, Map<UUID, RailEdge>> edges = new HashMap<>();
        for (final EdgeRow row : dao.edges()) {
            edges.computeIfAbsent(row.world(), _ -> new LinkedHashMap<>())
                    .put(
                            row.id(),
                            new RailEdge(
                                    new RailPos(row.fromX(), row.fromY(), row.fromZ()),
                                    new RailPos(row.toX(), row.toY(), row.toZ()),
                                    rails.getOrDefault(row.id(), List.of()),
                                    row.length()));
        }
        final Set<String> worlds = new TreeSet<>(stations.keySet());
        worlds.addAll(edges.keySet());
        worlds.addAll(junctions.keySet());
        final Map<String, Stored> stored = new HashMap<>();
        for (final String world : worlds) {
            stored.put(
                    world,
                    new Stored(
                            stations.getOrDefault(world, List.of()),
                            junctions.getOrDefault(world, Set.of()),
                            edges.getOrDefault(world, Map.of())));
        }
        return stored;
    }

    /** Stores a placed station, in place of any older one the store still holds at its position. */
    public void placed(final StationRow station) {
        last = last.thenRunAsync(
                        () -> jdbi.useTransaction(handle -> {
                            final RailDao dao = handle.attach(RailDao.class);
                            dao.deleteStationAt(station.world(), station.x(), station.y(), station.z());
                            dao.insertStation(
                                    station.id(),
                                    station.world(),
                                    station.x(),
                                    station.y(),
                                    station.z(),
                                    station.axis(),
                                    station.placedBy());
                        }),
                        background)
                .exceptionally(this::logged);
    }

    /** Stores the name a player gave {@code station}. */
    public void named(final StationRow station) {
        write(dao -> dao.renameStation(
                station.id(), Objects.requireNonNull(station.name()), Objects.requireNonNull(station.named()), false));
    }

    /** Notes that the placer of {@code station} was told of its new name. */
    public void seen(final UUID station) {
        write(dao -> dao.seen(station));
    }

    /** Stores whether {@code station} is hidden from the map. */
    public void hidden(final UUID station, final boolean hidden) {
        write(dao -> dao.hide(station, hidden));
    }

    public void favoured(final UUID player, final UUID station, final boolean favourite) {
        write(dao -> {
            if (favourite) {
                dao.insertFavourite(player, station);
            } else {
                dao.deleteFavourite(player, station);
            }
        });
    }

    public void removed(final UUID station) {
        write(dao -> dao.deleteStation(station));
    }

    /** Stores what a re-check changed in {@code world}, in one transaction. */
    public void changed(final String world, final WorldNetworks.Change change) {
        if (change.empty()) {
            return;
        }
        last = last.thenRunAsync(
                        () -> jdbi.useTransaction(handle -> {
                            final RailDao dao = handle.attach(RailDao.class);
                            change.lost().forEach(station -> dao.deleteStation(station.id()));
                            if (!change.removed().isEmpty()) {
                                dao.deleteEdges(change.removed().stream()
                                        .map(RailLeg::id)
                                        .toList());
                            }
                            batch(change.crossings(), (x, y, z) -> dao.deleteJunctions(world, x, y, z));
                            batch(change.junctions(), (x, y, z) -> dao.insertJunctions(world, x, y, z));
                            change.added().forEach(leg -> insert(dao, world, leg));
                            change.renamed()
                                    .forEach(station -> dao.renameStation(
                                            station.id(),
                                            Objects.requireNonNull(station.name()),
                                            Objects.requireNonNull(station.named()),
                                            true));
                        }),
                        background)
                .exceptionally(this::logged);
    }

    private static void insert(final RailDao dao, final String world, final RailLeg leg) {
        final RailEdge edge = Objects.requireNonNull(leg.edge());
        dao.insertEdge(
                leg.id(),
                world,
                edge.from().x(),
                edge.from().y(),
                edge.from().z(),
                edge.to().x(),
                edge.to().y(),
                edge.to().z(),
                edge.length());
        final List<Integer> ordinals = new ArrayList<>();
        for (int ordinal = 0; ordinal < edge.rails().size(); ordinal++) {
            ordinals.add(ordinal);
        }
        batch(edge.rails(), (x, y, z) -> dao.insertEdgeRails(leg.id(), ordinals, x, y, z));
    }

    private interface Columns {
        void accept(List<Integer> x, List<Integer> y, List<Integer> z);
    }

    private static void batch(final Collection<RailPos> positions, final Columns columns) {
        if (positions.isEmpty()) {
            return;
        }
        columns.accept(
                positions.stream().map(RailPos::x).toList(),
                positions.stream().map(RailPos::y).toList(),
                positions.stream().map(RailPos::z).toList());
    }

    private void write(final Consumer<RailDao> work) {
        last = last.thenRunAsync(() -> work.accept(jdbi.onDemand(RailDao.class)), background)
                .exceptionally(this::logged);
    }

    private @Nullable Void logged(final Throwable failure) {
        log.log(Level.WARNING, "rail networks not written", failure);
        return null;
    }
}
