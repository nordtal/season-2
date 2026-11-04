package eu.nordtal.season.smp.rail;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one hologram over every named station whose chunk is loaded, and none over any other.
 * The spawn station is named by the translations, so its hologram shows the name it has there.
 * The holograms themselves are drawn by a {@link Drawing}, so the rules run without a server.
 */
final class StationHolograms<H> {

    /** Draws, retitles and removes the holograms. */
    interface Drawing<H> {

        H draw(StationRow station, String name);

        void retitle(H hologram, String name);

        void remove(H hologram);

        /** Whether {@code hologram} is still in the world, and not removed by something else. */
        boolean drawn(H hologram);
    }

    private record Shown<H>(StationRow station, String name, H hologram) {}

    private final Drawing<H> drawing;
    private final Map<UUID, Shown<H>> shown = new HashMap<>();
    private Supplier<String> spawnName = () -> "Spawn";

    StationHolograms(final Drawing<H> drawing) {
        this.drawing = drawing;
    }

    /** Returns the name {@code station} shows: the spawn station's from the translations, or its own, or none. */
    @Nullable
    String nameOf(final StationRow station) {
        return station.spawn() ? spawnName.get() : station.name();
    }

    /** Names the spawn station's hologram with what {@code name} returns at the time it is shown. */
    void spawnNamed(final Supplier<String> name) {
        this.spawnName = name;
    }

    /** Shows {@code station} as it is now, in a loaded chunk: its name when it has one, and nothing otherwise. */
    void show(final StationRow station) {
        final @Nullable String name = nameOf(station);
        if (name == null) {
            hide(station.id());
            return;
        }
        final Shown<H> current = shown.get(station.id());
        if (current != null && samePlace(current.station(), station) && drawing.drawn(current.hologram())) {
            if (!current.name().equals(name)) {
                drawing.retitle(current.hologram(), name);
            }
            shown.put(station.id(), new Shown<>(station, name, current.hologram()));
            return;
        }
        hide(station.id());
        shown.put(station.id(), new Shown<>(station, name, drawing.draw(station, name)));
    }

    private static boolean samePlace(final StationRow one, final StationRow other) {
        return one.world().equals(other.world()) && one.pos().equals(other.pos());
    }

    /** Removes the hologram of the station {@code id}, if it has one. */
    void hide(final UUID id) {
        final Shown<H> gone = shown.remove(id);
        if (gone != null) {
            drawing.remove(gone.hologram());
        }
    }

    /** Removes the holograms of the chunk {@code chunkKey} of {@code world}, which unloads. */
    void unloaded(final String world, final long chunkKey) {
        List.copyOf(shown.values()).stream()
                .filter(entry -> entry.station().world().equals(world) && chunkKey(entry.station()) == chunkKey)
                .forEach(entry -> hide(entry.station().id()));
    }

    /** Brings the holograms of {@code world} level with all of its {@code stations}, of which only the loaded show. */
    void reconcile(final String world, final Collection<StationRow> stations, final Predicate<StationRow> loaded) {
        final List<StationRow> showing = stations.stream().filter(loaded).toList();
        final Set<UUID> kept = showing.stream().map(StationRow::id).collect(Collectors.toSet());
        List.copyOf(shown.values()).stream()
                .filter(entry -> entry.station().world().equals(world)
                        && !kept.contains(entry.station().id()))
                .forEach(entry -> hide(entry.station().id()));
        showing.forEach(this::show);
    }

    /** Removes every hologram, as the plugin stops. */
    void clear() {
        List.copyOf(shown.keySet()).forEach(this::hide);
    }

    /** Returns the stations that show a hologram now. */
    Set<UUID> shown() {
        return Set.copyOf(shown.keySet());
    }

    /** Returns the key Paper gives the chunk {@code station} stands in. */
    static long chunkKey(final StationRow station) {
        return chunkKey(station.x() >> 4, station.z() >> 4);
    }

    /** Returns the key Paper gives the chunk at {@code chunkX}, {@code chunkZ}, as {@code Chunk.getChunkKey}. */
    static long chunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xffffffffL) | ((chunkZ & 0xffffffffL) << 32);
    }
}
