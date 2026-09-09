package eu.nordtal.s2.smp.aura;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contribution payout.
 *
 * <p>Two invariants run through everything below: <b>the pot is never overspent</b>, and <b>the
 * same inputs always produce the same payout</b>.
 */
class AuraPayoutTest {

    @Test
    void twoEqualContributorsSplitEverything() {
        final List<AuraPayout.Share> shares = AuraPayout.split(100, 1000, contributions("a", 500, "b", 500));

        assertEquals(2, shares.size());
        // 30 % equally: 15 each. 70 % by share: 35 each.
        assertEquals(15, shares.get(0).equal());
        assertEquals(35, shares.get(0).proportional());
        assertEquals(50, total(shares) / 2);
        assertEquals(100, total(shares));
    }

    @Test
    void aContributorBelowTwoPercentGetsTheProportionalShareOnly() {
        // Below the threshold a contributor gets their proportional share only, so a symbolic
        // contribution to every objective on the track cannot pay hundreds of aura.
        final List<AuraPayout.Share> shares = AuraPayout.split(100, 1000, contributions("a", 990, "b", 10));

        final AuraPayout.Share small = byId(shares, "b");
        assertFalse(small.qualified());
        assertEquals(0, small.equal());
        assertTrue(byId(shares, "a").qualified());
    }

    @Test
    void exactlyOnTheThresholdQualifies() {
        // 2 % of 1000 is 20. ">=" and ">" differ by one player's entire equal share.
        assertTrue(byId(AuraPayout.split(100, 1000, contributions("a", 980, "b", 20)), "b").qualified());
        assertFalse(byId(AuraPayout.split(100, 1000, contributions("a", 981, "b", 19)), "b").qualified());
    }

    @Test
    void theWorkedExampleFromTheConcept() {
        // At a pot of 30 with twelve qualifiers, 30 % is nine aura, which in whole numbers rounds
        // to nothing - and paying a participant zero is what the equal part exists to prevent.
        final Map<String, Long> twelve = new LinkedHashMap<>();
        for (int index = 0; index < 12; index++) {
            twelve.put("p" + index, 100L);
        }

        final List<AuraPayout.Share> shares = AuraPayout.split(30, 1000, twelve);

        assertEquals(12, shares.size());
        for (final AuraPayout.Share share : shares) {
            assertEquals(1, share.equal(), "every qualifier is guaranteed one aura");
        }
        // The equal part grew from 9 to 12, so 18 is left to divide proportionally: 1 each, with 6
        // lost to the floor.
        assertEquals(1, shares.get(0).proportional());
        assertTrue(total(shares) <= 30, "paid " + total(shares) + " out of a pot of 30");
    }

    @Test
    void thePotIsNeverOverspent() {
        // An absolute guaranteed floor next to a relative pot would let a small objective's pot
        // be smaller than the sum of its own floors.
        for (int pot : new int[]{1, 2, 7, 30, 60, 80, 110, 170, 1000}) {
            for (int contributors : new int[]{1, 2, 3, 12, 40, 100}) {
                final Map<String, Long> map = new LinkedHashMap<>();
                for (int index = 0; index < contributors; index++) {
                    map.put("p" + index, (long) (index + 1) * 7);
                }

                final int paid = total(AuraPayout.split(pot, 1000, map));

                assertTrue(paid <= pot,
                        "pot " + pot + " with " + contributors + " contributors paid out " + paid);
            }
        }
    }

    @Test
    void moreQualifiersThanThereIsAuraPaysTheBiggestContributorsFirst() {
        // Forty qualifiers on a pot of thirty cannot all get their guaranteed aura: the guarantee
        // reaches as far as the pot does, largest contribution first.
        final Map<String, Long> forty = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            forty.put(String.format("p%02d", index), (long) (index + 1) * 100);
        }

        final List<AuraPayout.Share> shares = AuraPayout.split(30, 100, forty);

