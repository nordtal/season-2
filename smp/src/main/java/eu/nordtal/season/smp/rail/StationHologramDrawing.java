package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.hud.PillHologram;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/** Draws a station's name as a grey pill above its rail, never saved with the world. */
final class StationHologramDrawing implements StationHolograms.Drawing<Optional<PillHologram>> {

    /** How far above the station rail's block the name floats. */
    static final double HEIGHT = 1.7;

    /** Half the vanilla range: a name reads as a player comes near, and far stations do not crowd the view. */
    private static final float VIEW_RANGE = 0.5f;

    @Override
    public Optional<PillHologram> draw(final StationRow station, final String name) {
        final @Nullable World world = Bukkit.getWorld(station.world());
        if (world == null) {
            return Optional.empty();
        }
        final Location at = new Location(world, station.x() + 0.5, station.y() + HEIGHT, station.z() + 0.5);
        // The short view range keeps a town's stations from crowding.
        return Optional.of(PillHologram.spawn(at, pill(name), display -> display.setViewRange(VIEW_RANGE)));
    }

    @Override
    public void retitle(final Optional<PillHologram> hologram, final String name) {
        hologram.ifPresent(pill -> pill.text(pill(name)));
    }

    /** The station's name on a grey pill after the rail symbol. */
    static PillHologram.Layers pill(final String name) {
        return PillHologram.layers(Symbol.RAIL, name);
    }

    @Override
    public boolean drawn(final Optional<PillHologram> hologram) {
        return hologram.map(PillHologram::isValid).orElse(false);
    }

    @Override
    public void remove(final Optional<PillHologram> hologram) {
        hologram.ifPresent(PillHologram::remove);
    }
}
