package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The contribution payout: a budget is paid out exactly, and the same inputs always produce the same payout. */
class PayoutTest {

    @Test
    void twoEqualContributorsSplitEverything() {
        final List<Payout.Share> shares = Payout.split(100, 1000, contributions("a", 500, "b", 500));

        assertEquals(2, shares.size());
        // 30 % equally: 15 each. 70 % by share: 35 each.
        assertEquals(15, shares.get(0).equal());
        assertEquals(35, shares.get(0).proportional());
        assertEquals(50, total(shares) / 2);
        assertEquals(100, total(shares));
    }

    @Test
    void aContributorBelowTwoPercentGetsTheProportionalShareOnly() {
        // Below the threshold a contributor gets only their proportional share, not hundreds for a token contribution.
        final List<Payout.Share> shares = Payout.split(100, 1000, contributions("a", 990, "b", 10));

        final Payout.Share small = byId(shares, "b");
        assertFalse(small.qualified());
        assertEquals(0, small.equal());
        assertTrue(byId(shares, "a").qualified());
    }

    @Test
    void exactlyOnTheThresholdQualifies() {
        // 2 % of 1000 is 20. ">=" and ">" differ by one player's entire equal share.
        assertTrue(byId(Payout.split(100, 1000, contributions("a", 980, "b", 20)), "b")
                .qualified());
        assertFalse(byId(Payout.split(100, 1000, contributions("a", 981, "b", 19)), "b")
                .qualified());
    }

    @Test
    void theWorkedExampleFromTheConcept() {
        // Nine of thirty on twelve qualifiers floors to zero each; the guarantee exists to prevent that.
        final Map<String, Long> twelve = new LinkedHashMap<>();
        for (int index = 0; index < 12; index++) {
            twelve.put("p" + index, 100L);
        }

        final List<Payout.Share> shares = Payout.split(30, 1000, twelve);

        assertEquals(12, shares.size());
        for (final Payout.Share share : shares) {
            assertEquals(1, share.equal(), "every qualifier is guaranteed one aura");
        }
        // The equal part grew from 9 to 12, so 18 is left to divide proportionally: 1 each, and 6 to the remainders.
        assertEquals(30, total(shares), "paid " + total(shares) + " out of a budget of 30");
        assertEquals(
                6, shares.stream().filter(share -> share.proportional() == 2).count());
    }

    @Test
    void theWholeBudgetIsPaidOutAndNeverMore() {
        for (final int budget : new int[] {1, 2, 7, 10, 20, 30, 60, 80, 110, 170, 1000}) {
            for (final int contributors : new int[] {1, 2, 3, 12, 40, 100}) {
                final Map<String, Long> map = new LinkedHashMap<>();
                for (int index = 0; index < contributors; index++) {
                    map.put("p" + index, (long) (index + 1) * 7);
                }

                final int paid = total(Payout.split(budget, 1000, map));

                assertEquals(budget, paid, "budget " + budget + " with " + contributors + " contributors");
            }
        }
    }

    @Test
    void aPlayerWhoDidEverythingAloneGetsEverything() {
        assertEquals(170, total(Payout.split(170, 8192, Map.of("a", 9000L))), "a qualifier alone");
        assertEquals(20, total(Payout.split(20, 10, Map.of("a", 1L))), "a gate's one holder");
        assertEquals(50, total(Payout.split(50, 1000, Map.of("a", 10L))), "alone, even below the threshold");
    }

    @Test
    void whatTheFloorsLeaveGoesToTheLargestRemainders() {
        // Ten among 50, 30 and 20: one each equally, then 7 by share is 3.5, 2.1 and 1.4, so the last unit is a's.
        final List<Payout.Share> shares = Payout.split(10, 100, contributions("a", 50, "b", 30, "c", 20));

        assertEquals(5, byId(shares, "a").total());
        assertEquals(3, byId(shares, "b").total());
        assertEquals(2, byId(shares, "c").total());
    }

    @Test
    void tenEqualPlayersOnTwentySpinsGetTwoEach() {
        // The spin budget of a milestone whose gate is ten players, as the default track derives it.
        final Map<String, Long> ten = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            ten.put("p" + index, 100L);
        }

