package eu.nordtal.season.smp.world;

import eu.nordtal.season.smp.config.BorderSpec;
import eu.nordtal.season.smp.config.WorldsSpec;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;

/**
 * Finds, creates and holds the borders of the four worlds of the SMP, and loads the extra worlds beside them.
 *
 * A created world is stored under {@code <level-name>/dimensions/}. Use {@link World#getWorldFolder()} for its folder.
 */
public final class Worlds {

    private final Map<WorldRole, String> names = new EnumMap<>(WorldRole.class);
    private final List<String> extras;
    private final BorderSpec config;
    private boolean customBorder;

    public boolean customBorder(final boolean active) {
        final boolean changed = active != customBorder;
        customBorder = active;
        return changed;
    }

    public Worlds(final WorldsSpec worlds, final BorderSpec border) {
        this.config = border;
        names.put(WorldRole.NORDTAL, worlds.worldNordtal());
        names.put(WorldRole.NETHER, worlds.worldNether());
        names.put(WorldRole.END, worlds.worldEnd());
        this.extras = List.copyOf(worlds.extraWorlds());
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
     * Loads or creates every world but Nordtal, which holds the spawn and is never created.
     * An extra world takes Nordtal's seed and generator, and its portals act as Nordtal's.
     *
     * @param log takes a line for each extra world
     * @return the Nordtal world, or empty when it does not exist
     */
    public Optional<World> bootstrap(final Logger log) {
        final World nordtal = Bukkit.getWorld(names.get(WorldRole.NORDTAL));
        if (nordtal == null) {
            return Optional.empty();
        }

        ensure(WorldRole.NETHER, World.Environment.NETHER);
        ensure(WorldRole.END, World.Environment.THE_END);
        extras.forEach(name -> loadExtra(name, nordtal, log));
        return Optional.of(nordtal);
    }

    /** A name Paper refuses only loses its own world, so a typo in a test world never stops the SMP. */
    private static void loadExtra(final String name, final World nordtal, final Logger log) {
        if (Bukkit.getWorld(name) != null) {
            return;
        }
        try {
            final World world = Bukkit.createWorld(new WorldCreator(name).copy(nordtal));
            if (world == null) {
                log.warning("extra world not loaded: world=" + name);
                return;
            }
            log.info("extra world loaded: world=" + name + " key=" + world.getKey());
        } catch (final IllegalArgumentException | IllegalStateException e) {
            log.warning("extra world not loaded: world=" + name + " cause=" + e.getMessage());
        }
    }

    private void ensure(final WorldRole role, final World.Environment environment) {
        final String name = names.get(role);
        if (Bukkit.getWorld(name) != null) {
            return;
        }
        Bukkit.createWorld(new WorldCreator(name).environment(environment));
    }

    /**
     * Sizes and centres every vanilla border. Main thread only.
     * Nordtal's is the fixed outer limit while the custom border holds it, and the start border before that.
     */
    public void applyFixedBorders() {
        world(WorldRole.NORDTAL).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            if (applyOuterLimit(border, customBorder)) {
                return;
            }
            border.setCenter(config.borderCentre().x(), config.borderCentre().z());
            border.setSize(config.startBorderDiameter());
        });
        centreAndSize(WorldRole.NETHER, config.netherBorderDiameter());
        centreAndSize(WorldRole.END, config.endBorderDiameter());
    }

    static boolean applyOuterLimit(final WorldBorder border, final boolean active) {
        if (active) {
            border.setCenter(5120, 5120);
            border.setSize(10240);
        }
        return active;
    }

    private void centreAndSize(final WorldRole role, final int diameter) {
        world(role).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(0, 0);
            border.setSize(diameter);
        });
    }
}
