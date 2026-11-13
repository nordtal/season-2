package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.block.Blocks;

/** Decides for every cell of a tile whether the map stays, and where rock closes a cave on either side of the cut. */
final class Zones {

    /** The length of the coarse wave in the closing rock, in blocks. */
    private static final double COARSE = 27.0;

    private final Tile tile;
    private final Parameters measures;
    private final byte[] map;
    private final byte[] flags;
    private final Entrances entrances;

    Zones(final Tile tile, final Parameters measures, final Entrances entrances) {
        this.tile = tile;
        this.measures = measures;
        this.entrances = entrances;
        map = tile.map;
        flags = tile.flags;
    }

    /** Runs every step in its order; afterwards the flags of the tile hold the result. */
    void decide() {
        settleAmbiguous();
        sky();
        wallDistances();
        reach(Tile.SKY, measures.outsideReach(), Tile.OUTSIDE);
        protect();
        crust();
        fill();
        close();
        if (measures.entrances()) {
            entrances.join();
        }
    }

    /** An ambiguous block counts as built where it is joined to a built block through its like, else as ground. */
    private void settleAmbiguous() {
        final byte[] distance = tile.first;
        for (int i = 0; i < map.length; i++) {
            final int kind = map[i] & Blocks.KIND;
            distance[i] =
                    (byte) (kind == Blocks.BUILT ? 0 : kind == Blocks.AMBIGUOUS ? Spread.UNREACHED : Spread.BLOCKED);
        }
        final int limit = 3 * measures.ambiguousReach();
        Spread.distances(tile, distance, limit);
        for (int i = 0; i < map.length; i++) {
            if ((map[i] & Blocks.KIND) == Blocks.AMBIGUOUS) {
                map[i] = (byte) ((distance[i] & 0xFF) <= limit ? Blocks.BUILT : (map[i] & ~Blocks.KIND) | Blocks.ROCK);
            }
        }
    }

    private void sky() {
        for (int z = 0; z < tile.depth; z++) {
            for (int x = 0; x < tile.width; x++) {
                for (int layer = tile.height - 1; layer >= 0; layer--) {
                    final int cell = tile.index(x, layer, z);
                    if (!Blocks.open(map[cell])) {
                        break;
                    }
                    flags[cell] |= (byte) (Tile.SKY | Tile.OUTSIDE);
                }
            }
        }
    }

    /** Leaves in the second layer how far each open cell below the sky is from the nearest wall. */
    private void wallDistances() {
        final byte[] distance = tile.second;
        for (int i = 0; i < map.length; i++) {
            final boolean open = Blocks.open(map[i]);
            distance[i] = (byte) (!open ? 0 : (flags[i] & Tile.SKY) != 0 ? Spread.BLOCKED : Spread.UNREACHED);
        }
        Spread.distances(tile, distance, 3 * Parameters.WALL_CAP);
    }

    /**
     * Follows open space from the cells with the source flag and sets the result flag as far as the limit.
     * Over the last blocks the space narrows: a cell stays only while it is far enough from the walls.
     */
    private void reach(final int source, final int limit, final int result) {
        final byte[] distance = tile.first;
        for (int i = 0; i < map.length; i++) {
            final boolean passable = Blocks.open(map[i]) && (flags[i] & Tile.SKY) == 0;
            distance[i] = (byte) ((flags[i] & source) != 0 ? 0 : passable ? Spread.UNREACHED : Spread.BLOCKED);
        }
        Spread.distances(tile, distance, 3 * limit);
        if (source == Tile.SKY && measures.entrances()) {
            entrances.find();
        }
        final Cells seeds = new Cells();
        final double taper = measures.taper();
        for (int i = 0; i < map.length; i++) {
            final int far = distance[i] & 0xFF;
            if (far == 0 || far > 3 * limit) {
                continue;
            }
            final double left = limit - far / 3.0;
            final double wall = (tile.second[i] & 0xFF) / 3.0;
            if (left >= taper || (left > 0 && wall >= taper * taper / left - taper)) {
                flags[i] |= (byte) Tile.MARK;
                if (far <= 5) {
                    seeds.push(i);
                }
            }
        }
        Spread.flood(tile, seeds, result);
        Spread.unmark(tile);
    }

