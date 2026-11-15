package eu.nordtal.season.underground.structures;

/** A box of blocks, both corners included. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("A box has its larger corner last.");
        }
    }

    /** Returns the box of the game's BB array. */
    public static Box of(final int[] corners) {
        return new Box(corners[0], corners[1], corners[2], corners[3], corners[4], corners[5]);
    }

    /** Returns the box as the game's BB array. */
    public int[] corners() {
        return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Returns the smallest box around this one and another. */
    public Box union(final Box other) {
        return new Box(
                Math.min(minX, other.minX),
                Math.min(minY, other.minY),
                Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX),
                Math.max(maxY, other.maxY),
                Math.max(maxZ, other.maxZ));
    }

    /** Returns the box grown by these distances on each side. */
    public Box grow(final int across, final int down, final int up) {
        return new Box(minX - across, minY - down, minZ - across, maxX + across, maxY + up, maxZ + across);
    }

    /** Returns whether the two boxes share a block. */
    public boolean meets(final Box other) {
        return minX <= other.maxX
                && other.minX <= maxX
                && minY <= other.maxY
                && other.minY <= maxY
                && minZ <= other.maxZ
                && other.minZ <= maxZ;
    }

    /** Returns whether the box holds a position. */
    public boolean holds(final int x, final int y, final int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Returns the lowest chunk X the box touches. */
    public int minChunkX() {
        return minX >> 4;
    }

    /** Returns the lowest chunk Z the box touches. */
    public int minChunkZ() {
        return minZ >> 4;
    }

    /** Returns the highest chunk X the box touches. */
    public int maxChunkX() {
        return maxX >> 4;
    }

    /** Returns the highest chunk Z the box touches. */
    public int maxChunkZ() {
        return maxZ >> 4;
    }

    @Override
    public String toString() {
        return minX + " " + minY + " " + minZ + " " + maxX + " " + maxY + " " + maxZ;
    }
}
