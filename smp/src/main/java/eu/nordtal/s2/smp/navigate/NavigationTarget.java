package eu.nordtal.s2.smp.navigate;

import java.util.UUID;

/**
 * Somewhere {@code /navigate} can point at.
 *
 * <p>Three kinds, and the distinction is what decides how the entry is labelled and whether it can
 * be deleted (docs/smp.md#navigate):
 *
 * <ul>
 *   <li>{@link Kind#WORLD_SPAWN} - the current world's spawn, built in and always present</li>
 *   <li>{@link Kind#LAST_DEATH} - built in, and absent until there has been one</li>
 *   <li>{@link Kind#POI} - created by players, public, unlimited</li>
 * </ul>
 *
 * <p><b>There is no navigation to players</b>, and that was considered and dropped rather than
 * forgotten: with PvP enabled everywhere an arrow pointing at a person is a hunting tool, and a
 * consent flow around it is more machinery than the feature is worth.
 *
 * @param id    the POI's id, or null for the two built-in kinds
 * @param label a POI's name, or a message key for the built-in kinds
 */
public record NavigationTarget(Kind kind, UUID id, String label, String world, int x, int y, int z) {

    public enum Kind {
        WORLD_SPAWN,
        LAST_DEATH,
        POI
    }

    public static NavigationTarget worldSpawn(final String world, final int x, final int y, final int z) {
        return new NavigationTarget(Kind.WORLD_SPAWN, null, "smp.navigate.world-spawn", world, x, y, z);
    }

    public static NavigationTarget lastDeath(final String world, final int x, final int y, final int z) {
        return new NavigationTarget(Kind.LAST_DEATH, null, "smp.navigate.last-death", world, x, y, z);
    }

    public static NavigationTarget poi(final UUID id, final String name, final String world,
                                       final int x, final int y, final int z) {
        return new NavigationTarget(Kind.POI, id, name, world, x, y, z);
    }

    /** Whether this target is in the world the player is standing in. */
    public boolean isIn(final String worldName) {
        return world.equals(worldName);
    }
}
