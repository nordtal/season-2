package eu.nordtal.season.smp.rail;

import org.jspecify.annotations.Nullable;

/** Reads the rails of one world. */
public interface RailView {

    /** Whether {@code pos} can be read now. A position in a chunk that is not loaded cannot. */
    boolean readable(RailPos pos);

    /** Whether the walk gave up on reading {@code pos}, which it cannot read, so the track counts as ending there. */
    default boolean givenUp(final RailPos pos) {
        return false;
    }

    /** Returns the shape of the rail at {@code pos}, or null when no rail is there. */
    @Nullable
    RailShape shape(RailPos pos);
}
