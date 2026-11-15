package eu.nordtal.season.smp.wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The part of the wall a player sees: the panels near them, from below their feet to above their head. */
final class Band {

    /**
     * How far along the wall a player sees it, in blocks.
     *
     * The field's shader fades it out by the same distance, and the pixel look's last three panels fade.
     */
    static final double RADIUS = 30;

    /** How far the band reaches below the feet, in blocks. */
    static final double BELOW = 3;

    /** The height of the band, in blocks. */
    static final float HEIGHT = 8;

    private Band() {}

    /** The model of each panel a player at {@code x}, {@code z} sees. */
    static Map<Panel, String> seen(final Wall wall, final Look look, final double x, final double z) {
        final Map<Panel, String> seen = new HashMap<>();
        for (final Panel panel : wall.near(x, z, RADIUS)) {
            seen.put(panel, look.model(panel.distance(x, z)));
        }
        return seen;
    }

    /** The height the panels stand at for a player whose feet are at {@code feetY}. */
    static double base(final double feetY) {
        return Math.floor(feetY) - BELOW;
    }

    /** What turns the panels {@code shown} into the panels {@code seen}. */
    static Change change(final Map<Panel, String> shown, final Map<Panel, String> seen) {
        final Map<Panel, String> added = new HashMap<>();
        final List<Panel> removed = new ArrayList<>();
        final Map<Panel, String> remodelled = new HashMap<>();
        seen.forEach((panel, model) -> {
            final String before = shown.get(panel);
            if (before == null) {
                added.put(panel, model);
            } else if (!before.equals(model)) {
                remodelled.put(panel, model);
            }
        });
        for (final Panel panel : shown.keySet()) {
            if (!seen.containsKey(panel)) {
                removed.add(panel);
            }
        }
        return new Change(added, removed, remodelled);
    }

    /** The panels to add and to remodel, each with its model, and the panels to remove. */
    record Change(Map<Panel, String> added, List<Panel> removed, Map<Panel, String> remodelled) {}
}
