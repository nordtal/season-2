package eu.nordtal.season.underground.transplant;

/** A box of the world as bytes: one layer per world and per purpose, a cell at (y * depth + z) * width + x. */
final class Tile {

    /** Open from the top of the world down to this cell. */
    static final int SKY = 1;

    /** Open and reached from the sky, so a player meets it without digging. */
    static final int OUTSIDE = 2;

    /** Open space that belongs to something built. */
    static final int PROTECTED = 4;

    /** The cell stays as it is in the map. */
    static final int KEPT = 8;

    /** A kept open cell that becomes rock, because the cave it belongs to is cut off behind it. */
    static final int FILLED = 16;

    /** An open cell of the generated world that becomes rock, so its cave closes below the kept zone. */
    static final int CLOSED = 32;

    /** A cell that an entrance opens. */
    static final int CARVED = 64;

    /** Free for the step that is running. */
    static final int MARK = 128;

    final int originX;
    final int originZ;
    final int width;
    final int depth;
    final int height;
    final byte[] map;
    final byte[] parallel;
    final byte[] flags;
    final byte[] first;
    final byte[] second;
    final boolean[] missing;

    Tile(final int originX, final int originZ, final int width, final int depth, final int height) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.height = height;
        final int size = Math.multiplyExact(Math.multiplyExact(width, depth), height);
        map = new byte[size];
        parallel = new byte[size];
        flags = new byte[size];
        first = new byte[size];
        second = new byte[size];
        missing = new boolean[width * depth];
    }

    int size() {
        return map.length;
    }

    int index(final int x, final int layer, final int z) {
        return (layer * depth + z) * width + x;
    }

    int x(final int cell) {
        return cell % width;
    }

    int z(final int cell) {
        return cell / width % depth;
    }

    int layer(final int cell) {
        return cell / (width * depth);
    }

    /** Returns whether a cell lies on the surface of the box, where it has no full set of neighbours. */
    boolean edge(final int cell) {
        final int x = x(cell);
        final int z = z(cell);
        final int layer = layer(cell);
        return x == 0 || z == 0 || layer == 0 || x == width - 1 || z == depth - 1 || layer == height - 1;
    }

    boolean has(final int cell, final int flag) {
        return (flags[cell] & flag) != 0;
    }

    /** Returns the index steps to the six neighbours that share a face with a cell. */
    int[] faces() {
        return new int[] {1, -1, width, -width, width * depth, -width * depth};
    }
}
