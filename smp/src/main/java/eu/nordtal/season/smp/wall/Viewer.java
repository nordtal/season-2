package eu.nordtal.season.smp.wall;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The panels one player's client holds: each one's entity, height and look, and how far they are stretched. */
final class Viewer {

    private final String world;
    private final Map<Panel, Shown> shown = new HashMap<>();
    private double base = Double.NaN;
    private boolean skyward;
    private double stretchedFrom;
    private long turnedAt = Long.MIN_VALUE / 2;

    Viewer(final String world) {
        this.world = world;
    }

    String world() {
        return world;
    }

    /** The height the pixel look's panels stand at. */
    double base() {
        return base;
    }

    void lift(final double base) {
        this.base = base;
    }

    /** Whether the field's panels stretch toward the wall up to the build limit, not toward the band on the ground. */
    boolean skyward() {
        return skyward;
    }

    /** Turns the stretch around at {@code now}, in milliseconds, from wherever the client has brought it. */
    void turn(final boolean skyward, final long now) {
        stretchedFrom = stretched(now);
        turnedAt = now;
        this.skyward = skyward;
    }

    /** How far the client has stretched the field's panels at {@code now}: 0 the ground band, 1 the whole wall. */
    double stretched(final long now) {
        final double target = skyward ? 1 : 0;
        return stretchedFrom + (target - stretchedFrom) * (1 - left(now) / (double) Band.STRETCH_TICKS);
    }

    /** The ticks the client still takes at {@code now} to finish the stretch. */
    int left(final long now) {
        return (int) Math.clamp(Band.STRETCH_TICKS - (now - turnedAt) / 50, 0, Band.STRETCH_TICKS);
    }

    Map<Panel, Shown> shown() {
        return shown;
    }

    /** Each panel as the player sees it. */
    Map<Panel, Band.Seen> seen() {
        final Map<Panel, Band.Seen> seen = new HashMap<>();
        shown.forEach((panel, held) -> seen.put(panel, held.seen()));
        return seen;
    }

    void add(final Panel panel, final int id, final double y, final Band.Seen seen) {
        shown.put(panel, new Shown(id, y, seen));
    }

    /** Sets how a panel is seen and returns its entity. */
    int remodel(final Panel panel, final Band.Seen seen) {
        final Shown before = Objects.requireNonNull(shown.get(panel));
        shown.put(panel, new Shown(before.id(), before.y(), seen));
        return before.id();
    }

    /** Drops the panels and returns their entities. */
    int[] forget(final Collection<Panel> panels) {
        final List<Panel> copy = List.copyOf(panels);
        final int[] removed = new int[copy.size()];
        for (int i = 0; i < copy.size(); i++) {
            removed[i] = Objects.requireNonNull(shown.remove(copy.get(i))).id();
        }
        return removed;
    }

    /** A panel in the client: its entity, the height the entity stands at and how it is seen. */
    record Shown(int id, double y, Band.Seen seen) {}
}
