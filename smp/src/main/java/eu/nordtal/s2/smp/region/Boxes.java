package eu.nordtal.s2.smp.region;

import java.util.List;
import java.util.Optional;

/**
 * An ordered list of {@link Box}es, asked one question: which one is this position in?
 *
 * <p>Checked in configuration order and the first hit wins, which is what makes a small box carved
 * out of a large one possible by putting it first. Linear, because the number of boxes is a handful
 * and a spatial index for six entries is a way to be slower with more code.
 *
 * <p>Pure, so it is tested without a server.
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
