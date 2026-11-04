package eu.nordtal.season.smp.rail;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which way a new station rail runs: it joins the track that leads to it.
 * The axis with more neighbouring rails leading to it wins; the placer's facing decides only when no track does.
 */
final class StationAxis {

    private static final int[] RISES = {-1, 0, 1};

    private StationAxis() {}

    /**
     * Returns the axis of a station rail placed at {@code here}.
     *
     * @param shapes the shape of the rail at a position, or null where there is none
     * @param facing the heading the placer faces
     */
    static RailShape of(final RailPos here, final Function<RailPos, @Nullable RailShape> shapes, final Heading facing) {
        int northSouth = 0;
        int eastWest = 0;
        for (final Heading heading : Heading.values()) {
            final RailShape along = axisOf(heading);
            if (leadsHere(here, heading, along, shapes)) {
                if (along == RailShape.NORTH_SOUTH) {
                    northSouth++;
                } else {
                    eastWest++;
                }
            }
        }
        if (northSouth != eastWest) {
            return northSouth > eastWest ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
        }
        return axisOf(facing);
    }

    private static boolean leadsHere(
            final RailPos here,
            final Heading heading,
            final RailShape along,
            final Function<RailPos, @Nullable RailShape> shapes) {
        for (final int dy : RISES) {
            final RailPos next = here.step(heading, dy);
            final @Nullable RailShape other = shapes.apply(next);
            if (other != null && Connections.leads(next, other, here, along)) {
                return true;
            }
        }
        return false;
    }

    private static RailShape axisOf(final Heading heading) {
        return RailShape.NORTH_SOUTH.leadsTowards(heading) ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
    }
}
