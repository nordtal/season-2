package eu.nordtal.s2.smp.npc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place in this design where a bug takes items off a player and gives nothing back.
 *
 * <p>Everything asserted here is a refusal: refusing to take what no objective wants, refusing to
 * take more than is still needed, and - the case worth the whole test class - refusing to swallow
 * the surplus of a stack that straddles the target.
 */
class HandInTest {

    private static final Set<String> WANTED = Set.of("IRON_INGOT", "GOLD_INGOT");

    private static HandIn.Offered offered(final int slot, final String material, final int amount) {
        return new HandIn.Offered(slot, material, amount);
    }

    private static int taken(final HandIn.Result result) {
        return result.takes().stream().mapToInt(HandIn.Take::taken).sum();
    }

    @Test
    void whatTheObjectiveWantsIsTaken() {
        final HandIn.Result result = HandIn.sort(List.of(offered(0, "IRON_INGOT", 32)), WANTED, 100);

        assertEquals(32, result.accepted());
        assertEquals(32, taken(result));
        assertEquals(0, result.takes().get(0).returned());
    }

    @Test
    void whatItDoesNotWantIsNeverTouched() {
        final HandIn.Result result = HandIn.sort(
                List.of(offered(0, "DIRT", 64), offered(1, "IRON_INGOT", 8)), WANTED, 100);

        assertEquals(8, result.accepted());
        assertEquals(1, result.takes().size(), "the dirt's slot is not in the list at all");
        assertEquals(1, result.takes().get(0).slot());
    }

    /** The case this class exists for. */
    @Test
    void aStackThatStraddlesTheTargetIsSplitAndTheSurplusLeftAlone() {
        final HandIn.Result result = HandIn.sort(List.of(offered(3, "IRON_INGOT", 64)), WANTED, 10);

        assertEquals(10, result.accepted());
        assertEquals(10, result.takes().get(0).taken());
        assertEquals(54, result.takes().get(0).returned(),
                "somebody emptying a chest into a nearly-finished objective keeps the rest");
    }

    @Test
    void nothingIsTakenOnceTheObjectiveIsFull() {
        final HandIn.Result result = HandIn.sort(
                List.of(offered(0, "IRON_INGOT", 64), offered(1, "GOLD_INGOT", 64)), WANTED, 0);

        assertEquals(0, result.accepted());
        assertTrue(result.takes().isEmpty());
    }

    @Test
    void severalWantedMaterialsCountTowardsTheSameObjective() {
        final HandIn.Result result = HandIn.sort(
                List.of(offered(0, "IRON_INGOT", 5), offered(1, "GOLD_INGOT", 5)), WANTED, 100);

        assertEquals(10, result.accepted());
        assertEquals(2, result.takes().size());
    }

    @Test
    void matchingIsCaseInsensitiveBecauseConfigIsWrittenByHand() {
        final HandIn.Result result = HandIn.sort(
                List.of(offered(0, "iron_ingot", 4)), Set.of("Iron_Ingot"), 100);

        assertEquals(4, result.accepted());
    }

    @Test
    void anEmptyDepositIsNotAnError() {
        final HandIn.Result result = HandIn.sort(List.of(), WANTED, 100);

        assertEquals(0, result.accepted());
        assertTrue(result.takes().isEmpty());
    }

    @Test
    void anObjectiveThatWantsNothingTakesNothing() {
        final HandIn.Result result = HandIn.sort(List.of(offered(0, "IRON_INGOT", 64)), Set.of(), 100);

        assertEquals(0, result.accepted());
        assertTrue(result.takes().isEmpty());
    }

    /** The guarantee the whole feature rests on, stated as one equality. */
    @Test
    void everyItemTakenIsAnItemCounted() {
        final HandIn.Result result = HandIn.sort(
                List.of(offered(0, "IRON_INGOT", 40), offered(1, "DIRT", 3),
                        offered(2, "GOLD_INGOT", 40)), WANTED, 50);

        assertEquals(result.accepted(), taken(result));
        assertEquals(50, result.accepted(), "and never more than the objective still needs");
    }
}
