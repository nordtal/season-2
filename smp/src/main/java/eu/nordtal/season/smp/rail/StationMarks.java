package eu.nordtal.season.smp.rail;

import java.util.Arrays;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** The marker of a station rail, kept with its chunk, so a block is known as a station before the store answers. */
final class StationMarks {

    private final NamespacedKey key;

    StationMarks(final Plugin plugin) {
        this.key = new NamespacedKey(plugin, "station_rails");
    }

    boolean marked(final Block block) {
        final long[] marks = marks(block.getChunk().getPersistentDataContainer());
        return Arrays.stream(marks).anyMatch(mark -> mark == packed(block));
    }

    void mark(final Block block) {
        if (marked(block)) {
            return;
        }
        final PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        final long[] marks = marks(data);
        final long[] more = Arrays.copyOf(marks, marks.length + 1);
        more[marks.length] = packed(block);
        data.set(key, PersistentDataType.LONG_ARRAY, more);
    }

    void unmark(final Block block) {
        final PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        final long[] left =
                Arrays.stream(marks(data)).filter(mark -> mark != packed(block)).toArray();
        if (left.length == 0) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.LONG_ARRAY, left);
        }
    }

    /** Returns where the station rails marked in {@code chunk} stand. */
    List<RailPos> positions(final Chunk chunk) {
        final int baseX = chunk.getX() << 4;
        final int baseZ = chunk.getZ() << 4;
        return Arrays.stream(marks(chunk.getPersistentDataContainer()))
                .mapToObj(mark ->
                        new RailPos(baseX + (int) (mark & 15), (int) (mark >> 8), baseZ + (int) ((mark >> 4) & 15)))
                .toList();
    }

    private long[] marks(final PersistentDataContainer data) {
        final long[] marks = data.get(key, PersistentDataType.LONG_ARRAY);
        return marks == null ? new long[0] : marks;
    }

    /** Packs the block's place within its chunk: x and z in the low byte, the height above. */
    private static long packed(final Block block) {
        return (block.getX() & 15) | ((long) (block.getZ() & 15) << 4) | ((long) block.getY() << 8);
    }
}
