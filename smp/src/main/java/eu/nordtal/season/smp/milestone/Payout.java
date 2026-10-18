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
 * Splits the aura or spin budget of an objective among its contributors.
 * The arithmetic is integer only, so the order of the contributions does not change a share.
 */
public final class Payout {

    /** The part of the budget split equally among the qualifiers, in percent. */
    public static final int EQUAL_PERCENT = 30;

    /** The part of the target a contributor must reach to qualify, in percent. */
    public static final int QUALIFYING_PERCENT = 2;

    /** The least that a qualifier receives from the equal part. */
    public static final int MINIMUM_QUALIFIER_SHARE = 1;

    /** The least that every contributor of a completed objective receives while the full budget lasts. */
    public static final int MINIMUM_SHARE = 1;

    private Payout() {}

    /**
     * Splits the {@link #scaled} budget, raised to {@link #MINIMUM_SHARE} per contributor and capped at {@code budget}.
     * A contributor the split leaves below the minimum takes it from the largest share.
     *
     * @param contributions the amount per {@code discord_id}, entries of zero or less are ignored
     * @return one share per positive contribution, sorted by total descending, then by id; empty for a budget of 0
     */
    public static List<Share> paid(
            final int budget, final long reached, final long target, final Map<String, Long> contributions) {
        final Map<String, Long> positive = new LinkedHashMap<>();
        contributions.forEach((id, amount) -> {
            if (id != null && amount != null && amount > 0L) {
                positive.put(id, amount);
            }
        });
        final long minimums = (long) positive.size() * MINIMUM_SHARE;
        final int total = (int) Math.min(budget, Math.max(scaled(budget, reached, target), minimums));
        final Map<String, Share> shares = new LinkedHashMap<>();
        split(total, target, positive).forEach(share -> shares.put(share.contributorId(), share));
        for (final String id : ranked(positive, positive.keySet())) {
            while (shares.containsKey(id) && shares.get(id).total() < MINIMUM_SHARE) {
                final Share donor = sorted(shares.values()).get(0);
                if (donor.total() <= MINIMUM_SHARE) {
                    break;
                }
                shares.put(
                        donor.contributorId(),
                        donor.proportional() > 0
                                ? new Share(donor.contributorId(), donor.equal(), donor.proportional() - 1)
                                : new Share(donor.contributorId(), donor.equal() - 1, 0));
                final Share share = shares.get(id);
                shares.put(id, new Share(id, share.equal(), share.proportional() + 1));
            }
        }
        return sorted(shares.values());
    }

    private static List<Share> sorted(final java.util.Collection<Share> shares) {
        return shares.stream()
                .sorted(Comparator.comparingInt(Share::total).reversed().thenComparing(Share::contributorId))
                .toList();
    }

    /**
     * The payout to one contributor.
     *
     * @param contributorId the {@code discord_id} of the contributor
     * @param equal the amount from the equal part, zero for a contributor who did not qualify
     * @param proportional the amount from the proportional part
     */
    public record Share(String contributorId, int equal, int proportional) {

        public Share {
            Objects.requireNonNull(contributorId, "contributorId");
            if (equal < 0 || proportional < 0) {
                throw new IllegalArgumentException("A share cannot be negative: " + equal + "/" + proportional);
            }
        }

        public int total() {
            return equal + proportional;
        }

        public boolean qualified() {
            return equal > 0;
        }
    }

    /**
     * Splits {@code budget} into the equal part and the proportional part.
     * The shares add up to {@code budget} when at least one contribution is positive.
     *
     * @param target the target that {@link #QUALIFYING_PERCENT} refers to
     * @param contributions the amount per {@code discord_id}, entries of zero or less are ignored
     * @return one share per positive contribution, sorted by total descending, then by id
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
     * Splits the equal part among the qualifiers and leaves the rest of the budget to the proportional part.
     * When the part is too small for {@link #MINIMUM_QUALIFIER_SHARE} each, it pays the largest contributors first.
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
            for (final String id : ranked(contributors, qualifiers)) {
                if (equal.size() >= budget) {
                    break;
                }
                equal.put(id, MINIMUM_QUALIFIER_SHARE);
            }
        }
        return equal;
    }

    /** Splits {@code rest} by the share of {@code total}, rounds down and adds 1 to the largest remainders. */
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
        for (int index = 0; index < left; index++) {
            shares.merge(byRemainder.get(index), 1, Integer::sum);
        }
        return shares;
    }

    /**
     * Returns the total that {@link #paid} gives {@code contributorId}, or zero for a {@code target} of zero or less.
     *
     * @param contributions the amount per {@code discord_id}
     */
    public static int shareOf(
            final int budget,
            final long reached,
            final long target,
            final Map<String, Long> contributions,
            final String contributorId) {
        if (target <= 0) {
            return 0;
        }
        return paid(budget, reached, target, contributions).stream()
                .filter(share -> share.contributorId().equals(contributorId))
                .mapToInt(Share::total)
                .findFirst()
                .orElse(0);
    }

    /**
     * Returns {@code budget × reached ÷ target}, rounded down, with {@code reached} capped at {@code target}.
     *
     * @param reached the progress, {@code smp_objective.amount}
     * @throws IllegalArgumentException when {@code target} is zero or less
     */
    public static int scaled(final int budget, final long reached, final long target) {
        if (target <= 0) {
            throw new IllegalArgumentException("An objective's target is positive by schema CHECK, was " + target);
        }
        if (budget <= 0 || reached <= 0) {
            return 0;
        }
        return (int) ((long) budget * Math.min(reached, target) / target);
    }

    /** Returns the contributors with at least {@value #QUALIFYING_PERCENT} % of {@code target}, in the given order. */
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
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }
}
