package eu.nordtal.season.underground.check;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures a result against the map and the generated world, without the decisions of the transplant.
 * It counts built blocks that changed, fluids that met open space of the other world, and flat faces of new rock.
 */
public final class Seams {

    /** The size from which a flat face counts: a square of five by five blocks. */
    static final int FLAT = 25;

    /** The size from which a flat face is large: a square of about fourteen by fourteen blocks. */
    static final int LARGE = 200;

    static final int CLOSED = 0;
    static final int PLUG = 1;
    static final int NATURAL = 2;
    private static final int UP = 4;
    private static final String[] FACES = {"wall", "floor", "ceiling"};

    private final long[][][] faces = new long[3][3][5];
    private final String[][] places = new String[3][3];
    private long built;
    private long builtChanged;
    private long ambiguous;
    private long ambiguousChanged;
    private long fluidMeetsMap;
    private long fluidMeetsParallel;

    /** The outermost cells of a measured region towards a side whose neighbour has not been measured yet. */
    private final Map<Border, byte[]> borders = new HashMap<>();

    /** One side of a region: 0 towards lower x, 1 higher x, 2 lower z, 3 higher z. */
    private record Border(int regionX, int regionZ, int side) {}

    /**
     * The limits a result has to stay within.
     *
     * @param flatShare the largest share of the ceilings and floors of new rock that may lie in flat faces, and in
     *     large ones, as a multiple of that share among the ceilings and floors the game made itself
     */
    public record Limits(double flatShare) {}

    /** Prints the measures of the regions and returns whether they stay within the limits. */
    public static boolean run(
            final Path map, final Path parallel, final Path out, final List<int[]> regions, final Limits limits)
            throws IOException {
        final Seams seams = new Seams();
        for (final int[] region : regions) {
            if (Files.exists(Region.file(out.resolve("region"), region[0], region[1]))) {
                seams.region(new Compared(map, parallel, out, region[0], region[1]).cells, region[0], region[1]);
                System.out.println("region measured: x=" + region[0] + " z=" + region[1]);
            }
        }
        return seams.report(limits);
    }

    private void region(final byte[] cells, final int regionX, final int regionZ) {
        final byte[][] marks = new byte[3][cells.length];
        for (final byte facts : cells) {
            if ((facts & Compared.BUILT) != 0) {
                built++;
                builtChanged += (facts & Compared.AS_MAP) == 0 ? 1 : 0;
            }
            if ((facts & Compared.AMBIGUOUS) != 0) {
                ambiguous++;
                ambiguousChanged += (facts & Compared.AS_MAP) == 0 ? 1 : 0;
            }
        }
        final int[] steps = {
            1, -1, Compared.SIDE, -Compared.SIDE, Compared.SIDE * Compared.SIDE, -Compared.SIDE * Compared.SIDE
        };
        for (int layer = 1; layer < ChunkBlocks.HEIGHT - 1; layer++) {
            for (int z = 1; z < Compared.SIDE - 1; z++) {
                for (int x = 1; x < Compared.SIDE - 1; x++) {
                    cell(cells, marks, steps, Compared.index(x, layer, z));
                }
            }
        }
        edges(cells);
        borders(cells, regionX, regionZ);
        for (int kind = 0; kind < 3; kind++) {
            for (int side = 0; side < 6; side++) {
                final int face = side < 4 ? 0 : side == UP ? 1 : 2;
                final long[] sum = faces[kind][face];
                final long largest = sum[2];
                patches(marks[kind], side, sum);
                if (sum[2] > largest) {
                    final int cell = (int) sum[3];
                    places[kind][face] = (regionX * Compared.SIDE + cell % Compared.SIDE) + " "
                            + (cell / (Compared.SIDE * Compared.SIDE) + ChunkBlocks.MIN_Y) + " "
                            + (regionZ * Compared.SIDE + cell / Compared.SIDE % Compared.SIDE);
                }
            }
        }
    }

    private void cell(final byte[] cells, final byte[][] marks, final int[] steps, final int cell) {
        final int facts = cells[cell];
        for (int side = 0; side < 6; side++) {
            final int next = cells[cell + steps[side]];
            final boolean open = (next & Compared.OPEN) != 0;
            contact(facts, next);
            if ((facts & Compared.OPEN) != 0 || !open) {
                continue;
            }
            final boolean cave = (next & Compared.PARALLEL_OPEN) != 0 && (next & Compared.AS_PARALLEL) != 0;
            final boolean mapCave = (next & Compared.MAP_OPEN) != 0 && (next & Compared.AS_MAP) != 0;
            if (cave && (facts & Compared.PARALLEL_OPEN) != 0) {
                marks[CLOSED][cell] |= (byte) (1 << side);
            } else if (cave && (facts & Compared.AS_PARALLEL) != 0) {
                marks[NATURAL][cell] |= (byte) (1 << side);
            }
            if (mapCave && !cave && (facts & Compared.MAP_OPEN) != 0) {
                marks[PLUG][cell] |= (byte) (1 << side);
            }
        }
    }

