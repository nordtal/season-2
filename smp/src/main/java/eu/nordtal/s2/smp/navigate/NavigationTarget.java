package eu.nordtal.s2.smp.navigate;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messages.MessageRef;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Somewhere {@code /navigate} can point at: the world spawn, the last death, or a public POI, never a player.
 *
 * @param id the POI's id, or null for the two built-in kinds
 * @param label a POI's name, or {@code null} for the built-in kinds, which are named by {@link #name()}
 */
public record NavigationTarget(
        Kind kind, @Nullable UUID id, @Nullable String label, String world, int x, int y, int z) {

    public enum Kind {
        WORLD_SPAWN,
        LAST_DEATH,
        POI
    }

    public static NavigationTarget worldSpawn(final String world, final int x, final int y, final int z) {
        return new NavigationTarget(Kind.WORLD_SPAWN, null, null, world, x, y, z);
    }

    public static NavigationTarget lastDeath(final String world, final int x, final int y, final int z) {
        return new NavigationTarget(Kind.LAST_DEATH, null, null, world, x, y, z);
    }

    public static NavigationTarget poi(
            final UUID id, final String name, final String world, final int x, final int y, final int z) {
        return new NavigationTarget(Kind.POI, id, name, world, x, y, z);
    }

    /** What a destination is called: a POI's own name, or the built-in kind's message. */
    public MessageRef name() {
        return switch (kind) {
            case WORLD_SPAWN -> MESSAGES.smp().navigate().worldSpawn();
            case LAST_DEATH -> MESSAGES.smp().navigate().lastDeath();
            case POI -> throw new IllegalStateException("a POI is named by its label: " + label);
        };
    }

    /** Whether this target is in the world the player is standing in. */
    public boolean isIn(final String worldName) {
        return world.equals(worldName);
    }
}
