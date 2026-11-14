package eu.nordtal.season.underground.transplant;

/** Distances through the cells of a tile in thirds of a block: 3 across a face, 4 across an edge, 5 across a corner. */
final class Spread {

    /** A cell the distance may reach and has not reached. */
    static final int UNREACHED = 255;

    /** A cell no distance passes through. */
    static final int BLOCKED = 254;

    private Spread() {}

    /** Fills in the distance to the nearest cell that holds 0, through cells that hold UNREACHED, up to a limit. */
    static void distances(final Tile tile, final byte[] distance, final int limit) {
        final int[] steps = new int[26];
        final int[] weights = new int[26];
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes > 0) {
                        steps[count] = tile.index(dx, dy, dz);
                        weights[count++] = axes + 2;
                    }
                }
            }
        }
        final Cells[] buckets = new Cells[limit + 6];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Cells();
        }
        seed(tile, distance, buckets[0]);
        for (int reached = 0; reached <= limit; reached++) {
            final Cells bucket = buckets[reached];
            for (int at = 0; at < bucket.size(); at++) {
                final int cell = bucket.get(at);
                if ((distance[cell] & 0xFF) != reached || tile.edge(cell)) {
                    continue;
                }
                for (int k = 0; k < 26; k++) {
                    final int next = cell + steps[k];
                    final int far = reached + weights[k];
                    final int known = distance[next] & 0xFF;
                    if (far <= limit && known != BLOCKED && far < known) {
                        distance[next] = (byte) far;
                        buckets[far].push(next);
                    }
                }
            }
            buckets[reached] = new Cells();
        }
    }

    /** Sets a flag on every cell that carries MARK and is joined to a seed through faces, and returns their number. */
    static int flood(final Tile tile, final Cells seeds, final int flag) {
        final int[] faces = tile.faces();
        final byte[] flags = tile.flags;
        int reached = 0;
        while (!seeds.isEmpty()) {
            final int cell = seeds.pop();
            if ((flags[cell] & Tile.MARK) == 0) {
                continue;
            }
            flags[cell] = (byte) ((flags[cell] & ~Tile.MARK) | flag);
            reached++;
            if (tile.edge(cell)) {
                continue;
            }
            for (final int face : faces) {
                if ((flags[cell + face] & Tile.MARK) != 0) {
                    seeds.push(cell + face);
                }
            }
        }
        return reached;
    }

    /** Removes MARK from every cell. */
    static void unmark(final Tile tile) {
        final byte[] flags = tile.flags;
        for (int i = 0; i < flags.length; i++) {
            flags[i] = (byte) (flags[i] & ~Tile.MARK);
        }
    }

    private static void seed(final Tile tile, final byte[] distance, final Cells sources) {
        final int[] faces = tile.faces();
        for (int layer = 1; layer < tile.height - 1; layer++) {
            for (int z = 1; z < tile.depth - 1; z++) {
                int cell = tile.index(1, layer, z);
                for (int x = 1; x < tile.width - 1; x++, cell++) {
                    if (distance[cell] != 0) {
                        continue;
                    }
                    for (final int face : faces) {
                        if ((distance[cell + face] & 0xFF) == UNREACHED) {
                            sources.push(cell);
                            break;
                        }
                    }
                }
            }
        }
    }
}
