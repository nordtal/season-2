package eu.nordtal.season.underground.check;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.block.Blocks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts every block of a world, in all and well below the ground, and every structure start. */
public final class Histogram {

    /** How far below the top of its column a block counts as deep. */
    private static final int DEEP = 12;

    private final Blocks blocks = Blocks.load();
    private final Map<String, long[]> counts = new TreeMap<>();
    private final Map<String, Long> starts = new TreeMap<>();
    private final Map<Integer, Long> versions = new TreeMap<>();

    /** Counts the regions of a world in parallel and returns the report as text. */
    public static String of(final Path world, final List<int[]> regions) {
        final Histogram total = new Histogram();
        regions.parallelStream().forEach(region -> {
            final Histogram one = new Histogram();
            try {
                one.count(Region.read(Region.file(world.resolve("region"), region[0], region[1])));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            synchronized (total) {
                total.add(one);
            }
        });
        return total.report();
    }

    private void count(final Map<Integer, Map<String, Object>> region) {
        for (final Map<String, Object> chunk : region.values()) {
            versions.merge(Nbt.number(chunk, "DataVersion", 0), 1L, Long::sum);
            final Map<String, Object> structures = Nbt.child(chunk, "structures");
            final Map<String, Object> chunkStarts = structures == null ? null : Nbt.child(structures, "starts");
            if (chunkStarts != null) {
                chunkStarts.keySet().forEach(id -> starts.merge(id, 1L, Long::sum));
            }
            if (ChunkBlocks.full(chunk)) {
                count(new ChunkBlocks(chunk));
            }
        }
    }

    private void count(final ChunkBlocks chunk) {
        final int[] top = new int[256];
        for (int column = 0; column < 256; column++) {
            int y = ChunkBlocks.MIN_Y + ChunkBlocks.HEIGHT - 1;
            while (y > ChunkBlocks.MIN_Y && open(chunk.entry(column & 15, y, column >> 4))) {
                y--;
            }
            top[column] = y;
        }
        for (int section = 0; section < ChunkBlocks.HEIGHT / 16; section++) {
            final Object[] palette = chunk.palette(section);
            final int[] indices = chunk.indices(section);
            if (palette == null || indices == null) {
                continue;
            }
            final long[][] perEntry = new long[palette.length][];
            for (int i = 0; i < Section.BLOCKS; i++) {
                final int y = ChunkBlocks.MIN_Y + section * 16 + (i >> 8);
                long[] entry = perEntry[indices[i]];
                if (entry == null) {
                    entry = new long[] {0, 0, y, y};
                    perEntry[indices[i]] = entry;
                }
                entry[0]++;
                entry[1] += y < top[i & 255] - DEEP ? 1 : 0;
                entry[3] = y;
            }
            for (int i = 0; i < palette.length; i++) {
                if (perEntry[i] != null) {
                    merge(Section.name(palette[i]), perEntry[i]);
                }
            }
        }
    }

    private boolean open(final Object entry) {
        return Blocks.open(blocks.mapCell(Section.name(entry)));
    }

    private void merge(final String name, final long[] more) {
        final long[] sum = counts.computeIfAbsent(name, _ -> new long[] {0, 0, more[2], more[3]});
        sum[0] += more[0];
        sum[1] += more[1];
        sum[2] = Math.min(sum[2], more[2]);
        sum[3] = Math.max(sum[3], more[3]);
    }

    private void add(final Histogram other) {
        other.counts.forEach(this::merge);
        other.starts.forEach((id, count) -> starts.merge(id, count, Long::sum));
        other.versions.forEach((version, count) -> versions.merge(version, count, Long::sum));
    }

    private String report() {
        final StringBuilder text = new StringBuilder("block\tall\tdeep\tlowest\thighest\n");
        counts.forEach((name, count) -> text.append(name)
                .append('\t')
                .append(count[0])
                .append('\t')
                .append(count[1])
                .append('\t')
                .append(count[2])
                .append('\t')
                .append(count[3])
                .append('\n'));
        text.append("\nstructure start\tchunks\n");
        starts.forEach((id, count) -> text.append(id).append('\t').append(count).append('\n'));
        text.append("\ndata version\tchunks\n");
        versions.forEach((version, count) ->
                text.append(version).append('\t').append(count).append('\n'));
        return text.toString();
    }
}
