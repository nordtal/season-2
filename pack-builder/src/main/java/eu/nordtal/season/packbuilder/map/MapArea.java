package eu.nordtal.season.packbuilder.map;

/**
 * A square of blocks the map shows, from {@code minX} and {@code minZ} along {@code size} blocks.
 *
 * @param size the side in blocks, a multiple of every scale and of a map tile's pixels times that scale
 */
public record MapArea(int minX, int minZ, int size) {

    /** The play area, x and z 0 to 9983. */
    public static final MapArea PLAY = new MapArea(0, 0, 9984);

    public MapArea {
        if (size <= 0) {
            throw new IllegalArgumentException("map area without blocks: " + size);
        }
    }

    /** Returns the largest x inside. */
    public int maxX() {
        return minX + size - 1;
    }

    /** Returns the largest z inside. */
    public int maxZ() {
        return minZ + size - 1;
    }
}
