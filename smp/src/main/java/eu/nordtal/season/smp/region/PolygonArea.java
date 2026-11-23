package eu.nordtal.season.smp.region;

import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.area.Polygons;
import java.util.List;
import java.util.Objects;

/**
 * A named 3D polygon area in one world, spanning from {@code minY} to {@code maxY} (both inclusive).
 * Corners name blocks and edges run through block centres, with edge centres counted as inside.
 */
public final class PolygonArea {

    private final String name;
    private final String world;
    private final List<Corner> corners;
    private final int minY;
    private final int maxY;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public PolygonArea(
            final String name, final String world, final List<Corner> corners, final int minY, final int maxY) {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("an area needs a world");
        }
        if (corners == null || corners.size() < 3) {
            throw new IllegalArgumentException("the area in '" + world + "' needs at least 3 corners");
        }
        if (maxY < minY) {
            throw new IllegalArgumentException("the area in '" + world + "' has maxY below minY");
        }
        this.name = name == null ? "" : name;
        this.world = world;
        this.corners = List.copyOf(corners);
        this.minY = minY;
        this.maxY = maxY;
        int lowX = Integer.MAX_VALUE;
        int highX = Integer.MIN_VALUE;
        int lowZ = Integer.MAX_VALUE;
        int highZ = Integer.MIN_VALUE;
        for (final Corner corner : this.corners) {
            lowX = Math.min(lowX, corner.x());
            highX = Math.max(highX, corner.x());
            lowZ = Math.min(lowZ, corner.z());
            highZ = Math.max(highZ, corner.z());
        }
        this.minX = lowX;
        this.maxX = highX;
        this.minZ = lowZ;
        this.maxZ = highZ;
    }

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public List<Corner> corners() {
        return corners;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int minX() {
        return minX;
    }

    public int maxX() {
        return maxX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Whether the block at {@code x}, {@code y}, {@code z} lies inside or on the boundary of this area. */
    public boolean containsBlock(final String world, final int x, final int y, final int z) {
        return this.world.equals(world)
                && y >= minY
                && y <= maxY
                && x >= minX
                && x <= maxX
                && z >= minZ
                && z <= maxZ
                && Polygons.containsBlock(corners, x, z);
    }

    /** Whether the continuous point {@code x}, {@code y}, {@code z} lies inside or on the boundary of this area. */
    public boolean containsPoint(final String world, final double x, final double y, final double z) {
        return this.world.equals(world)
                && y >= minY
                && y < maxY + 1.0
                && x >= minX + 0.5 - 1e-9
                && x <= maxX + 0.5 + 1e-9
                && z >= minZ + 0.5 - 1e-9
                && z <= maxZ + 0.5 + 1e-9
                && Polygons.containsPoint(corners, x, z);
    }

    /** Returns the average of the polygon's block centres as {@code {x, z}}. */
    public double[] centre() {
        return Polygons.centre(corners);
    }

    /** Returns a unit horizontal vector {@code {x, z}} pushing a point out of this polygon. */
    public double[] outward(final double x, final double z) {
        return Polygons.outward(corners, x, z);
    }

    /** Returns a point {@code margin} blocks outside the nearest edge of this polygon. */
    public double[] outsidePoint(final double x, final double z, final double margin) {
        return Polygons.outsidePoint(corners, x, z, margin);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final PolygonArea area
                && minY == area.minY
                && maxY == area.maxY
                && name.equals(area.name)
                && world.equals(area.world)
                && corners.equals(area.corners);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, world, corners, minY, maxY);
    }

    @Override
    public String toString() {
        return "PolygonArea[name=" + name + ", world=" + world + ", minY=" + minY + ", maxY=" + maxY + ", corners="
                + corners + "]";
    }
}
