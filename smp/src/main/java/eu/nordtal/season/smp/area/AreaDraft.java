package eu.nordtal.season.smp.area;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.inbox.AreaShape.Corner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The private draft of one admin's area editor: the shapes of one world as the admin changes them.
 * Nothing outside the editor sees the draft until Steward saves it.
 */
final class AreaDraft {

    /** How many blocks a new polygon reaches to each side of the player. */
    static final int NEW_HALF = 8;

    /** How far below and above the player a new polygon with heights reaches. */
    static final int NEW_BELOW = 4;

    static final int NEW_ABOVE = 12;

    private final String world;
    private final boolean heights;
    private final List<Shape> shapes = new ArrayList<>();

    AreaDraft(final String world, final boolean heights, final List<AreaShape> shapes) {
        this.world = Objects.requireNonNull(world, "world");
        this.heights = heights;
        for (final AreaShape shape : shapes) {
            this.shapes.add(new Shape(shape.name(), shape.corners(), shape.bottom(), shape.top()));
        }
    }

    private static final class Shape {
        private final String name;
        private final List<Corner> corners;
        private int bottom;
        private int top;

        private Shape(final String name, final List<Corner> corners, final int bottom, final int top) {
            this.name = name;
            this.corners = new ArrayList<>(corners);
            this.bottom = bottom;
            this.top = top;
        }
    }

    String world() {
        return world;
    }

    boolean heights() {
        return heights;
    }

    int size() {
        return shapes.size();
    }

    /** Returns the shapes as they are now, in the order Steward sent and added them. */
    List<AreaShape> shapes() {
        return shapes.stream()
                .map(shape -> new AreaShape(shape.name, shape.corners, shape.bottom, shape.top))
                .toList();
    }

    AreaShape shape(final int shape) {
        return shapes().get(shape);
    }

    /** Moves corner {@code corner} of shape {@code shape} to {@code x}, {@code z}. */
    void move(final int shape, final int corner, final int x, final int z) {
        shapes.get(shape).corners.set(corner, new Corner(x, z));
    }

    /** Puts a new corner at {@code x}, {@code z} on the edge that starts at corner {@code edge}; returns its index. */
    int insert(final int shape, final int edge, final int x, final int z) {
        shapes.get(shape).corners.add(edge + 1, new Corner(x, z));
        return edge + 1;
    }

    /** Removes corner {@code corner}; refuses, and returns false, while the shape has only three. */
    boolean remove(final int shape, final int corner) {
        final List<Corner> corners = shapes.get(shape).corners;
        if (corners.size() <= 3) {
            return false;
        }
        corners.remove(corner);
        return true;
    }

    /** Sets the bottom, or with {@code top} the top, of shape {@code shape} to {@code y}. */
    void height(final int shape, final boolean top, final int y) {
        if (top) {
            shapes.get(shape).top = y;
        } else {
            shapes.get(shape).bottom = y;
        }
    }

    /** Adds a square shape named {@code name} around {@code x}, {@code y}, {@code z}. */
    void add(final String name, final int x, final int y, final int z) {
        shapes.add(new Shape(
                name, Polygons.square(x, z, NEW_HALF), heights ? y - NEW_BELOW : 0, heights ? y + NEW_ABOVE : 0));
    }
}
