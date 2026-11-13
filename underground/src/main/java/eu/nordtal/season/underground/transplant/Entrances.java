package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.block.Blocks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Joins caves of the map that go on below the kept zone to a generated cave nearby, through a winding tunnel. */
final class Entrances {

    private static final int SMALLEST = 30;
    private static final int SEARCH = 40;
    private static final int TRIES = 5;

    /** The samples of a tunnel's line over which its slope is taken, about five blocks. */
    private static final int STRETCH = 12;

    /** The steepest a stretch of a tunnel may be, as rise over run: a little more than a block per block. */
    private static final double STEEPEST = 1.1;

    private final Tile tile;
    private final Parameters measures;
    private final Tally tally;
    private final List<Front> fronts = new ArrayList<>();
    private final List<int[]> beyond = new ArrayList<>();
    private final List<String> joined = new ArrayList<>();

    Entrances(final Tile tile, final Parameters measures, final Tally tally) {
        this.tile = tile;
        this.measures = measures;
        this.tally = tally;
    }

    /** The stretch of a map cave where it would narrow, the cell at its deep end and the way the cave heads. */
    private static final class Front {
        private final Cells cells;
        private final int deep;
        private final double[] heading;
        private final double width;

        Front(final Cells cells, final int deep, final double[] heading, final double width) {
            this.cells = cells;
            this.deep = deep;
            this.heading = heading;
            this.width = width;
        }
    }

    /** Returns the opened cells that lie in a neighbouring region, as world positions x, y, z. */
    List<int[]> beyond() {
        return beyond;
    }

    /** Returns a line per entrance: where the cave of the map ends, the middle of its tunnel and where that ends. */
    List<String> joined() {
        return joined;
    }

    /** Finds the caves to join, while the first layer holds the distance from the sky and the second from walls. */
    void find() {
        final int limit = 3 * measures.outsideReach();
        final int from = limit - 3 * measures.taper();
        final byte[] flags = tile.flags;
        for (int i = 0; i < flags.length; i++) {
            final int far = tile.first[i] & 0xFF;
            if ((tile.map[i] & Blocks.KIND) == Blocks.AIR
                    && (flags[i] & Tile.SKY) == 0
                    && far >= from
                    && far <= limit) {
                flags[i] |= (byte) Tile.MARK;
            }
        }
        for (int i = 0; i < flags.length; i++) {
            if ((flags[i] & Tile.MARK) != 0) {
                front(component(i), from, limit);
            }
        }
    }

    private Cells component(final int start) {
        final int[] faces = tile.faces();
        final Cells cells = new Cells();
        final Cells open = new Cells();
        open.push(start);
        tile.flags[start] &= (byte) ~Tile.MARK;
        while (!open.isEmpty()) {
            final int cell = open.pop();
            cells.push(cell);
            for (int k = 0; k < 6 && !tile.edge(cell); k++) {
                final int next = cell + faces[k];
                if ((tile.flags[next] & Tile.MARK) != 0) {
                    tile.flags[next] &= (byte) ~Tile.MARK;
                    open.push(next);
                }
            }
        }
        return cells;
    }

