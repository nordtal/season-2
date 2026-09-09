package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.db.OwnContributionRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The share line's arithmetic, which is the whole of what the spawn NPC's bottom row says.
 *
 * <p>Every case here was previously answerable only by contributing to an objective on a running
 * server and then opening the menu - the line did not exist at all before 2026-09-09, and the number
 * on it is derived from a database read, a config list and two different summations.</p>
 */
class OwnShareTest {

    /** The shipped thresholds: one spin at the qualifying 2 %, two at 10 %, three at 25 %. */
    private static final List<Integer> THRESHOLDS = List.of(2, 10, 25);

    @Test
    @DisplayName("the spin count is per objective and summed, not the aggregate put through once")
    void spinsAreCountedPerObjective() {
        // 30 % of one objective and nothing of three others. Per objective that is three spins -
        // which is what ObjectiveEngine will actually grant when that one completes. The aggregate
        // share is 30/400 = 7.5 %, which through the thresholds once would be one spin, and the
        // line would then promise a third of what the player is owed.
        final OwnShare.Summary summary = OwnShare.of(List.of(
                new OwnContributionRow("a", 30, 100),
                new OwnContributionRow("b", 0, 100),
                new OwnContributionRow("c", 0, 100),
                new OwnContributionRow("d", 0, 100)), THRESHOLDS);

        assertEquals(3, summary.spins());
        assertEquals(7.5, summary.percent(), 1e-9);
        assertEquals(List.of(3, 0, 0, 0), summary.lines().stream().map(OwnShare.Line::spins).toList());
    }

    @Test
    @DisplayName("the percentage is what was contributed over what the milestone asked for")
    void thePercentageIsPerMilestone() {
        final OwnShare.Summary summary = OwnShare.of(List.of(
                new OwnContributionRow("a", 500, 2000),
                new OwnContributionRow("b", 100, 500)), THRESHOLDS);

        assertEquals(24.0, summary.percent(), 1e-9, "600 of 2500");
        assertEquals(25.0, summary.lines().get(0).percent(), 1e-9);
        assertEquals(20.0, summary.lines().get(1).percent(), 1e-9);
        assertEquals(5, summary.spins(), "three at 25 % and two at 20 %");
    }

    @Test
    @DisplayName("a player who has contributed nothing is empty, and one who is just over the line is not")
    void emptyIsEmptyAndTwoPercentIsNot() {
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 0, 100)), THRESHOLDS).empty());
        assertTrue(OwnShare.of(List.of(), THRESHOLDS).empty());

        // The qualifying threshold is 2 %, so this player IS paid and the menu must not tell them
        // they have contributed nothing.
        final OwnShare.Summary just = OwnShare.of(
                List.of(new OwnContributionRow("a", 2, 100)), THRESHOLDS);
        assertFalse(just.empty());
        assertEquals(1, just.spins());
    }

    @Test
    @DisplayName("a contribution below the first threshold shows a share and promises no spin")
    void belowTheThresholdIsStillAShare() {
        final OwnShare.Summary summary = OwnShare.of(
                List.of(new OwnContributionRow("a", 1, 100)), THRESHOLDS);
        assertFalse(summary.empty());
        assertEquals(1.0, summary.percent(), 1e-9);
        assertEquals(0, summary.spins(),
                "1 % is under the 2 % qualifying threshold, so it earns nothing - and the line has"
                        + " to say a share of 1 % rather than round it into a spin");
    }

    @Test
    @DisplayName("over-collection is shown rather than tidied to a hundred")
    void aDoubleDeliveryIsNotClamped() {
        // A target lowered by a reload is one of the concept's own escape hatches, and a HAND_IN
        // that finishes usually overshoots. A player who delivered twice what was asked should see
        // that they did.
        final OwnShare.Summary summary = OwnShare.of(
                List.of(new OwnContributionRow("a", 200, 100)), THRESHOLDS);
        assertEquals(200.0, summary.percent(), 1e-9);
    }

    @Test
    @DisplayName("a target of zero answers zero rather than dividing by it")
    void aZeroTargetDoesNotDivide() {
        // The schema's CHECK makes a positive target the only legal one, so this is the case that
        // cannot happen - and would be a division by zero on the one screen every player opens.
        assertEquals(0.0, OwnShare.percentOf(5, 0), 1e-9);
        assertEquals(0.0, OwnShare.percentOf(0, 0), 1e-9);
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 5, 0)), THRESHOLDS).empty());
    }

    @Test
    @DisplayName("no thresholds configured is no spins, not an exception")
    void noThresholdsPromisesNothing() {
        assertEquals(0, OwnShare.of(
                List.of(new OwnContributionRow("a", 100, 100)), List.of()).spins());
    }

    @Test
    @DisplayName("the percentage is written the way the reader's own language writes one")
    void oneDecimalInTheReadersLanguage() {
        // One decimal, because the qualifying threshold is 2 % and the next band is 10 %: a whole
        // number rounds 2.4 and 1.6 to the same "2", and those two players are on opposite sides of
        // whether they are paid at all.
        assertEquals("4.2", OwnShare.format(4.24, Locale.ENGLISH));
        assertEquals("4,2", OwnShare.format(4.24, Locale.GERMAN));
        assertEquals("100.0", OwnShare.format(100.0, Locale.ENGLISH));
        assertEquals("0.0", OwnShare.format(0.0, Locale.ENGLISH));
    }
}
