package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.port.OwnContributionRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The share line's arithmetic, which is the whole of what the spawn NPC's bottom row says. */
class OwnShareTest {

    @Test
    void spinsAreCountedPerObjective() {
        // 30% of one objective and nothing of three others; the aggregate would understate what one completion pays.
        final OwnShare.Summary summary = OwnShare.of(List.of(
                new OwnContributionRow("a", 30, 100, 3),
                new OwnContributionRow("b", 0, 100, 0),
                new OwnContributionRow("c", 0, 100, 0),
                new OwnContributionRow("d", 0, 100, 0)));

        assertEquals(3, summary.spins());
        assertEquals(7.5, summary.percent(), 1e-9);
        assertEquals(
                List.of(3, 0, 0, 0),
                summary.lines().stream().map(OwnShare.Line::spins).toList());
    }

    @Test
    void thePercentageIsPerMilestone() {
        final OwnShare.Summary summary = OwnShare.of(
                List.of(new OwnContributionRow("a", 500, 2000, 4), new OwnContributionRow("b", 100, 500, 2)));

        assertEquals(24.0, summary.percent(), 1e-9, "600 of 2500");
        assertEquals(25.0, summary.lines().get(0).percent(), 1e-9);
        assertEquals(20.0, summary.lines().get(1).percent(), 1e-9);
        assertEquals(6, summary.spins(), "each objective's own forecast, added up");
    }

    @Test
    void emptyIsEmptyAndTwoPercentIsNot() {
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 0, 100, 0))).empty());
        assertTrue(OwnShare.of(List.of()).empty());

        // A spin forecast of one at 2 %; the menu must not say this player contributed nothing.
        final OwnShare.Summary just = OwnShare.of(List.of(new OwnContributionRow("a", 2, 100, 1)));
        assertFalse(just.empty());
        assertEquals(1, just.spins());
    }

    @Test
    void belowTheThresholdIsStillAShare() {
        final OwnShare.Summary summary = OwnShare.of(List.of(new OwnContributionRow("a", 1, 100, 0)));
        assertFalse(summary.empty());
        assertEquals(1.0, summary.percent(), 1e-9);
        assertEquals(0, summary.spins(), "a share no spin is forecast for still shows as a share of 1 %");
    }

    @Test
    void aDoubleDeliveryIsNotClamped() {
        // A reload-lowered target is one of the escape hatches; a player who delivered twice what was asked sees it.
        final OwnShare.Summary summary = OwnShare.of(List.of(new OwnContributionRow("a", 200, 100, 20)));
        assertEquals(200.0, summary.percent(), 1e-9);
    }

    @Test
    void aZeroTargetDoesNotDivide() {
        // The schema's CHECK makes a positive target the only legal one, ruling out a division by zero on this screen.
        assertEquals(0.0, OwnShare.percentOf(5, 0), 1e-9);
        assertEquals(0.0, OwnShare.percentOf(0, 0), 1e-9);
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 5, 0, 0))).empty());
    }
}
