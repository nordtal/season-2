package eu.nordtal.season.smp.wall;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The panels one player's client holds: each one's entity, height and look, and the heights they cover. */
final class Viewer {

    private final String world;
    private final Map<Panel, Shown> shown = new HashMap<>();
    private double base = Double.NaN;
    private Band.@Nullable Range range;

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

    /** The heights the field's panels cover, or null before the first. */
    Band.@Nullable Range range() {
        return range;
    }

    void reshape(final Band.Range range) {
        this.range = range;
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
