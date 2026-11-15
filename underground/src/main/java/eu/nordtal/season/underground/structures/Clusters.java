package eu.nordtal.season.underground.structures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups positions that lie within a distance of each other, through any chain of them. */
public final class Clusters {

    private Clusters() {}

    /** A position with what stands there. */
    public record At<T>(int x, int y, int z, T thing) {}

    /** Returns the groups, each in the order of the input, in the order of their first member. */
    public static <T> List<List<At<T>>> of(final List<At<T>> items, final int distance) {
        final int[] parent = new int[items.size()];
        final Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            parent[i] = i;
            final At<T> item = items.get(i);
            grid.computeIfAbsent(
                            Finds.key(Math.floorDiv(item.x(), distance), Math.floorDiv(item.z(), distance)),
                            key -> new ArrayList<>())
                    .add(i);
        }
        final long square = (long) distance * distance;
        for (int i = 0; i < items.size(); i++) {
            final At<T> item = items.get(i);
            final int cellX = Math.floorDiv(item.x(), distance);
            final int cellZ = Math.floorDiv(item.z(), distance);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (final int j : grid.getOrDefault(Finds.key(cellX + dx, cellZ + dz), List.of())) {
                        final At<T> other = items.get(j);
                        final long x = (long) other.x() - item.x();
                        final long z = (long) other.z() - item.z();
                        if (j > i && x * x + z * z <= square) {
                            parent[root(parent, i)] = root(parent, j);
                        }
                    }
                }
            }
        }
        final Map<Integer, List<At<T>>> groups = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(items.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    /** Returns the box around the members of a group. */
    public static Box box(final List<? extends At<?>> group) {
        Box box = point(group.getFirst());
        for (final At<?> item : group) {
            box = box.union(point(item));
        }
        return box;
    }

    private static Box point(final At<?> item) {
        return new Box(item.x(), item.y(), item.z(), item.x(), item.y(), item.z());
    }

    private static int root(final int[] parent, final int i) {
        int at = i;
        while (parent[at] != at) {
            parent[at] = parent[parent[at]];
            at = parent[at];
        }
        return at;
    }
}
