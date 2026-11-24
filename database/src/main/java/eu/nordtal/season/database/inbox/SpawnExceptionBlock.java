package eu.nordtal.season.database.inbox;

import java.util.Objects;

/**
 * One door or trapdoor in an admin's spawn exception capture draft.
 * A two-block door is normalized to its lower half.
 */
public record SpawnExceptionBlock(String world, int x, int y, int z, Kind kind, Facing facing, boolean defaultOpen) {

    public SpawnExceptionBlock {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(facing, "facing");
    }

    /** Whether the captured block is a door or a trapdoor. */
    public enum Kind {
        DOOR,
        TRAPDOOR
    }

    /** The cardinal facing stored on the door or trapdoor block data. */
    public enum Facing {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }
}