        assertEquals(30, shares.stream().filter(AuraPayout.Share::qualified).count(),
                "the guarantee reaches exactly as far as the pot");
        assertTrue(byId(shares, "p39").qualified(), "the largest contributor is paid");
        assertFalse(byId(shares, "p00").qualified(), "the smallest is not");
        assertTrue(total(shares) <= 30);
    }

    @Test
    void anAdvancementObjectiveSplitsEvenly() {
        // A player's share is 1 or 0, so the proportional part divides equally too - and everybody
        // who earned the advancement qualifies, INCLUDING those beyond the target count.
        final Map<String, Long> ten = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            ten.put("p" + index, 1L);
        }

        final List<AuraPayout.Share> shares = AuraPayout.split(80, 8, ten);

        assertEquals(10, shares.size());
        assertEquals(10, shares.stream().filter(AuraPayout.Share::qualified).count(),
                "the two players beyond the target of 8 are paid like the rest");
        final int each = shares.get(0).total();
        for (final AuraPayout.Share share : shares) {
            assertEquals(each, share.total(), "an ADVANCEMENT objective pays everybody the same");
        }
    }

    @Test
    void anAdminCompletionPaysProportionallyToWhatWasActuallyReached() {
        // An admin completion pays pot × (reached ÷ target), so a rescue neither robs the
        // contributors nor mints aura.
        assertEquals(50, AuraPayout.scaledPot(100, 500, 1000));
        assertEquals(0, AuraPayout.scaledPot(100, 0, 1000));
        assertEquals(100, AuraPayout.scaledPot(100, 1000, 1000));
        assertEquals(100, AuraPayout.scaledPot(100, 5000, 1000),
                "an objective already over its target is still only worth its pot");
        assertEquals(33, AuraPayout.scaledPot(100, 333, 1000), "floored, never rounded up");
    }

    @Test
    void anEmptyOrZeroPotPaysNobody() {
        assertTrue(AuraPayout.split(0, 1000, contributions("a", 500, "b", 500)).isEmpty());
        assertTrue(AuraPayout.split(100, 1000, Map.of()).isEmpty());
        assertTrue(AuraPayout.split(100, 1000, contributions("a", 0, "b", 0)).isEmpty());
    }

    @Test
    void aTargetOfZeroIsRefusedRatherThanDividedBy() {
        // smp_objective's own CHECK forbids it; stated again where the division happens, so a
        // stale row gets a message rather than an arithmetic exception mid-payout.
        assertThrows(IllegalArgumentException.class,
                () -> AuraPayout.split(100, 0, contributions("a", 1, "b", 1)));
        assertThrows(IllegalArgumentException.class, () -> AuraPayout.scaledPot(100, 10, 0));
    }

    @Test
    void theSameInputsAlwaysProduceTheSamePayout() {
        // Including the tie-break: two equal contributors on a pot that cannot pay them both must
        // resolve the same way on every run.
        final Map<String, Long> tied = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            tied.put(String.format("p%02d", index), 50L);
        }

        final List<AuraPayout.Share> first = AuraPayout.split(10, 100, tied);
        final List<AuraPayout.Share> second = AuraPayout.split(10, 100, tied);

        assertEquals(first, second);
        assertEquals(10, first.stream().filter(AuraPayout.Share::qualified).count());
        assertEquals(List.of("p00", "p01", "p02", "p03", "p04", "p05", "p06", "p07", "p08", "p09"),
                first.stream().filter(AuraPayout.Share::qualified)
                        .map(AuraPayout.Share::contributorId).sorted().toList(),
                "ties are broken by id ascending, so the answer is stable across runs");
    }

    @Test
    void anOvershotObjectiveStillOnlyPaysItsPot() {
        // The delivery that completes a HAND_IN usually overshoots, which is why the denominator
        // is the total actually contributed rather than the target.
        final List<AuraPayout.Share> shares =
                AuraPayout.split(100, 1000, contributions("a", 3000, "b", 1000));

        assertTrue(total(shares) <= 100, "paid " + total(shares));
        assertTrue(byId(shares, "a").total() > byId(shares, "b").total());
    }

    private static Map<String, Long> contributions(final String first, final long firstAmount,
                                                   final String second, final long secondAmount) {
        final Map<String, Long> map = new LinkedHashMap<>();
        map.put(first, firstAmount);
        map.put(second, secondAmount);
        return map;
    }

    private static int total(final List<AuraPayout.Share> shares) {
        return shares.stream().mapToInt(AuraPayout.Share::total).sum();
    }

    private static AuraPayout.Share byId(final List<AuraPayout.Share> shares, final String id) {
        return shares.stream().filter(share -> share.contributorId().equals(id)).findFirst().orElseThrow();
    }
}
