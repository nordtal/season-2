package eu.nordtal.season.smp.aura;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Splits one objective's pot among the people who worked on it, never paying more than the pot.
 *
 * Integer arithmetic only, so arrival order never changes a payout; every floor favours the pot.
 */
public final class AuraPayout {

    /** The share of the pot handed out equally among qualifiers, in percent. */
    public static final int EQUAL_PERCENT = 30;

    /** How much of the target a contributor must reach to qualify for the equal part, in percent. */
    public static final int QUALIFYING_PERCENT = 2;

    /** What a qualifier is guaranteed, pot permitting. */
    public static final int MINIMUM_QUALIFIER_SHARE = 1;

    private AuraPayout() {}

    /**
     * One player's result.
     *
     * @param contributorId the {@code discord_id} the aura is booked against
     * @param equal what they got from the equal part; zero for a non-qualifier
     * @param proportional what they got from the proportional part
     */
    public record Share(String contributorId, int equal, int proportional) {

        public Share {
            Objects.requireNonNull(contributorId, "contributorId");
            if (equal < 0 || proportional < 0) {
                throw new IllegalArgumentException("A share cannot be negative: " + equal + "/" + proportional);
            }
        }

        /** Returns what to book into {@code smp_aura_event} for this player. */
        public int total() {
            return equal + proportional;
        }

        /** Returns whether this player reached the qualifying threshold. */
        public boolean qualified() {
            return equal > 0;
        }
    }

    /**
     * Splits a pot: 30 % equally among qualifiers, the rest by share of the total actually contributed.
     *
     * @param pot the objective's pot, already scaled by {@link #scaledPot(int, long, long)} on an admin completion
     * @param target the objective's original target, which the 2 % qualifying threshold is measured against
     * @param contributions {@code discord_id} to amount contributed; entries of zero or less are ignored
     * @return one share per positive contributor, by total paid descending, then by id
     */
    public static List<Share> split(final int pot, final long target, final Map<String, Long> contributions) {
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
            // A pot of zero is real: an admin completion nobody touched scales to nothing.
            return List.of();
        }

        final Set<String> qualifiers = qualifiersOf(contributors, target);

        final EqualSplit equalSplit = equalShares(pot, contributors, qualifiers);
        final Map<String, Integer> equal = equalSplit.equal();
        final int proportionalBudget = equalSplit.proportionalBudget();

        final List<Share> shares = new java.util.ArrayList<>(contributors.size());
        for (final Map.Entry<String, Long> entry : contributors.entrySet()) {
            // Multiply before dividing, in long arithmetic.
            final long proportional =
                    proportionalBudget <= 0 ? 0L : (long) proportionalBudget * entry.getValue() / total;
            shares.add(new Share(entry.getKey(), equal.getOrDefault(entry.getKey(), 0), (int)
                    Math.min(Integer.MAX_VALUE, proportional)));
        }

        shares.sort(Comparator.comparingInt(Share::total).reversed().thenComparing(Share::contributorId));
        return List.copyOf(shares);
    }

    private record EqualSplit(Map<String, Integer> equal, int proportionalBudget) {}

    /**
     * Splits the equal part, leaving the rest of the pot rather than a fixed 70 % for the proportional part.
     *
     * When the pot cannot pay every qualifier's guarantee, it pays as many as it can, largest contribution first.
     */
    private static EqualSplit equalShares(
            final int pot, final Map<String, Long> contributors, final Set<String> qualifiers) {
        final int equalBudget = pot * EQUAL_PERCENT / 100;
        int proportionalBudget = pot - equalBudget;
        final Map<String, Integer> equal = new LinkedHashMap<>();
        if (qualifiers.isEmpty()) {
            return new EqualSplit(equal, proportionalBudget);
        }

        final int perQualifier = equalBudget / qualifiers.size();
        if (perQualifier >= MINIMUM_QUALIFIER_SHARE) {
            for (final String id : qualifiers) {
                equal.put(id, perQualifier);
            }
        } else if (qualifiers.size() <= pot) {
            // A pot of 30 with twelve qualifiers gives nine to split twelve ways, which is nothing whole.
            for (final String id : qualifiers) {
                equal.put(id, MINIMUM_QUALIFIER_SHARE);
            }
            proportionalBudget = pot - qualifiers.size();
        } else {
            for (final String id : rankedForTheGuarantee(contributors, qualifiers)) {
                if (equal.size() >= pot) {
                    break;
                }
                equal.put(id, MINIMUM_QUALIFIER_SHARE);
            }
            proportionalBudget = pot - equal.size();
        }
        return new EqualSplit(equal, proportionalBudget);
    }

    /**
     * What an admin completion pays: {@code pot × (reached ÷ target)}, floored, so a rescue neither robs nor mints.
     *
     * @param pot the objective's full pot
     * @param reached {@code smp_objective.amount}, the progress actually collected
     * @param target the original target
     * @return the pot to hand to {@link #split(int, long, Map)}
     */
    public static int scaledPot(final int pot, final long reached, final long target) {
        if (target <= 0) {
            throw new IllegalArgumentException("An objective's target is positive by schema CHECK, was " + target);
        }
        if (pot <= 0 || reached <= 0) {
            return 0;
        }
        // Capped at the full pot, for an admin completing an over-target objective.
        final long scaled = (long) pot * Math.min(reached, target) / target;
        return (int) scaled;
    }

    /**
     * Returns who reached {@value #QUALIFYING_PERCENT} % of the target, in the order they were given.
     *
     * @param contributions the contributors and their amounts
     * @param target the objective's target
     */
    public static Set<String> qualifiersOf(final Map<String, Long> contributions, final long target) {
        final Set<String> qualifiers = new java.util.LinkedHashSet<>();
        for (final Map.Entry<String, Long> entry : contributions.entrySet()) {
            if (entry.getValue() != null
                    && entry.getValue() > 0L
                    && entry.getValue() * 100L >= (long) QUALIFYING_PERCENT * target) {
                qualifiers.add(entry.getKey());
            }
        }
        return qualifiers;
    }

    private static List<String> rankedForTheGuarantee(
            final Map<String, Long> contributions, final Set<String> qualifiers) {
        return qualifiers.stream()
                .sorted(Comparator.comparingLong((String id) -> contributions.getOrDefault(id, 0L))
                        .reversed()
                        // Ties broken by id so the same inputs always produce the same payout.
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }
}
