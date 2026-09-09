package eu.nordtal.s2.smp.aura;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Splitting one objective's pot among the people who worked on it.
 *
 * <p><b>A pot is split, never topped up.</b> 30 % is shared equally among the qualifiers, the rest
 * goes by each contributor's share of the work, qualifying takes 2 % of the target, and every
 * qualifier gets at least one aura. Everything is floored to whole aura and the remainder is not
 * paid.
 *
 * <p>All integer arithmetic, with no {@code double} anywhere: floating point would make the same
 * objective pay differently depending on the order the contributors arrived in. Every floor is in
 * the pot's favour.
 *
 * <p><b>The proportional denominator is the total actually contributed, not the target.</b> Against
 * the target it would overspend the pot the moment an objective finishes with more than was asked
 * for, which is the ordinary {@code HAND_IN} case. The 2 % <em>qualifying</em> threshold is
 * genuinely against the target: it says how much work is worth rewarding, not how the cake is cut.
 *
 * <p>When there are more qualifiers than there is aura, the guarantee is honoured for as many as
 * the pot can pay, in descending order of contribution, ties broken by contributor id so the same
 * inputs always produce the same payout. Dropping the equal part entirely instead would floor
 * almost every proportional share to zero and pay nobody at all.
 */
public final class AuraPayout {

    /** The share of the pot handed out equally among qualifiers, in percent. */
    public static final int EQUAL_PERCENT = 30;

    /** How much of the target a contributor must reach to qualify for the equal part, in percent. */
    public static final int QUALIFYING_PERCENT = 2;

    /** What a qualifier is guaranteed, pot permitting. */
    public static final int MINIMUM_QUALIFIER_SHARE = 1;

    private AuraPayout() {
    }

    /**
     * One player's result.
     *
     * @param contributorId the {@code discord_id} the aura is booked against
     * @param equal         what they got from the equal part; zero for a non-qualifier
     * @param proportional  what they got from the proportional part
     */
    public record Share(String contributorId, int equal, int proportional) {

        public Share {
            Objects.requireNonNull(contributorId, "contributorId");
            if (equal < 0 || proportional < 0) {
                throw new IllegalArgumentException("A share cannot be negative: " + equal + "/" + proportional);
            }
        }

        /** @return what to book into {@code smp_aura_event} for this player */
        public int total() {
            return equal + proportional;
        }

        /** @return whether this player reached the qualifying threshold */
        public boolean qualified() {
            return equal > 0;
        }
    }

    /**
     * Splits a pot.
     *
     * @param pot           the objective's pot. For an admin completion this is already scaled -
     *                      {@code pot × (reached ÷ target)}, see {@link #scaledPot(int, long, long)}
     *                      - because a rescue must neither rob the contributors nor mint aura
     * @param target        the objective's target, which the 2 % qualifying threshold is measured
     *                      against. The <em>original</em> target on an admin completion: what was
     *                      asked for is what a contribution should be judged against, not what it
     *                      was lowered to afterwards
     * @param contributions {@code discord_id} to amount contributed, from {@code smp_contribution}.
     *                      Entries of zero or less are ignored rather than rejected - a row can
     *                      exist at zero
     * @return one share per contributor with a positive contribution, in descending order of total
     *         paid, then by id. Never pays out more than {@code pot} in total
     */
    public static List<Share> split(final int pot, final long target,
                                    final Map<String, Long> contributions) {
        Objects.requireNonNull(contributions, "contributions");
        if (target <= 0) {
            throw new IllegalArgumentException("An objective's target is positive by schema CHECK, was " + target);
        }

        final Map<String, Long> contributors = new LinkedHashMap<>();
        long total = 0L;
        for (final Map.Entry<String, Long> entry : contributions.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                contributors.put(entry.getKey(), entry.getValue());
                total += entry.getValue();
            }
        }
        if (pot <= 0 || contributors.isEmpty()) {
            // A pot of zero is real: `waiting` and `departure` carry none, and an admin completion
            // of an objective nobody touched scales to nothing.
            return List.of();
        }

        final Set<String> qualifiers = qualifiersOf(contributors, target);

