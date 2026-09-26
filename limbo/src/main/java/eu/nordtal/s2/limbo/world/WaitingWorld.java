package eu.nordtal.s2.limbo.world;

import eu.nordtal.s2.limbo.config.LimboSpec;
import java.util.Objects;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The empty world every player waits in, and the one location in it.
 *
 * A Paper server always has the world named by {@code level-name} in {@code server.properties},
 * and that world is whatever the server jar generated the first time it started - terrain, a sky, a
 * day/night cycle and mobs. The waiting room needs the opposite of all of it, and the cheapest way
 * to guarantee that is not to configure the server's world but to build one that has never had
 * anything in it. Players are moved here before they are spawned at all
 * ({@code AsyncPlayerSpawnLocationEvent}), so the server's own world is never seen for a frame.
 *
 * What is switched off below is mostly belt and braces, since the generator produces nothing - but
 * a gamerule left at its default is a thing that starts happening the moment somebody changes the
 * generator, and this is a server whose entire purpose is that nothing happens.
 */
public final class WaitingWorld {

    private final World world;
    private final Location spawn;

    private WaitingWorld(final World world, final Location spawn) {
        this.world = world;
        this.spawn = spawn;
    }

    /**
     * Loads the waiting world, creating it if this server has never had one.
     *
     * @param plugin the plugin, for logging
     * @param config the loaded {@code config.yml}
     * @return the world and its spawn, or {@code null} if the server refused to create it - which
     *         the caller must treat as fatal, because a waiting room with nowhere to wait would
     *         drop every login into the server's own world instead
     */
    public static @Nullable WaitingWorld loadOrCreate(final Plugin plugin, final LimboSpec config) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");

        final World world = new WorldCreator(config.worldName())
                .generator(new VoidChunkGenerator())
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            return null;
        }

        world.setDifficulty(Difficulty.PEACEFUL);
        world.setSpawnLocation(0, config.spawnY(), 0);
        // A client still ticks weather and a thunderstorm is audible even on a screen that is entirely black.
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        // GameRules, not GameRule: the old constants are @Deprecated(forRemoval) since 1.21.11.
        setRule(world, GameRules.ADVANCE_TIME, false);
        setRule(world, GameRules.ADVANCE_WEATHER, false);
        setRule(world, GameRules.SPAWN_MOBS, false);
        // do-fire-tick became a radius in blocks rather than staying a boolean.
        setRule(world, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        setRule(world, GameRules.RANDOM_TICK_SPEED, 0);
        setRule(world, GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        setRule(world, GameRules.SHOW_DEATH_MESSAGES, false);
        setRule(world, GameRules.IMMEDIATE_RESPAWN, true);
        // Nobody should take damage here, but the impossible should not scatter an inventory into the void either.
        setRule(world, GameRules.KEEP_INVENTORY, true);
        setRule(world, GameRules.FALL_DAMAGE, false);
        setRule(world, GameRules.DROWNING_DAMAGE, false);
        setRule(world, GameRules.FIRE_DAMAGE, false);
        setRule(world, GameRules.FREEZE_DAMAGE, false);
        setRule(world, GameRules.RESPAWN_RADIUS, 0);

        // World#setKeepSpawnInMemory is a no-op: generating an empty chunk is generating nothing regardless.
        return new WaitingWorld(world, new Location(world, 0.5, config.spawnY(), 0.5, 0.0f, 0.0f));
    }

    /** @return the world itself */
    public World world() {
        return world;
    }

    /** @return a fresh copy of the one place anybody stands */
    public Location spawn() {
        return spawn.clone();
    }

    /**
     * @param location where a player is
     * @return whether they have wandered far enough from {@link #spawn()} to be put back. Flying in
     *         an empty world harms nothing, but a player who stops flying falls out of it, and a
     *         player who falls forever is a player whose client is downloading a resource pack
     *         while the server streams empty chunks after them
     */
    public boolean hasStrayed(final @Nullable Location location) {
        if (location == null || !world.equals(location.getWorld())) {
            return true;
        }
        return location.getY() < spawn.getY() - STRAY_BELOW
                || location.distanceSquared(spawn) > STRAY_RADIUS * STRAY_RADIUS;
    }

    /** How far from the spawn a player may drift before being put back. */
    private static final double STRAY_RADIUS = 24.0;

    /** How far below the spawn a player may fall before being put back. */
    private static final double STRAY_BELOW = 8.0;

    private static <T> void setRule(final World world, final GameRule<T> rule, final T value) {
        world.setGameRule(rule, value);
    }
}
