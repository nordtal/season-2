package eu.nordtal.season.smp.navigate;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messages.MessageRef;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A target of {@code /navigate}: the world spawn, the last death or a public POI.
 *
 * @param id    the POI id, or null for a built-in kind
 * @param label the POI name, or {@code null} for a built-in kind, whose name is {@link #name()}
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

    public MessageRef name() {
        return switch (kind) {
            case WORLD_SPAWN -> MESSAGES.smp().navigate().worldSpawn();
            case LAST_DEATH -> MESSAGES.smp().navigate().lastDeath();
            case POI -> throw new IllegalStateException("a POI is named by its label: " + label);
        };
    }

    public boolean isIn(final String worldName) {
        return world.equals(worldName);
    }
}