    /** Counts a fluid of one world that meets open space only the other world has. */
    private void contact(final int facts, final int next) {
        if ((facts & Compared.FLUID) == 0 || (next & Compared.OPEN) == 0 || (next & Compared.FLUID) != 0) {
            return;
        }
        final int from = facts & (Compared.AS_PARALLEL | Compared.AS_MAP);
        fluidMeetsMap += from == Compared.AS_PARALLEL && (next & Compared.PARALLEL_OPEN) == 0 ? 1 : 0;
        fluidMeetsParallel += from == Compared.AS_MAP && (next & Compared.MAP_OPEN) == 0 ? 1 : 0;
    }

    /** Counts the fluid contacts of the outermost cells, which the faces leave out, with their neighbours inside. */
    private void edges(final byte[] cells) {
        final int last = Compared.SIDE - 1;
        for (int layer = 0; layer < ChunkBlocks.HEIGHT; layer++) {
            final boolean outer = layer == 0 || layer == ChunkBlocks.HEIGHT - 1;
            for (int z = 0; z < Compared.SIDE; z++) {
                for (int x = 0; x < Compared.SIDE; x += outer || z == 0 || z == last ? 1 : last) {
                    fluidAround(cells, x, layer, z);
                }
            }
        }
    }

    private void fluidAround(final byte[] cells, final int x, final int layer, final int z) {
        final int facts = cells[Compared.index(x, layer, z)];
        if ((facts & Compared.FLUID) == 0) {
            return;
        }
        final int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (final int[] step : steps) {
            final int nx = x + step[0];
            final int nl = layer + step[1];
            final int nz = z + step[2];
            if (nx >= 0 && nx < Compared.SIDE && nz >= 0 && nz < Compared.SIDE && nl >= 0 && nl < ChunkBlocks.HEIGHT) {
                contact(facts, cells[Compared.index(nx, nl, nz)]);
            }
        }
    }