        final int equalBudget = pot * EQUAL_PERCENT / 100;
        // The proportional part is the REST of the pot rather than 70 % of it, so that the aura the
        // 30 % lost to its own floor is not lost twice.
        int proportionalBudget = pot - equalBudget;

        final Map<String, Integer> equal = new LinkedHashMap<>();
        if (!qualifiers.isEmpty()) {
            final int perQualifier = equalBudget / qualifiers.size();
            if (perQualifier >= MINIMUM_QUALIFIER_SHARE) {
                for (final String id : qualifiers) {
                    equal.put(id, perQualifier);
                }
                // Whatever the division left over stays in the equal part's share of the pot and is
                // simply not paid. Moving it to the proportional part would make the 30/70 split
                // depend on the number of qualifiers.
            } else if (qualifiers.size() <= pot) {
                // A pot of 30 with twelve qualifiers gives nine aura to split twelve ways, which in
                // whole numbers is nothing. One each, taken from the proportional part.
                for (final String id : qualifiers) {
                    equal.put(id, MINIMUM_QUALIFIER_SHARE);
                }
                proportionalBudget = pot - qualifiers.size();
            } else {
                // More qualifiers than there is aura in the pot: pay the guarantee to as many as
                // it reaches, largest contribution first.
                for (final String id : rankedForTheGuarantee(contributors, qualifiers)) {
                    if (equal.size() >= pot) {
                        break;
                    }
                    equal.put(id, MINIMUM_QUALIFIER_SHARE);
                }
                proportionalBudget = pot - equal.size();
            }
        }

        final List<Share> shares = new java.util.ArrayList<>(contributors.size());
        for (final Map.Entry<String, Long> entry : contributors.entrySet()) {
            // Multiply before dividing: (budget * c) / total, all in long arithmetic. The other
            // order would floor each contributor's fraction to zero before it was worth anything.
            final long proportional = proportionalBudget <= 0
                    ? 0L
                    : (long) proportionalBudget * entry.getValue() / total;
            shares.add(new Share(entry.getKey(), equal.getOrDefault(entry.getKey(), 0),
                    (int) Math.min(Integer.MAX_VALUE, proportional)));
        }

        shares.sort(Comparator.comparingInt(Share::total).reversed()
                .thenComparing(Share::contributorId));
        return List.copyOf(shares);
    }

    /**
     * What an admin completion pays: {@code pot × (reached ÷ target)}, floored.
     *
     * <p>So a rescue neither robs the contributors of the work they did nor mints aura out of
     * nothing.
     *
     * @param pot     the objective's full pot
     * @param reached {@code smp_objective.amount}, the progress actually collected
     * @param target  the original target, which is what the fraction is against
     * @return the pot to hand to {@link #split(int, long, Map)}
     */
    public static int scaledPot(final int pot, final long reached, final long target) {
        if (target <= 0) {
            throw new IllegalArgumentException("An objective's target is positive by schema CHECK, was " + target);
        }
        if (pot <= 0 || reached <= 0) {
            return 0;
        }
        // Capped at the full pot: an objective completed normally has reached >= target, and an
        // admin who completes one that is already over its target must not pay more than it is worth.
        final long scaled = (long) pot * Math.min(reached, target) / target;
        return (int) scaled;
    }

    /**
     * @param contributions the contributors and their amounts
     * @param target        the objective's target
     * @return who reached {@value #QUALIFYING_PERCENT} % of the target, in the order they were given
     */
    public static Set<String> qualifiersOf(final Map<String, Long> contributions, final long target) {
        final Set<String> qualifiers = new java.util.LinkedHashSet<>();
        for (final Map.Entry<String, Long> entry : contributions.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L
                    && entry.getValue() * 100L >= (long) QUALIFYING_PERCENT * target) {
                qualifiers.add(entry.getKey());
            }
        }
        return qualifiers;
    }

    private static List<String> rankedForTheGuarantee(final Map<String, Long> contributions,
                                                      final Set<String> qualifiers) {
        return qualifiers.stream()
                .sorted(Comparator.comparingLong((String id) -> contributions.getOrDefault(id, 0L))
                        .reversed()
                        // Ties broken by id so the same inputs always produce the same payout.
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }
}
