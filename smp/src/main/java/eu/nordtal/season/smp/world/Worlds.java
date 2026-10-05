package eu.nordtal.season.smp.world;

import eu.nordtal.season.smp.config.BalloonSpawnPointsSpec;
import eu.nordtal.season.smp.config.SmpSpec;
import eu.nordtal.season.smp.config.SpawnPointSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

/**
 * The SMP's four worlds: finding them, creating the ones that are missing, and holding their borders.
 *
 * A created world lands under {@code <level-name>/dimensions/}, so ask {@link World#getWorldFolder()} for its folder.
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
        // Every WorldRole is put in the map in the constructor above, so a lookup never misses.
        return Objects.requireNonNull(names.get(role));
    }

    /**
     * Where the balloon puts a player down in that world, a configured point rather than the world spawn.
     *
     * The caller puts it through {@code LandingSite#findSafeAt}.
     */
    public SpawnPointSpec balloonSpawnPoint(final WorldRole role) {
        final BalloonSpawnPointsSpec points = config.balloonSpawnPoints();
        return switch (role) {
            case NORDTAL -> points.nordtal();
            case NETHER -> points.nether();
            case END -> points.end();
        };
    }

    /** The loaded world for a role, if it is loaded at all. */
    public Optional<World> world(final WorldRole role) {
        return Optional.ofNullable(Bukkit.getWorld(names.get(role)));
    }

    /** Which of the four a world is, or empty for anything else on the server. */
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
     * Loads or creates the three worlds that are not Nordtal, which is never created because it carries the spawn.
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

    /** Puts every fixed border in place and centres Nordtal's, whose size {@link #expandNordtal} sets. */
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
     * Sets Nordtal's border, animating the change when it is a growth.
     *
     * @param diameter the new diameter
     * @param animate false when putting the border back after a restart, true on a real unlock
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
            // Seconds for the WALL to travel, so half the diameter change: a border grows from both sides at once.
            final double travel = (diameter - current) / 2.0;
            final long seconds =
                    Math.max(1L, Math.round(travel / Math.max(0.0001, config.borderExpansionBlocksPerSecond())));
            border.changeSize(diameter, seconds);
        });
    }
}