    /**
     * Counts the fluid contacts across the borders to regions measured before, and keeps the other borders.
     * A border is kept until its neighbour is measured, so a run holds one column of regions and the area's outline.
     */
    void borders(final byte[] cells, final int regionX, final int regionZ) {
        final int[][] towards = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int side = 0; side < 4; side++) {
            final byte[] plane = plane(cells, side);
            final byte[] other =
                    borders.remove(new Border(regionX + towards[side][0], regionZ + towards[side][1], side ^ 1));
            if (other == null) {
                borders.put(new Border(regionX, regionZ, side), plane);
            } else {
                across(plane, other);
            }
        }
    }

    /** Returns the outermost cells towards one side, a row of the other axis per layer. */
    private static byte[] plane(final byte[] cells, final int side) {
        final byte[] plane = new byte[Compared.SIDE * ChunkBlocks.HEIGHT];
        final int edge = side % 2 == 0 ? 0 : Compared.SIDE - 1;
        for (int layer = 0; layer < ChunkBlocks.HEIGHT; layer++) {
            for (int along = 0; along < Compared.SIDE; along++) {
                plane[layer * Compared.SIDE + along] =
                        cells[side < 2 ? Compared.index(edge, layer, along) : Compared.index(along, layer, edge)];
            }
        }
        return plane;
    }

    /** Counts the fluid contacts between the facing outermost cells of two neighbouring regions. */
    void across(final byte[] one, final byte[] other) {
        for (int i = 0; i < one.length; i++) {
            contact(one[i], other[i]);
            contact(other[i], one[i]);
        }
    }

    /** Returns how many fluid contacts with open space of the other world were counted: generated, then map. */
    long[] fluid() {
        return new long[] {fluidMeetsMap, fluidMeetsParallel};
    }

    /** Counts the faces towards one side and those that lie in one plane as a patch of at least the flat size. */
    private static void patches(final byte[] marks, final int side, final long[] sum) {
        final int bit = 1 << side;
        final int[] within = side < 2
                ? new int[] {
                    Compared.SIDE, -Compared.SIDE, Compared.SIDE * Compared.SIDE, -Compared.SIDE * Compared.SIDE
                }
                : side < 4
                        ? new int[] {1, -1, Compared.SIDE * Compared.SIDE, -Compared.SIDE * Compared.SIDE}
                        : new int[] {1, -1, Compared.SIDE, -Compared.SIDE};
        final int[] stack = new int[1 << 16];
        for (int start = 0; start < marks.length; start++) {
            if ((marks[start] & bit) == 0) {
                continue;
            }
            int size = 0;
            int top = 0;
            stack[top++] = start;
            marks[start] &= (byte) ~bit;
            while (top > 0) {
                final int cell = stack[--top];
                size++;
                for (final int step : within) {
                    final int next = cell + step;
                    if (next >= 0 && next < marks.length && (marks[next] & bit) != 0 && top < stack.length) {
                        marks[next] &= (byte) ~bit;
                        stack[top++] = next;
                    }
                }
            }
            sum[0] += size;
            sum[1] += size >= FLAT ? size : 0;
            sum[4] += size >= LARGE ? size : 0;
            if (size > sum[2]) {
                sum[2] = size;
                sum[3] = start;
            }
        }
    }

    private boolean report(final Limits limits) {
        System.out.println("built blocks counted: count=" + built + " changed=" + builtChanged);
        System.out.println("ambiguous blocks counted: count=" + ambiguous + " changed=" + ambiguousChanged);
        System.out.println("fluid at open space of the other world counted: generated=" + fluidMeetsMap + " map="
                + fluidMeetsParallel);
        // Rock that closes a generated cave, rock that ends a cave of the map, and the caves as the game made them.
        final String[] kinds = {"closing", "ending", "generated"};
        for (int kind = 0; kind < 3; kind++) {
            for (int face = 0; face < 3; face++) {
                final long[] sum = faces[kind][face];
                System.out.println(String.format(
                        Locale.ROOT,
                        "faces measured: kind=%s face=%s count=%d flat=%.2f%% flat_size=%d large=%.2f%%"
                                + " large_size=%d largest=%d at=\"%s\"",
                        kinds[kind],
                        FACES[face],
                        sum[0],
                        share(sum, 1) * 100,
                        FLAT,
                        share(sum, 4) * 100,
                        LARGE,
                        sum[2],
                        places[kind][face]));
            }
        }
        final double worst = worst(faces);
        final boolean good =
                builtChanged == 0 && fluidMeetsMap == 0 && fluidMeetsParallel == 0 && worst <= limits.flatShare();
        System.out.println(String.format(
                Locale.ROOT,
                "limits checked: result=%s built_changed=%d fluid=%d flat_ratio=%.2f flat_ratio_limit=%.2f",
                good ? "kept" : "broken",
                builtChanged,
                fluidMeetsMap + fluidMeetsParallel,
                worst,
                limits.flatShare()));
        final List<String> broken = new ArrayList<>();
        if (builtChanged > 0) {
            broken.add("The check failed: " + builtChanged + " built blocks of the map changed, which the transplant"
                    + " never does on purpose. Do not use this result, and report the fault with this log.");
        }
        if (fluidMeetsMap + fluidMeetsParallel > 0) {
            broken.add("The check failed: " + (fluidMeetsMap + fluidMeetsParallel) + " sides of water or lava meet"
                    + " open space of the other world, which the transplant never does on purpose. Do not use this"
                    + " result, and report the fault with this log.");
        }
        if (worst > limits.flatShare()) {
            broken.add(String.format(
                    Locale.ROOT,
                    "The check failed: the new rock is %.2f times as flat as the generated caves, more than %.2f."
                            + " Raise --roughness and run transplant again.",
                    worst,
                    limits.flatShare()));
        }
        broken.forEach(System.out::println);
        return good;
    }

    /**
     * Returns how many times the generated caves' share of flat faces the new rock's floors and ceilings reach at most.
     * It takes the worse of flat and large faces, and of rock that closes a generated cave or ends a cave of the map.
     */
    static double worst(final long[][][] faces) {
        double worst = 0;
        for (final int kind : new int[] {CLOSED, PLUG}) {
            for (int face = 1; face < 3; face++) {
                for (final int size : new int[] {1, 4}) {
                    final double natural = share(faces[NATURAL][face], size);
                    final double closing = share(faces[kind][face], size);
                    worst = Math.max(worst, closing == 0 ? 0 : natural == 0 ? Double.MAX_VALUE : closing / natural);
                }
            }
        }
        return worst;
    }

    private static double share(final long[] sum, final int size) {
        return sum[0] == 0 ? 0 : (double) sum[size] / sum[0];
    }
}
