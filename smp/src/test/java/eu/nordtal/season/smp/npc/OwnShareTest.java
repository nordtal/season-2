package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.port.OwnContributionRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The share line's arithmetic, which is the whole of what the spawn NPC's bottom row says. */
class OwnShareTest {

    /** The shipped thresholds as the wheel applies them: one spin at the qualifying 2 %, two at 10 %, three at 25 %. */
    private static int thresholds(final double percent) {
        return (int) List.of(2, 10, 25).stream()
                .filter(threshold -> percent >= threshold)
                .count();
    }

    @Test
    void spinsAreCountedPerObjective() {
        // 30% of one objective and nothing of three others; the aggregate would understate what one completion grants.
        final OwnShare.Summary summary = OwnShare.of(
                List.of(
                        new OwnContributionRow("a", 30, 100),
                        new OwnContributionRow("b", 0, 100),
                        new OwnContributionRow("c", 0, 100),
                        new OwnContributionRow("d", 0, 100)),
                OwnShareTest::thresholds);

        assertEquals(3, summary.spins());
        assertEquals(7.5, summary.percent(), 1e-9);
        assertEquals(
                List.of(3, 0, 0, 0),
                summary.lines().stream().map(OwnShare.Line::spins).toList());
    }

    @Test
    void thePercentageIsPerMilestone() {
        final OwnShare.Summary summary = OwnShare.of(
                List.of(new OwnContributionRow("a", 500, 2000), new OwnContributionRow("b", 100, 500)),
                OwnShareTest::thresholds);

        assertEquals(24.0, summary.percent(), 1e-9, "600 of 2500");
        assertEquals(25.0, summary.lines().get(0).percent(), 1e-9);
        assertEquals(20.0, summary.lines().get(1).percent(), 1e-9);
        assertEquals(5, summary.spins(), "three at 25 % and two at 20 %");
    }

    @Test
    void emptyIsEmptyAndTwoPercentIsNot() {
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 0, 100)), OwnShareTest::thresholds)
                .empty());
        assertTrue(OwnShare.of(List.of(), OwnShareTest::thresholds).empty());

        // The qualifying threshold is 2%, so this player IS paid; the menu must not say they contributed nothing.
        final OwnShare.Summary just =
                OwnShare.of(List.of(new OwnContributionRow("a", 2, 100)), OwnShareTest::thresholds);
        assertFalse(just.empty());
        assertEquals(1, just.spins());
    }

    @Test
    void belowTheThresholdIsStillAShare() {
        final OwnShare.Summary summary =
                OwnShare.of(List.of(new OwnContributionRow("a", 1, 100)), OwnShareTest::thresholds);
        assertFalse(summary.empty());
        assertEquals(1.0, summary.percent(), 1e-9);
        assertEquals(
                0,
                summary.spins(),
                "1 % is under the 2 % qualifying threshold, so it earns nothing - and the line has"
                        + " to say a share of 1 % rather than round it into a spin");
    }

    @Test
    void aDoubleDeliveryIsNotClamped() {
        // A reload-lowered target is one of the escape hatches; a player who delivered twice what was asked sees it.
        final OwnShare.Summary summary =
                OwnShare.of(List.of(new OwnContributionRow("a", 200, 100)), OwnShareTest::thresholds);
        assertEquals(200.0, summary.percent(), 1e-9);
    }

    @Test
    void aZeroTargetDoesNotDivide() {
        // The schema's CHECK makes a positive target the only legal one, ruling out a division by zero on this screen.
        assertEquals(0.0, OwnShare.percentOf(5, 0), 1e-9);
        assertEquals(0.0, OwnShare.percentOf(0, 0), 1e-9);
        assertTrue(OwnShare.of(List.of(new OwnContributionRow("a", 5, 0)), OwnShareTest::thresholds)
                .empty());
    }
}