    private void front(final Cells cells, final int from, final int limit) {
        final double[] near = new double[4];
        final double[] deep = new double[4];
        boolean goesOn = false;
        for (int at = 0; at < cells.size(); at++) {
            final int cell = cells.get(at);
            final int far = tile.first[cell] & 0xFF;
            if (far <= from + 3) {
                add(near, cell);
            }
            if (far >= limit - 6) {
                add(deep, cell);
                goesOn |= goesOn(cell);
            }
        }
        if (cells.size() < SMALLEST || near[3] == 0 || deep[3] == 0 || !goesOn) {
            return;
        }
        final double[] heading = new double[3];
        double length = 0;
        for (int axis = 0; axis < 3; axis++) {
            heading[axis] = deep[axis] / deep[3] - near[axis] / near[3];
            length += heading[axis] * heading[axis];
        }
        length = Math.sqrt(length);
        int end = -1;
        double best = Double.MAX_VALUE;
        for (int at = 0; at < cells.size(); at++) {
            final int cell = cells.get(at);
            final double off = square(tile.x(cell) - deep[0] / deep[3])
                    + square(tile.layer(cell) - deep[1] / deep[3])
                    + square(tile.z(cell) - deep[2] / deep[3]);
            if (off < best) {
                best = off;
                end = cell;
            }
        }
        final int margin = TileLoader.MARGIN * 16;
        final boolean own = tile.x(end) >= margin
                && tile.x(end) < tile.width - margin
                && tile.z(end) >= margin
                && tile.z(end) < tile.depth - margin;
        if (length >= 1 && own) {
            for (int axis = 0; axis < 3; axis++) {
                heading[axis] /= length;
            }
            level(heading);
            fronts.add(new Front(cells, end, heading, (tile.second[end] & 0xFF) / 3.0));
            tally.mouths++;
        }
    }

    /** Turns a heading that points steeply up or down towards the level, so the tunnel starts as a walk. */
    private static void level(final double[] heading) {
        heading[1] = Math.clamp(heading[1], -0.6, 0.3);
        final double length = Math.sqrt(square(heading[0]) + square(heading[1]) + square(heading[2]));
        for (int axis = 0; axis < 3; axis++) {
            heading[axis] /= length;
        }
    }

    private boolean goesOn(final int cell) {
        if (tile.edge(cell)) {
            return false;
        }
        for (final int face : tile.faces()) {
            final int next = cell + face;
            final boolean unreached = (tile.first[next] & 0xFF) == Spread.UNREACHED;
            if (unreached && Blocks.open(tile.map[next]) && (tile.flags[next] & Tile.SKY) == 0) {
                return true;
            }
        }
        return false;
    }

    private void add(final double[] sum, final int cell) {
        sum[0] += tile.x(cell);
        sum[1] += tile.layer(cell);
        sum[2] += tile.z(cell);
        sum[3]++;
    }

    private static double square(final double value) {
        return value * value;
    }

    /** Opens a tunnel for every found cave that has a generated cave in reach, once the caves are closed. */
    void join() {
        for (final Front front : fronts) {
            final int[] targets = targets(front);
            for (final int target : targets) {
                if (target < 0) {
                    continue;
                }
                final Bored bored = tunnel(front, target);
                final Cells tunnel = bored.cells();
                final boolean sound = bored.walkable() && sound(tunnel);
                for (int at = 0; at < tunnel.size(); at++) {
                    tile.flags[tunnel.get(at)] &= (byte) ~Tile.MARK;
                }
                if (sound) {
                    open(front.cells);
                    open(tunnel);
                    tally.entrances++;
                    joined.add("entrance opened: from=\"" + place(front.deep) + "\" over=\""
                            + place(tunnel.get(tunnel.size() / 2)) + "\" to=\"" + place(target) + "\"");
                    break;
                }
            }
        }
    }

    /** Returns the best cells of generated caves to head for: roomy air below the closing band, near the heading. */
    private int[] targets(final Front front) {
        final int[] best = new int[TRIES];
        final double[] scores = new double[TRIES];
        Arrays.fill(best, -1);
        Arrays.fill(scores, Double.MAX_VALUE);
        final int x0 = tile.x(front.deep);
        final int y0 = tile.layer(front.deep);
        final int z0 = tile.z(front.deep);
        for (int y = Math.max(2, y0 - SEARCH); y <= Math.min(tile.height - 3, y0 + 2); y++) {
            for (int z = Math.max(2, z0 - SEARCH); z <= Math.min(tile.depth - 3, z0 + SEARCH); z++) {
                for (int x = Math.max(2, x0 - SEARCH); x <= Math.min(tile.width - 3, x0 + SEARCH); x++) {
                    final int cell = tile.index(x, y, z);
                    final boolean cave = (tile.flags[cell] & (Tile.KEPT | Tile.CLOSED)) == 0
                            && (tile.parallel[cell] & Blocks.KIND) == Blocks.AIR
                            && (tile.second[cell] & 0xFF) >= 6
                            && (tile.first[cell] & 0xFF) >= 18;
                    final double far = Math.sqrt(square(x - x0) + square(y - y0) + square(z - z0));
                    final boolean steep = Math.abs(y - y0) > Math.hypot(x - x0, z - z0);
                    if (!cave || steep || far < 6 || far > SEARCH) {
                        continue;
                    }
                    final double along =
                            ((x - x0) * front.heading[0] + (y - y0) * front.heading[1] + (z - z0) * front.heading[2])
                                    / far;
                    rank(best, scores, cell, far * (1 + 1.5 * (1 - along)));
                }
            }
        }
        return best;
    }