    /** Keeps what is built with the rock around it, the open space that belongs to it and a wall around that. */
    private void protect() {
        final byte[] distance = tile.first;
        for (int i = 0; i < map.length; i++) {
            distance[i] = (byte) ((map[i] & Blocks.KIND) == Blocks.BUILT ? 0 : Spread.UNREACHED);
        }
        Spread.distances(tile, distance, 3 * measures.shell());
        for (int i = 0; i < map.length; i++) {
            if ((distance[i] & 0xFF) <= 3 * measures.shell()) {
                final boolean open = Blocks.open(map[i]) && (flags[i] & Tile.SKY) == 0;
                flags[i] |= (byte) (open ? Tile.KEPT | Tile.PROTECTED : Tile.KEPT);
            }
        }
        reach(Tile.PROTECTED, measures.protectedReach(), Tile.PROTECTED);
        keepAround(Tile.PROTECTED, measures.wall());
    }

    private void crust() {
        keepAround(Tile.OUTSIDE, measures.crust());
        final int top = measures.top() - ChunkBlocks.MIN_Y;
        for (int layer = 0; layer < tile.height; layer++) {
            for (int column = 0; column < tile.width * tile.depth; column++) {
                if (layer > top || tile.missing[column]) {
                    flags[layer * tile.width * tile.depth + column] |= (byte) Tile.KEPT;
                }
            }
        }
    }

    private void keepAround(final int flag, final int blocks) {
        final byte[] distance = tile.first;
        for (int i = 0; i < map.length; i++) {
            distance[i] = (byte) ((flags[i] & flag) != 0 ? 0 : Spread.UNREACHED);
        }
        Spread.distances(tile, distance, 3 * blocks);
        for (int i = 0; i < map.length; i++) {
            if ((distance[i] & 0xFF) <= 3 * blocks) {
                flags[i] |= (byte) Tile.KEPT;
            }
        }
    }

    /** Fills the kept stretch of a map cave that goes on below the kept zone, back to where it narrowed. */
    private void fill() {
        final int[] faces = tile.faces();
        final Cells seeds = new Cells();
        final int open = Tile.OUTSIDE | Tile.PROTECTED;
        for (int i = 0; i < map.length; i++) {
            if ((flags[i] & Tile.KEPT) == 0 || (flags[i] & open) != 0 || !Blocks.open(map[i])) {
                continue;
            }
            flags[i] |= (byte) Tile.MARK;
            if (tile.edge(i)) {
                continue;
            }
            for (final int face : faces) {
                if ((flags[i + face] & Tile.KEPT) == 0 && Blocks.open(map[i + face])) {
                    seeds.push(i);
                    break;
                }
            }
        }
        Spread.flood(tile, seeds, Tile.FILLED);
        Spread.unmark(tile);
    }

    /** Closes generated caves below the kept zone: the nearer the zone, the wider the cave that still closes. */
    private void close() {
        final byte[] toKept = tile.first;
        final byte[] toWall = tile.second;
        for (int i = 0; i < map.length; i++) {
            toKept[i] = (byte) ((flags[i] & Tile.KEPT) != 0 ? 0 : Spread.UNREACHED);
            toWall[i] = (byte) ((tile.parallel[i] & Blocks.KIND) == Blocks.ROCK ? 0 : Spread.UNREACHED);
        }
        final int band = measures.band();
        Spread.distances(tile, toKept, 3 * band);
        Spread.distances(tile, toWall, 3 * Parameters.CAVE_CAP);
        for (int i = 0; i < map.length; i++) {
            final int near = toKept[i] & 0xFF;
            if (near == 0 || near > 3 * band || toWall[i] == 0) {
                continue;
            }
            final double below = Math.max(0.5, near / 3.0 + rough(i, near / 3.0));
            final double wall = Math.min(Parameters.CAVE_CAP, (toWall[i] & 0xFF) / 3.0);
            if (wall <= band * band / below - band) {
                flags[i] |= (byte) Tile.CLOSED;
            }
        }
    }

    /**
     * Returns by how many blocks a cell counts as further from the kept zone than it is.
     * A fine and a coarse wave make the closing rock rise and fall, so the ceiling of a wide cave is not a plane
     * below level ground. Next to the kept zone there is none, so no cave stays open against it.
     */
    private double rough(final int cell, final double below) {
        final int x = tile.originX + tile.x(cell);
        final int y = tile.layer(cell) + ChunkBlocks.MIN_Y;
        final int z = tile.originZ + tile.z(cell);
        final double waves = Noise.at(x, y, z) + 2 * Noise.at(x, y, z, COARSE);
        return measures.roughness() * waves * Math.clamp((below - 1) / 3, 0, 1);
    }
}
