package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code smp_rail_station}: a placed station rail, and its name once it has one. */
public record StationRow(
        UUID id,
        String world,
        int x,
        int y,
        int z,
        String axis,
        UUID placedBy,
        @Nullable String name,
        @Nullable Instant named) {

    public RailPos pos() {
        return new RailPos(x, y, z);
    }

    public StationRow renamed(final String newName, final Instant at) {
        return new StationRow(id, world, x, y, z, axis, placedBy, newName, at);
    }
}
