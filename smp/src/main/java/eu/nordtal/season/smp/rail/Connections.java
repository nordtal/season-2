package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Finds the rails that a rail is connected to.
 * Two rails are connected when the shape of at least one of them leads to the other, slopes included.
 */
public final class Connections {

    private static final int[] RISES = {-1, 0, 1};

    private Connections() {}

    /** Whether the rail at {@code from} with {@code shape} leads to the rail at {@code to}, which has {@code other}. */
    static boolean leads(final RailPos from, final RailShape shape, final RailPos to, final RailShape other) {
        final int dy = to.y() - from.y();
        if (Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z()) != 1 || Math.abs(dy) > 1) {
            return false;
        }
        final Heading heading = Heading.between(from, to);
        if (!shape.leadsTowards(heading)) {
            return false;
        }
        if (shape.risesTowards(heading)) {
            return dy == 1;
        }
        // A flat end meets a rail beside it, or one below it that rises back towards it.
        return dy == 0 || (dy == -1 && other.risesTowards(heading.opposite()));
    }

    /**
     * Returns the readable rails connected to the rail at {@code pos}, which has {@code shape}.
     *
     * @param unreadable receives each position next to {@code pos} that could hold a rail but cannot be read now,
     *     unless the view gave up on it
     */
    public static List<RailPos> of(
            final RailPos pos, final RailShape shape, final RailView view, final List<RailPos> unreadable) {
        final List<RailPos> connected = new ArrayList<>(3);
        for (final Heading heading : Heading.values()) {
            for (final int dy : RISES) {
                final RailPos next = pos.step(heading, dy);
                if (!view.readable(next)) {
                    if (!view.givenUp(next)) {
                        unreadable.add(next);
                    }
                    continue;
                }
                final @Nullable RailShape other = view.shape(next);
                if (other != null && (leads(pos, shape, next, other) || leads(next, other, pos, shape))) {
                    connected.add(next);
                }
            }
        }
        return connected;
    }
}
