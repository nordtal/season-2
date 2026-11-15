package eu.nordtal.season.underground.structures;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Puts the pieces of a place under starts at the candidate chunks of a spread, so that /locate finds them.
 * The game reads a start only from chunks within eight chunks of it, so a wide place gets several.
 */
public final class Reach {

    /** How many chunks from its start a chunk may lie and still find it. */
    public static final int CHUNKS = 8;

    private final Spread spread;
    private final long seed;
    private final Finds finds;

    public Reach(final Spread spread, final long seed, final Finds finds) {
        this.spread = spread;
        this.seed = seed;
        this.finds = finds;
    }

    /** A candidate chunk with the pieces it can hold. */
    public record Choice(int chunkX, int chunkZ, List<Box> pieces) {}

    /** Splits a box into one piece per chunk column it touches, each of which a candidate can always reach. */
    public static List<Box> columns(final Box box) {
        final List<Box> pieces = new ArrayList<>();
        for (int chunkX = box.minChunkX(); chunkX <= box.maxChunkX(); chunkX++) {
            for (int chunkZ = box.minChunkZ(); chunkZ <= box.maxChunkZ(); chunkZ++) {
                pieces.add(new Box(
                        Math.max(box.minX(), chunkX * 16),
                        box.minY(),
                        Math.max(box.minZ(), chunkZ * 16),
                        Math.min(box.maxX(), chunkX * 16 + 15),
                        box.maxY(),
                        Math.min(box.maxZ(), chunkZ * 16 + 15)));
            }
        }
        return pieces;
    }

    /** Returns whether a start in this chunk reaches every chunk of a piece. */
    public static boolean reaches(final int chunkX, final int chunkZ, final Box piece) {
        return piece.minChunkX() >= chunkX - CHUNKS
                && piece.maxChunkX() <= chunkX + CHUNKS
                && piece.minChunkZ() >= chunkZ - CHUNKS
                && piece.maxChunkZ() <= chunkZ + CHUNKS;
    }

    /**
     * Chooses as few candidates as it can, each a full chunk, that together reach every piece.
     * Of two that reach as many, it takes the one nearer the middle; a piece no candidate reaches is left out.
     */
    public List<Choice> cover(final List<Box> pieces) {
        final List<Box> open = new ArrayList<>(pieces);
        final List<Choice> choices = new ArrayList<>();
        while (!open.isEmpty()) {
            final Choice best = best(open);
            if (best == null) {
                break;
            }
            choices.add(best);
            open.removeAll(best.pieces());
        }
        return choices;
    }

    private @Nullable Choice best(final List<Box> open) {
        Box around = open.getFirst();
        for (final Box piece : open) {
            around = around.union(piece);
        }
        final int middleX = (around.minChunkX() + around.maxChunkX()) / 2;
        final int middleZ = (around.minChunkZ() + around.maxChunkZ()) / 2;
        Choice best = null;
        long bestDistance = Long.MAX_VALUE;
        final int fromX = Math.floorDiv(around.minChunkX() - CHUNKS, spread.spacing());
        final int toX = Math.floorDiv(around.maxChunkX() + CHUNKS, spread.spacing());
        final int fromZ = Math.floorDiv(around.minChunkZ() - CHUNKS, spread.spacing());
        final int toZ = Math.floorDiv(around.maxChunkZ() + CHUNKS, spread.spacing());
        for (int cellX = fromX; cellX <= toX; cellX++) {
            for (int cellZ = fromZ; cellZ <= toZ; cellZ++) {
                final int[] at = spread.candidate(seed, cellX, cellZ);
                if (!finds.full(at[0], at[1])) {
                    continue;
                }
                final List<Box> reached = new ArrayList<>();
                for (final Box piece : open) {
                    if (reaches(at[0], at[1], piece)) {
                        reached.add(piece);
                    }
                }
                final long distance =
                        (long) (at[0] - middleX) * (at[0] - middleX) + (long) (at[1] - middleZ) * (at[1] - middleZ);
                if (!reached.isEmpty()
                        && (best == null
                                || reached.size() > best.pieces().size()
                                || (reached.size() == best.pieces().size() && distance < bestDistance))) {
                    best = new Choice(at[0], at[1], reached);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }
}
