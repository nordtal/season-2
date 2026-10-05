package eu.nordtal.season.smp.region;

import java.util.List;
import java.util.Optional;

/**
 * An ordered list of {@link Box} entries, asked which one holds a position.
 *
 * The first hit in configuration order wins, so a small box carved out of a large one goes first.
 */
public final class Boxes {

    private final List<Box> boxes;

    public Boxes(final List<Box> boxes) {
        this.boxes = List.copyOf(boxes);
    }

    /** The first box containing the position, if any. */
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

    /** Every box in the given world, in configuration order. */
    public List<Box> in(final String world) {
        return boxes.stream().filter(box -> box.world().equals(world)).toList();
    }

    public List<Box> all() {
        return boxes;
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }
}