    /** Keeps the lowest scores, and of cells close to each other only the first, so the tries differ. */
    private void rank(final int[] best, final double[] scores, final int cell, final double score) {
        if (score >= scores[TRIES - 1]) {
            return;
        }
        for (int i = 0; i < TRIES; i++) {
            final boolean close = best[i] >= 0
                    && Math.abs(tile.x(best[i]) - tile.x(cell)) + Math.abs(tile.z(best[i]) - tile.z(cell)) < 12;
            if (close && scores[i] <= score) {
                return;
            }
            if (close) {
                best[i] = cell;
                scores[i] = score;
                return;
            }
        }
        int at = TRIES - 1;
        while (at > 0 && scores[at - 1] > score) {
            best[at] = best[at - 1];
            scores[at] = scores[at - 1];
            at--;
        }
        best[at] = cell;
        scores[at] = score;
    }

    /** The cells of a tunnel, each marked, and whether no stretch of it is too steep to walk. */
    private record Bored(Cells cells, boolean walkable) {}

    /** Returns the tunnel along a bent line from the deep end of the cave to the target. */
    private Bored tunnel(final Front front, final int target) {
        final double[] start = {tile.x(front.deep), tile.layer(front.deep), tile.z(front.deep)};
        final double[] end = {tile.x(target), tile.layer(target), tile.z(target)};
        final double length =
                Math.sqrt(square(end[0] - start[0]) + square(end[1] - start[1]) + square(end[2] - start[2]));
        final double flat = Math.max(1, Math.hypot(end[0] - start[0], end[2] - start[2]));
        final double sway = Noise.at(
                tile.originX + ((int) start[0]) * 7, ((int) start[1]) * 7, tile.originZ + ((int) start[2]) * 7);
        // A cave that ends as a shaft has no way it heads sideways, so its tunnel leaves towards the target.
        final boolean shaft = Math.hypot(front.heading[0], front.heading[2]) < 0.3;
        final double[] lead = shaft
                ? new double[] {(end[0] - start[0]) / length, (end[1] - start[1]) / length, (end[2] - start[2]) / length
                }
                : front.heading;
        final double[] bend = {
            start[0] + lead[0] * length * 0.5 - (end[2] - start[2]) / flat * length * 0.3 * sway,
            start[1] + lead[1] * length * 0.5,
            start[2] + lead[2] * length * 0.5 + (end[0] - start[0]) / flat * length * 0.3 * sway
        };
        final double from = Math.clamp(front.width, 1.6, 3.0);
        final Cells cells = new Cells();
        final int steps = (int) Math.ceil(length * 2.5);
        final double[][] points = new double[steps + 1][3];
        boolean walkable = true;
        for (int step = 0; step <= steps; step++) {
            final double t = (double) step / steps;
            final double[] point = points[step];
            for (int axis = 0; axis < 3; axis++) {
                point[axis] = (1 - t) * (1 - t) * start[axis] + 2 * (1 - t) * t * bend[axis] + t * t * end[axis];
            }
            if (step >= STRETCH) {
                final double[] before = points[step - STRETCH];
                final double rise = Math.abs(point[1] - before[1]);
                walkable &= rise <= STEEPEST * Math.hypot(point[0] - before[0], point[2] - before[2]);
            }
            final double wobble = Noise.at(
                    tile.originX + (int) (point[0] * 2), (int) (point[1] * 2), tile.originZ + (int) (point[2] * 2));
            bore(cells, point, from + (2.3 - from) * t + 0.6 * wobble);
        }
        return new Bored(cells, walkable);
    }

