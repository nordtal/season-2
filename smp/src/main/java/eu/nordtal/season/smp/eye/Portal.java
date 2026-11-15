package eu.nordtal.season.smp.eye;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One End portal of the map: the centre of a group of end portal frames, at the height of its frames.
 * Frames closer than {@link #REACH} blocks across and one block up or down belong to the same portal.
 */
record Portal(double x, double y, double z) {

    /** The widest gap between two neighbouring frames of one portal: the missing corner of the ring. */
    static final int REACH = 2;

    /** Groups the frames into portals, each frame with every frame it touches through its neighbours. */
    static List<Portal> of(final Collection<Frame> frames) {
        final List<Frame> all = frames.stream()
                .sorted(Comparator.comparingInt(Frame::x)
                        .thenComparingInt(Frame::z)
                        .thenComparingInt(Frame::y))
                .toList();
        final int[] parent = new int[all.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size() && all.get(j).x() - all.get(i).x() <= REACH; j++) {
                if (near(all.get(i), all.get(j))) {
                    parent[root(parent, j)] = root(parent, i);
                }
            }
        }
        final List<List<Frame>> groups = new ArrayList<>();
        final int[] group = new int[all.size()];
        Arrays.fill(group, -1);
        for (int i = 0; i < all.size(); i++) {
            final int root = root(parent, i);
            if (group[root] < 0) {
                group[root] = groups.size();
                groups.add(new ArrayList<>());
            }
            groups.get(group[root]).add(all.get(i));
        }
        return groups.stream().map(Portal::centre).toList();
    }

    /** Returns the portal nearest to x and z, counted across the ground as the game counts its strongholds. */
    static Optional<Portal> nearest(final List<Portal> portals, final double x, final double z) {
        return portals.stream().min(Comparator.comparingDouble(portal -> portal.distanceSquared(x, z)));
    }

    double distanceSquared(final double fromX, final double fromZ) {
        final double dx = x - fromX;
        final double dz = z - fromZ;
        return dx * dx + dz * dz;
    }

    private static boolean near(final Frame a, final Frame b) {
        return Math.abs(a.x() - b.x()) <= REACH && Math.abs(a.z() - b.z()) <= REACH && Math.abs(a.y() - b.y()) <= 1;
    }

    private static int root(final int[] parent, final int i) {
        int at = i;
        while (parent[at] != at) {
            parent[at] = parent[parent[at]];
            at = parent[at];
        }
        return at;
    }

    private static Portal centre(final List<Frame> frames) {
        final int minX = frames.stream().mapToInt(Frame::x).min().orElseThrow();
        final int maxX = frames.stream().mapToInt(Frame::x).max().orElseThrow();
        final int minZ = frames.stream().mapToInt(Frame::z).min().orElseThrow();
        final int maxZ = frames.stream().mapToInt(Frame::z).max().orElseThrow();
        final int minY = frames.stream().mapToInt(Frame::y).min().orElseThrow();
        return new Portal((minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0);
    }
}
