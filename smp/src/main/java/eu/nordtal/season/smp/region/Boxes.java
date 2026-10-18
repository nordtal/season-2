package eu.nordtal.season.smp.region;

import java.util.List;
import java.util.Optional;

/**
 * An ordered list of {@link Box} entries.
 *
 * The first box in configuration order that holds a position wins, so a box inside a larger box goes first.
 */
public final class Boxes {

    private final List<Box> boxes;

    public Boxes(final List<Box> boxes) {
        this.boxes = List.copyOf(boxes);
    }

    public Optional<Box> at(final String world, final int x, final int y, final int z) {
        for (final Box box : boxes) {
            if (box.contains(world, x, y, z)) {
                return Optional.of(box);
            }
        }
        return Optional.empty();
    }

    public boolean contains(final String world, final int x, final int y, final int z) {
        return at(world, x, y, z).isPresent();
    }

    public List<Box> all() {
        return boxes;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }
}