    private void bore(final Cells cells, final double[] point, final double radius) {
        final int reach = (int) Math.ceil(radius);
        for (int y = (int) point[1] - reach; y <= (int) point[1] + reach; y++) {
            for (int z = (int) point[2] - reach; z <= (int) point[2] + reach; z++) {
                for (int x = (int) point[0] - reach; x <= (int) point[0] + reach; x++) {
                    final double off = square((x - point[0]) / radius)
                            + square((y - point[1]) / (radius * 0.85))
                            + square((z - point[2]) / radius);
                    final boolean inside =
                            x >= 0 && y >= 0 && z >= 0 && x < tile.width && y < tile.height && z < tile.depth;
                    if (off <= 1 && inside && (tile.flags[tile.index(x, y, z)] & Tile.MARK) == 0) {
                        tile.flags[tile.index(x, y, z)] |= (byte) Tile.MARK;
                        cells.push(tile.index(x, y, z));
                    }
                }
            }
        }
    }

    /** Returns whether a tunnel touches nothing built, no fluid and no ground close below the open sky. */
    boolean sound(final Cells tunnel) {
        final int[] faces = tile.faces();
        for (int at = 0; at < tunnel.size(); at++) {
            final int cell = tunnel.get(at);
            final int x = tile.x(cell);
            final int z = tile.z(cell);
            final int layer = tile.layer(cell);
            if (x < 2 || z < 2 || layer < 2 || x > tile.width - 3 || z > tile.depth - 3 || layer > tile.height - 6) {
                return false;
            }
            if ((tile.map[cell] & Blocks.KIND) == Blocks.BUILT && tile.has(cell, Tile.KEPT)) {
                return false;
            }
            if (fluid(cell) || tile.has(cell, Tile.PROTECTED)) {
                return false;
            }
            for (final int face : faces) {
                final boolean built = (tile.map[cell + face] & Blocks.KIND) == Blocks.BUILT;
                if (fluid(cell + face) || tile.has(cell + face, Tile.PROTECTED) || built) {
                    return false;
                }
            }
            for (int up = 1; up <= 4; up++) {
                for (int side = 0; side < 9; side++) {
                    if (tile.has(tile.index(x + side % 3 - 1, layer + up, z + side / 3 - 1), Tile.SKY)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private String place(final int cell) {
        return (tile.originX + tile.x(cell)) + " " + (tile.layer(cell) + ChunkBlocks.MIN_Y) + " "
                + (tile.originZ + tile.z(cell));
    }

    private boolean fluid(final int cell) {
        final boolean kept = tile.has(cell, Tile.KEPT);
        if (tile.has(cell, kept ? Tile.FILLED : Tile.CLOSED)) {
            return false;
        }
        final int kind = (kept ? tile.map[cell] : tile.parallel[cell]) & Blocks.KIND;
        return kind == Blocks.WATER || kind == Blocks.LAVA;
    }

    private void open(final Cells cells) {
        final int margin = TileLoader.MARGIN * 16;
        for (int at = 0; at < cells.size(); at++) {
            final int cell = cells.get(at);
            tile.flags[cell] |= (byte) Tile.CARVED;
            final int x = tile.x(cell);
            final int z = tile.z(cell);
            if (x < margin || z < margin || x >= tile.width - margin || z >= tile.depth - margin) {
                beyond.add(new int[] {tile.originX + x, tile.layer(cell) + ChunkBlocks.MIN_Y, tile.originZ + z});
            }
        }
    }
}
