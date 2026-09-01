package eu.nordtal.s2.smp.wheel;

import java.util.List;
import java.util.Random;

/**
 * The weighted draw behind the wheel.
 *
 * <p>The pool and its weights live in {@code config.yml} and are meant to be retuned without a
 * release; what is here is only the arithmetic that turns them into one prize. Three bands are
 * intended - common, uncommon and rare - but nothing in this class knows about bands: a band is
 * simply a group of entries whose weights add up to roughly the share it should have.
 *
 * <p>Pure and given its {@link Random}, so the distribution is asserted rather than hoped for. That
 * matters more than it looks: the wheel is the only reward channel in this design that pays out
 * actual items, so it is the one worth abusing and the one worth getting arithmetically right.
 */
public final class PrizeDraw {

    private PrizeDraw() {
    }

    /**
     * Picks an index into {@code weights}, each with probability proportional to its weight.
     *
     * @throws IllegalArgumentException on an empty pool, which the config validation already
     *                                  refuses - the wheel has to have something to land on
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
        // Only reachable if nextDouble() returned exactly 1.0, which its contract forbids. Falling
        // back to the last positive-weight entry rather than throwing: a wheel that occasionally
        // refuses to spin would be a far stranger bug than a very slightly biased last prize.
        for (int index = weights.size() - 1; index >= 0; index--) {
            if (weights.get(index) != null && weights.get(index) > 0) {
                return index;
            }
        }
        throw new IllegalStateException("unreachable: a positive total with no positive weight");
    }

    /**
     * The thresholds at which contributing to an objective earns extra spins.
     *
     * <p>Staggered by contribution share - one spin at the qualifying threshold, two at the next,
     * three at the highest - and hung off the <em>same</em> percentages as the aura share, so there
     * is one rule to understand and one place to change it. That the biggest contributors collect
     * both the aura and the most spins is accepted: this is the only place in the design where
     * effort compounds, and it compounds into loot rather than into rank.
     *
     * @param percents the configured thresholds, in any order
     * @param share    this contributor's share of the objective, 0-100
     * @return how many extra spins, which is how many thresholds were met
     */
    public static int extraSpinsFor(final List<Integer> percents, final double share) {
        if (percents == null || percents.isEmpty()) {
            return 0;
        }
        int spins = 0;
        for (final Integer percent : percents) {
            if (percent != null && share >= percent) {
                spins++;
            }
        }
        return spins;
    }
}
