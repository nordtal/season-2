package eu.nordtal.season.smp.wall;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The panels one player's client holds: the entity and the model of each, and the height they stand at. */
final class Viewer {

    private final String world;
    private final Map<Panel, Integer> ids = new HashMap<>();
    private final Map<Panel, String> models = new HashMap<>();
    private double base = Double.NaN;

    Viewer(final String world) {
        this.world = world;
    }

    String world() {
        return world;
    }

    double base() {
        return base;
    }

    void lift(final double base) {
        this.base = base;
    }

    Map<Panel, String> models() {
        return models;
    }

    Map<Panel, Integer> ids() {
        return ids;
    }

    void add(final Panel panel, final int id, final String model) {
        ids.put(panel, id);
        models.put(panel, model);
    }

    /** Sets the model of a panel and returns its entity. */
    int remodel(final Panel panel, final String model) {
        models.put(panel, model);
        return Objects.requireNonNull(ids.get(panel));
    }

    /** Drops the panels and returns their entities. */
    int[] forget(final Collection<Panel> panels) {
        final List<Panel> copy = List.copyOf(panels);
        final int[] removed = new int[copy.size()];
        for (int i = 0; i < copy.size(); i++) {
            removed[i] = Objects.requireNonNull(ids.remove(copy.get(i)));
            models.remove(copy.get(i));
        }
        return removed;
    }
}
