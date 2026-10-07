package eu.nordtal.season.smp.wheel;

import java.util.List;
import java.util.Random;

/** The weighted draw behind the wheel, pure and given its {@link Random} so the distribution can be asserted. */
public final class PrizeDraw {

    private PrizeDraw() {}

    /**
     * Picks an index into {@code weights}, each with probability proportional to its weight.
     *
     * @throws IllegalArgumentException on an empty pool, which config validation already refuses
     */
    public static int draw(final List<Integer> weights, final Random random) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("the wheel has nothing to land on");
        }
        long total = 0;
        for (final Integer weight : weights) {
            total += Math.max(0, weight == null ? 0 : weight);
        }
        if (total <= 0) {
            throw new IllegalArgumentException("every prize in the pool has weight zero");
        }

        long roll = (long) Math.floor(random.nextDouble() * total);
        for (int index = 0; index < weights.size(); index++) {
            final int weight = Math.max(0, weights.get(index) == null ? 0 : weights.get(index));
            if (roll < weight) {
                return index;
            }
            roll -= weight;
        }
        // Only reachable if nextDouble() returned exactly 1.0, which its contract forbids.
        for (int index = weights.size() - 1; index >= 0; index--) {
            if (weights.get(index) != null && weights.get(index) > 0) {
                return index;
            }
        }
        throw new IllegalStateException("unreachable: a positive total with no positive weight");
    }
}
