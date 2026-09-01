package eu.nordtal.s2.smp.world;

import eu.nordtal.s2.smp.config.SmpSpec;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The SMP's four worlds: finding them, creating the ones that are missing, and holding their
 * borders.
 *
 * <p>Nordtal is expected to exist already - it is the {@code level-name} world, it carries the
 * built spawn, and it is pre-generated once to its final border of 4000 before the phase opens. The
 * other three are created here if the server has never seen them.
 *
 * <p><b>Where a created world lands is not where the old Bukkit layout put it.</b> Measured on
 * Paper 26.2 build 121 on 2026-09-01: a world created through {@code WorldCreator} appears at
 * {@code <level-name>/dimensions/minecraft/<name>}, inside the primary world rather than beside it.
 * Nothing here hard-codes a path because of that - the farm world's swap works off
 * {@link World#getWorldFolder()} and its parent, so the layout can move again without this breaking
 * quietly.
 */
public final class Worlds {

    private final Map<WorldRole, String> names = new EnumMap<>(WorldRole.class);
    private final SmpSpec config;

    public Worlds(final SmpSpec config) {
        this.config = config;
        names.put(WorldRole.NORDTAL, config.worldNordtal());
        names.put(WorldRole.FARM, config.worldFarm());
        names.put(WorldRole.NETHER, config.worldNether());
        names.put(WorldRole.END, config.worldEnd());
    }

    public String nameOf(final WorldRole role) {
        return names.get(role);
    }

    /** The loaded world for a role, if it is loaded at all. The farm world briefly is not. */
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
     * Loads or creates the three worlds that are not Nordtal.
     *
     * <p>Nordtal is deliberately not created here. If it is absent, something is wrong with the
     * deployment - the spawn is built into it - and inventing an empty replacement would hide that
     * behind a world nobody recognises.
     *
     * @return the Nordtal world, or empty when it does not exist
     */
    public Optional<World> bootstrap() {
        final World nordtal = Bukkit.getWorld(names.get(WorldRole.NORDTAL));
        if (nordtal == null) {
            return Optional.empty();
        }

        ensure(WorldRole.FARM, World.Environment.NORMAL);
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

    /**
     * Puts every fixed border in place and centres Nordtal's.
     *
     * <p>Nordtal's <em>size</em> is not set here: it comes from the milestone track and moves when
     * a milestone unlocks, which is {@link #expandNordtal} below. Everything else is a constant
     * from {@code config.yml}.
     *
     * <p>The three secondary worlds are centred on 0/0, which is where their pre-generation is
     * centred and where the balloon lands - Nordtal is the only world whose centre is a built place
     * and therefore the only one that needs a configured one.
     */
    public void applyFixedBorders() {
        world(WorldRole.NORDTAL).ifPresent(world -> {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(config.borderCentreX(), config.borderCentreZ());
        });
        centreAndSize(WorldRole.FARM, config.farmWorldBorderDiameter());
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
     * <p>Minecraft does the interpolation itself, which is why there is no tick loop here: given a
     * duration it moves the wall at a steady speed and every client renders it. The speed comes
     * from {@code border-expansion-blocks-per-second}, deliberately about a quarter to a half of
     * walking pace - the final expansion's 1 550 blocks then take somewhere between a quarter of an
     * hour and half an hour to travel, which is meant to be a ceremony rather than a hiccup.
     *
     * @param diameter the new diameter
     * @param animate  false when putting the border back after a restart, true on a real unlock
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
            // Seconds for the WALL to travel, so half the diameter change - a border grows from
            // both sides at once and using the whole delta would run it at double the intended
            // speed.
            final double travel = (diameter - current) / 2.0;
            final long seconds = Math.max(1L,
                    Math.round(travel / Math.max(0.0001, config.borderExpansionBlocksPerSecond())));
            border.setSize(diameter, seconds);
        });
    }
}
