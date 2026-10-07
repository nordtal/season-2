package eu.nordtal.season.smp.milestone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Splits one budget of an objective, its aura or its spins, among the people who worked on it, exactly.
 *
 * Integer arithmetic only, so arrival order never changes a payout; what the floors leave goes by largest remainder.
 */
public final class Payout {

    /** The share of the budget handed out equally among qualifiers, in percent. */
    public static final int EQUAL_PERCENT = 30;

    /** How much of the target a contributor must reach to qualify for the equal part, in percent. */
    public static final int QUALIFYING_PERCENT = 2;

    /** What a qualifier is guaranteed, budget permitting. */
    public static final int MINIMUM_QUALIFIER_SHARE = 1;

    private Payout() {}

    /**
     * One player's result.
     *
     * @param contributorId the {@code discord_id} the payout is booked against
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

        /** Returns what to book for this player. */
        public int total() {
            return equal + proportional;
        }

        /** Returns whether this player reached the qualifying threshold. */
        public boolean qualified() {
            return equal > 0;
        }
    }

    /**
     * Splits a budget: 30 % equally among qualifiers, the rest by share of the total actually contributed.
     * The shares add up to {@code budget} whenever anybody contributed, so a player alone gets all of it.
     *
     * @param budget the objective's budget, already scaled by {@link #scaled(int, long, long)} on an admin completion
     * @param target the objective's original target, which the 2 % qualifying threshold is measured against
     * @param contributions {@code discord_id} to amount contributed; entries of zero or less are ignored
     * @return one share per positive contributor, by total paid descending, then by id
     */
    public static List<Share> split(final int budget, final long target, final Map<String, Long> contributions) {
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
        if (budget <= 0 || contributors.isEmpty()) {
            // A budget of zero is real: an admin completion nobody touched scales to nothing.
            return List.of();
        }

        final Map<String, Integer> equal = equalShares(budget, contributors, qualifiersOf(contributors, target));
        final int rest =
                budget - equal.values().stream().mapToInt(Integer::intValue).sum();
        final Map<String, Integer> proportional = proportionalShares(rest, contributors, total);

        final List<Share> shares = new ArrayList<>(contributors.size());
        for (final String id : contributors.keySet()) {
            shares.add(new Share(id, equal.getOrDefault(id, 0), proportional.getOrDefault(id, 0)));
        }
        shares.sort(Comparator.comparingInt(Share::total).reversed().thenComparing(Share::contributorId));
        return List.copyOf(shares);
    }

    /**
     * Splits the equal part; whatever it does not hand out stays for the proportional part.
     *
     * When the budget cannot pay every qualifier's guarantee, it pays as many as it can, largest contribution first.
     */
    private static Map<String, Integer> equalShares(
            final int budget, final Map<String, Long> contributors, final Set<String> qualifiers) {
        final Map<String, Integer> equal = new LinkedHashMap<>();
        if (qualifiers.isEmpty()) {
            return equal;
        }
        final int perQualifier = budget * EQUAL_PERCENT / 100 / qualifiers.size();
        if (perQualifier >= MINIMUM_QUALIFIER_SHARE) {
            qualifiers.forEach(id -> equal.put(id, perQualifier));
        } else {
            // Thirty on twelve qualifiers gives nine to split twelve ways, which is nothing whole.
            for (final String id : ranked(contributors, qualifiers)) {
                if (equal.size() >= budget) {
                    break;
                }
                equal.put(id, MINIMUM_QUALIFIER_SHARE);
            }
        }
        return equal;
    }

    /** Splits {@code rest} by share of {@code total}: each its floor, then one more for the largest remainders. */
    private static Map<String, Integer> proportionalShares(
            final int rest, final Map<String, Long> contributors, final long total) {
        final Map<String, Integer> shares = new LinkedHashMap<>();
        final Map<String, Long> remainders = new LinkedHashMap<>();
        int left = rest;
        for (final Map.Entry<String, Long> entry : contributors.entrySet()) {
            final long weighted = Math.multiplyExact((long) rest, entry.getValue());
            final int floor = (int) (weighted / total);
            shares.put(entry.getKey(), floor);
            remainders.put(entry.getKey(), weighted % total);
            left -= floor;
        }
        final List<String> byRemainder = contributors.keySet().stream()
                .sorted(Comparator.comparingLong((String id) -> remainders.get(id))
                        .reversed()
                        .thenComparing(Comparator.comparingLong((String id) -> contributors.get(id))
                                .reversed())
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        // Fewer units are left than there are contributors, since each floor lost less than one.
        for (int index = 0; index < left; index++) {
            shares.merge(byRemainder.get(index), 1, Integer::sum);
        }
        return shares;
    }

    /**
     * Returns what {@code contributorId} gets of {@code budget} if the objective completed now; zero when nothing.
     *
     * @param budget the objective's budget
     * @param target the objective's target; a target of zero or less answers zero
     * @param contributions everybody's contribution so far, {@code discord_id} to amount
     * @param contributorId whose share to answer
     */
    public static int shareOf(
            final int budget, final long target, final Map<String, Long> contributions, final String contributorId) {
        if (target <= 0) {
            return 0;
        }
        return split(budget, target, contributions).stream()
                .filter(share -> share.contributorId().equals(contributorId))
                .mapToInt(Share::total)
                .findFirst()
                .orElse(0);
    }

    /**
     * What an admin completion pays: {@code budget × (reached ÷ target)}, floored, so a rescue neither robs nor mints.
     *
     * @param budget the objective's full budget
     * @param reached {@code smp_objective.amount}, the progress actually collected
     * @param target the original target
     * @return the budget to hand to {@link #split(int, long, Map)}
     */
    public static int scaled(final int budget, final long reached, final long target) {
        if (target <= 0) {
            throw new IllegalArgumentException("An objective's target is positive by schema CHECK, was " + target);
        }
        if (budget <= 0 || reached <= 0) {
            return 0;
        }
        // Capped at the full budget, for an admin completing an over-target objective.
        return (int) ((long) budget * Math.min(reached, target) / target);
    }

    /**
     * Returns who reached {@value #QUALIFYING_PERCENT} % of the target, in the order they were given.
     *
     * @param contributions the contributors and their amounts
     * @param target the objective's target
     */
    public static Set<String> qualifiersOf(final Map<String, Long> contributions, final long target) {
        final Set<String> qualifiers = new LinkedHashSet<>();
        for (final Map.Entry<String, Long> entry : contributions.entrySet()) {
            if (entry.getValue() != null
                    && entry.getValue() > 0L
                    && entry.getValue() * 100L >= (long) QUALIFYING_PERCENT * target) {
                qualifiers.add(entry.getKey());
            }
        }
        return qualifiers;
    }

    private static List<String> ranked(final Map<String, Long> contributions, final Set<String> qualifiers) {
        return qualifiers.stream()
                .sorted(Comparator.comparingLong((String id) -> contributions.getOrDefault(id, 0L))
                        .reversed()
                        // Ties broken by id so the same inputs always produce the same payout.
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }
}
