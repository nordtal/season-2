package eu.nordtal.season.smp.rail;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.jspecify.annotations.Nullable;

/** Draws a station's name as a text display above its rail, never saved with the world. */
final class StationHologramDrawing implements StationHolograms.Drawing<Optional<TextDisplay>> {

    /** How far above the station rail's block the name floats. */
    static final double HEIGHT = 1.7;

    /** Half the vanilla range: a name reads as a player comes near, and far stations do not crowd the view. */
    private static final float VIEW_RANGE = 0.5f;

    @Override
    public Optional<TextDisplay> draw(final StationRow station, final String name) {
        final @Nullable World world = Bukkit.getWorld(station.world());
        if (world == null) {
            return Optional.empty();
        }
        final Location at = new Location(world, station.x() + 0.5, station.y() + HEIGHT, station.z() + 0.5);
        // Unlike a grave's countdown, a name is not seen through blocks: towns and tunnels hold many stations.
        return Optional.of(world.spawn(at, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(false);
            display.setDefaultBackground(true);
            display.setViewRange(VIEW_RANGE);
            display.text(Component.text(name));
        }));
    }

    @Override
    public void retitle(final Optional<TextDisplay> hologram, final String name) {
        hologram.ifPresent(display -> display.text(Component.text(name)));
    }

    @Override
    public boolean drawn(final Optional<TextDisplay> hologram) {
        return hologram.map(TextDisplay::isValid).orElse(false);
    }

    @Override
    public void remove(final Optional<TextDisplay> hologram) {
        hologram.ifPresent(TextDisplay::remove);
    }
}
