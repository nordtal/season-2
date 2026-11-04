package eu.nordtal.season.smp.rail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** A world of rails in memory, built rail by rail, that counts the positions it was asked about. */
final class Track implements RailView {

    private final Map<RailPos, RailShape> rails = new HashMap<>();
    private final Set<RailPos> read = new HashSet<>();
    private Predicate<RailPos> readable = _ -> true;

    static RailPos at(final int x, final int y, final int z) {
        return new RailPos(x, y, z);
    }

    Track rail(final int x, final int y, final int z, final RailShape shape) {
        rails.put(at(x, y, z), shape);
        return this;
    }

    /** Lays flat rails from {@code fromX} to {@code toX} along the x axis. */
    Track eastWest(final int fromX, final int toX, final int y, final int z) {
        for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) {
            rail(x, y, z, RailShape.EAST_WEST);
        }
        return this;
    }

    /** Lays flat rails from {@code fromZ} to {@code toZ} along the z axis. */
    Track northSouth(final int x, final int y, final int fromZ, final int toZ) {
        for (int z = Math.min(fromZ, toZ); z <= Math.max(fromZ, toZ); z++) {
            rail(x, y, z, RailShape.NORTH_SOUTH);
        }
        return this;
    }

    Track remove(final int x, final int y, final int z) {
        rails.remove(at(x, y, z));
        return this;
    }

    Track readableWhere(final Predicate<RailPos> condition) {
        this.readable = condition;
        return this;
    }

    Map<RailPos, RailShape> rails() {
        return Map.copyOf(rails);
    }

    boolean wasRead(final RailPos pos) {
        return read.contains(pos);
    }

    /** Returns how many positions were read since the last call, and starts counting anew. */
    int readSinceLast() {
        final int count = read.size();
        read.clear();
        return count;
    }

    @Override
    public boolean readable(final RailPos pos) {
        return readable.test(pos);
    }

    @Override
    public @Nullable RailShape shape(final RailPos pos) {
        read.add(pos);
        return rails.get(pos);
    }
}
