package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.port.OwnContributionRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnShareTest {

    @Test
    void spinsAreCountedPerObjective() {
        // 30% of one objective and nothing of three others. The aggregate would understate what one completion pays.
        final OwnShare.Summary summary = OwnShare.of(List.of(
                new OwnContributionRow("a", 30, 100, 3, 20),
                new OwnContributionRow("b", 0, 100, 0, 20),
                new OwnContributionRow("c", 0, 100, 0, 20),
                new OwnContributionRow("d", 0, 100, 0, 20)));

        assertEquals(3, summary.spins());
        assertEquals(7.5, summary.percent(), 1e-9);
        assertEquals(
                List.of(3, 0, 0, 0),
                summary.lines().stream().map(OwnShare.Line::spins).toList());
    }

    @Test
    void eachForecastStandsBesideItsObjectivesSpinBudget() {
        final OwnShare.Summary summary = OwnShare.of(List.of(
                new OwnContributionRow("a", 30, 100, 3, 20),
                new OwnContributionRow("b", 0, 100, 0, 16),
                new OwnContributionRow("c", 5, 100, 1, -4)));

        assertEquals(
                List.of(20, 16, 0),
                summary.lines().stream().map(OwnShare.Line::budget).toList(),
                "each objective's own budget, a negative one read as nothing");
        assertEquals(36, summary.budget(), "the milestone's budgets, added up beside the forecast's sum");
        assertEquals(4, summary.spins());
    }

    @Test
    void thePercentageIsPerMilestone() {
        final OwnShare.Summary summary = OwnShare.of(
                List.of(new OwnContributionRow("a", 500, 2000, 4, 20), new OwnContributionRow("b", 100, 500, 2, 20)));

        assertEquals(24.0, summary.percent(), 1e-9, "600 of 2500");
        assertEquals(25.0, summary.lines().get(0).percent(), 1e-9);
        assertEquals(20.0, summary.lines().get(1).percent(), 1e-9);
        assertEquals(6, summary.spins(), "each objective's own forecast, added up");
    }

    @Test
    void emptyIsEmptyAndTwoPercentIsNot() {
        assertTrue(
                OwnShare.of(List.of(new OwnContributionRow("a", 0, 100, 0, 20))).empty());
        assertTrue(OwnShare.of(List.of()).empty());

        final OwnShare.Summary just = OwnShare.of(List.of(new OwnContributionRow("a", 2, 100, 1, 20)));
        assertFalse(just.empty());
        assertEquals(1, just.spins());
    }

    @Test
    void belowTheThresholdIsStillAShare() {
        final OwnShare.Summary summary = OwnShare.of(List.of(new OwnContributionRow("a", 1, 100, 0, 20)));
        assertFalse(summary.empty());
        assertEquals(1.0, summary.percent(), 1e-9);
        assertEquals(0, summary.spins(), "a share no spin is forecast for still shows as a share of 1 %");
    }

    @Test
    void aDoubleDeliveryIsNotClamped() {
        // A target lowered by a reload is an escape hatch. A player who delivered twice the target sees it.
        final OwnShare.Summary summary = OwnShare.of(List.of(new OwnContributionRow("a", 200, 100, 20, 20)));
        assertEquals(200.0, summary.percent(), 1e-9);
    }

    @Test
    void aZeroTargetDoesNotDivide() {
        // The CHECK of the schema allows only a positive target, so this screen never divides by zero.
        assertEquals(0.0, OwnShare.percentOf(5, 0), 1e-9);
        assertEquals(0.0, OwnShare.percentOf(0, 0), 1e-9);
        assertTrue(
                OwnShare.of(List.of(new OwnContributionRow("a", 5, 0, 0, 20))).empty());
    }
}