        for (final Payout.Share share : Payout.split(20, 1000, ten)) {
            assertEquals(2, share.total(), share.contributorId());
        }
    }

    @Test
    void moreQualifiersThanThereIsAuraPaysTheBiggestContributorsFirst() {
        // Forty qualifiers on a budget of thirty cannot all be guaranteed; the budget reaches the largest first.
        final Map<String, Long> forty = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            forty.put(String.format("p%02d", index), (long) (index + 1) * 100);
        }

        final List<Payout.Share> shares = Payout.split(30, 100, forty);

        assertEquals(
                30,
                shares.stream().filter(Payout.Share::qualified).count(),
                "the guarantee reaches exactly as far as the budget");
        assertTrue(byId(shares, "p39").qualified(), "the largest contributor is paid");
        assertFalse(byId(shares, "p00").qualified(), "the smallest is not");
        assertEquals(30, total(shares));
    }

    @Test
    void anAdvancementObjectiveSplitsEvenly() {
        // A share of 1 or 0 divides the proportional part equally; everybody who earned it qualifies, no exceptions.
        final Map<String, Long> ten = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            ten.put("p" + index, 1L);
        }

        final List<Payout.Share> shares = Payout.split(80, 8, ten);

        assertEquals(10, shares.size());
        assertEquals(
                10,
                shares.stream().filter(Payout.Share::qualified).count(),
                "the two players beyond the target of 8 are paid like the rest");
        final int each = shares.get(0).total();
        for (final Payout.Share share : shares) {
            assertEquals(each, share.total(), "an ADVANCEMENT objective pays everybody the same");
        }
    }

    @Test
    void anAdminCompletionPaysProportionallyToWhatWasActuallyReached() {
        // An admin completion pays budget × (reached ÷ target), so a rescue neither robs the contributors nor mints.
        assertEquals(50, Payout.scaled(100, 500, 1000));
        assertEquals(0, Payout.scaled(100, 0, 1000));
        assertEquals(100, Payout.scaled(100, 1000, 1000));
        assertEquals(
                100,
                Payout.scaled(100, 5000, 1000),
                "an objective already over its target is still only worth its budget");
        assertEquals(33, Payout.scaled(100, 333, 1000), "floored, never rounded up");
    }

    @Test
    void anEmptyOrZeroBudgetPaysNobody() {
        assertTrue(Payout.split(0, 1000, contributions("a", 500, "b", 500)).isEmpty());
        assertTrue(Payout.split(100, 1000, Map.of()).isEmpty());
        assertTrue(Payout.split(100, 1000, contributions("a", 0, "b", 0)).isEmpty());
    }

    @Test
    void aTargetOfZeroIsRefusedRatherThanDividedBy() {
        // smp_objective's own CHECK forbids it; stated again where the division happens rather than throwing raw.
        assertThrows(IllegalArgumentException.class, () -> Payout.split(100, 0, contributions("a", 1, "b", 1)));
        assertThrows(IllegalArgumentException.class, () -> Payout.scaled(100, 10, 0));
    }

    @Test
    void theSameInputsAlwaysProduceTheSamePayout() {
        // Including the tie-break: two equal contributors on a budget that pays one must resolve the same way always.
        final Map<String, Long> tied = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            tied.put(String.format("p%02d", index), 50L);
        }

        final List<Payout.Share> first = Payout.split(10, 100, tied);
        final List<Payout.Share> second = Payout.split(10, 100, tied);

        assertEquals(first, second);
        assertEquals(10, first.stream().filter(Payout.Share::qualified).count());
        assertEquals(
                List.of("p00", "p01", "p02", "p03", "p04", "p05", "p06", "p07", "p08", "p09"),
                first.stream()
                        .filter(Payout.Share::qualified)
                        .map(Payout.Share::contributorId)
                        .sorted()
                        .toList(),
                "ties are broken by id ascending, so the answer is stable across runs");
    }

    @Test
    void anOvershotObjectiveStillOnlyPaysItsBudget() {
        // A HAND_IN usually overshoots, which is why the denominator is what was actually contributed, not the target.
        final List<Payout.Share> shares = Payout.split(100, 1000, contributions("a", 3000, "b", 1000));

        assertEquals(100, total(shares));
        assertTrue(byId(shares, "a").total() > byId(shares, "b").total());
    }

    private static Map<String, Long> contributions(
            final String first, final long firstAmount, final String second, final long secondAmount) {
        final Map<String, Long> map = new LinkedHashMap<>();
        map.put(first, firstAmount);
        map.put(second, secondAmount);
        return map;
    }

    private static Map<String, Long> contributions(
            final String first,
            final long firstAmount,
            final String second,
            final long secondAmount,
            final String third,
            final long thirdAmount) {
        final Map<String, Long> map = contributions(first, firstAmount, second, secondAmount);
        map.put(third, thirdAmount);
        return map;
    }

    private static int total(final List<Payout.Share> shares) {
        return shares.stream().mapToInt(Payout.Share::total).sum();
    }

    private static Payout.Share byId(final List<Payout.Share> shares, final String id) {
        return shares.stream()
                .filter(share -> share.contributorId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
