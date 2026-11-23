package eu.nordtal.season.database.inbox;

import java.util.List;
import java.util.Objects;

/**
 * One shape of an area as the area editor holds it: a polygon of corners in order, whose last corner joins the first.
 *
 * @param bottom the lowest block the shape holds, for an area with heights, else 0
 * @param top the highest block the shape holds, for an area with heights, else 0
 */
public record AreaShape(String name, List<Corner> corners, int bottom, int top) {

    public AreaShape {
        Objects.requireNonNull(name, "name");
        corners = List.copyOf(corners);
    }

    /** A corner on the map, in whole blocks. */
    public record Corner(int x, int z) {}
}
