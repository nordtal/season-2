package eu.nordtal.season.smp.rail;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code smp_rail_station}: a placed station rail, and its name once it has one.
 * The spawn station, which the plugin places at the block the settings name, is the one row without a placer.
 */
public record StationRow(
        UUID id,
        String world,
        int x,
        int y,
        int z,
        String axis,
        @Nullable UUID placedBy,
        @Nullable String name,
        @Nullable Instant named) {

    public RailPos pos() {
        return new RailPos(x, y, z);
    }

    /** Whether this is the spawn station, which has no placer and no name of its own. */
    public boolean spawn() {
        return placedBy == null;
    }

    /**
     * Whether {@code player} may break, name or hide this station: its placer and admins may.
     * Nobody may change the spawn station. {@code admin} says whether the player is an admin.
     */
    public boolean mayChange(final UUID player, final boolean admin) {
        return !spawn() && (admin || player.equals(placedBy));
    }

    public StationRow renamed(final String newName, final Instant at) {
        return new StationRow(id, world, x, y, z, axis, placedBy, newName, at);
    }
}
