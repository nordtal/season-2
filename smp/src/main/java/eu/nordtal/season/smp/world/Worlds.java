package eu.nordtal.season.smp.world;

import eu.nordtal.season.smp.config.SmpSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

/**
 * Finds, creates and holds the borders of the four worlds of the SMP.
 *
 * A created world is stored under {@code <level-name>/dimensions/}. Use {@link World#getWorldFolder()} for its folder.
 */
public final class Worlds {

    private final Map<WorldRole, String> names = new EnumMap<>(WorldRole.class);
    private final SmpSpec config;

    public Worlds(final SmpSpec config) {
        this.config = config;
        names.put(WorldRole.NORDTAL, config.worldNordtal());
        names.put(WorldRole.NETHER, config.worldNether());
        names.put(WorldRole.END, config.worldEnd());
    }

    public String nameOf(final WorldRole role) {
        // The constructor puts every WorldRole into the map, so a lookup never misses.
        return Objects.requireNonNull(names.get(role));
    }

    public Optional<World> world(final WorldRole role) {
        return Optional.ofNullable(Bukkit.getWorld(names.get(role)));
    }

    public Optional<WorldRole> roleOf(final World world) {
        if (world == null) {
            return Optional.empty();
        }
        return roleOf(world.getName());
    }

    public Optional<WorldRole> roleOf(final String worldName) {
        for (final Map.Entry<WorldRole, String> entry : names.entrySet()) {
            if (entry.getValue().equals(worldName)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Loads or creates the three worlds other than Nordtal. It never creates Nordtal, which holds the spawn.
     *
     * @return the Nordtal world, or empty when it does not exist
     */
    public Optional<World> bootstrap() {
        final World nordtal = Bukkit.getWorld(names.get(WorldRole.NORDTAL));
        if (nordtal == null) {
            return Optional.empty();
        }

        ensure(WorldRole.NETHER, World.Environment.NETHER);
        ensure(WorldRole.END, World.Environment.THE_END);
        return Optional.of(nordtal);
    }

    private void ensure(final WorldRole role, final World.Environment environment) {
        final String name = names.get(role);
        if (Bukkit.getWorld(name) != null) {
            return;
        }
        Bukkit.createWorld(new WorldCreator(name).environment(environment));
    }

    public void applyFixedBorders() {
        world(WorldRole.NORDTAL).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(config.borderCentreX(), config.borderCentreZ());
        });
        centreAndSize(WorldRole.NETHER, config.netherBorderDiameter());
        centreAndSize(WorldRole.END, config.endBorderDiameter());
    }

    private void centreAndSize(final WorldRole role, final int diameter) {
        world(role).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(0, 0);
            border.setSize(diameter);
        });
    }

    /**
     * Sets the border of Nordtal and animates it when it grows.
     *
     * @param diameter the new diameter
     * @param animate false when restoring the border after a restart, true on a real unlock
     */
    public void expandNordtal(final int diameter, final boolean animate) {
        world(WorldRole.NORDTAL).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(config.borderCentreX(), config.borderCentreZ());

            final double current = border.getSize();
            if (!animate || diameter <= current) {
                border.setSize(diameter);
                return;
            }
            // The travel time in seconds is half the diameter change, because the border grows on both sides.
            final double travel = (diameter - current) / 2.0;
            final long seconds =
                    Math.max(1L, Math.round(travel / Math.max(0.0001, config.borderExpansionBlocksPerSecond())));
            border.changeSize(diameter, seconds);
        });
    }
}
