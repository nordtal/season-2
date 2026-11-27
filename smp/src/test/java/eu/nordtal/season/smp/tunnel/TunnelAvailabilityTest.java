package eu.nordtal.season.smp.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TunnelAvailabilityTest {

    private static final TunnelFrame BASE = new TunnelFrame("nordtal", new BlockPos(0, 64, 0), TunnelHeading.NORTH, 2);

    private static boolean permitAll(final Collection<BlockPos> footprint) {
        return true;
    }

    @Test
    void startAndNormalStepRequireAllFiveFloorBlocksTenFrontAndTwentyFiveTotal() {
        final TunnelPlan.StepPlan plan = TunnelPlan.planStart(BASE);
        final Set<BlockPos> blocks = new HashSet<>(plan.carveBlocks());
        assertEquals(
                TunnelAvailability.Status.AVAILABLE,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));

        // Missing any single bottom-row floor block fails even with 49 other blocks present.
        blocks.remove(BASE.at(-2, 0, 1));
        assertEquals(
                TunnelAvailability.Status.INSUFFICIENT_BLOCKS,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));

        // 5 floor + 4 other front blocks = 9 front blocks fails even with full second slice (34 total).
        blocks.clear();
        blocks.addAll(plan.frontFloor());
        for (int i = 0; i < 4; i++) {
            blocks.add(BASE.at(i - 2, 1, 1));
        }
        for (final BlockPos pos : plan.carveBlocks()) {
            if (!plan.frontFace().contains(pos)) {
                blocks.add(pos);
            }
        }
        assertEquals(
                TunnelAvailability.Status.INSUFFICIENT_BLOCKS,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));

        // Adding the 10th front block succeeds with side and ceiling blocks missing.
        blocks.add(BASE.at(2, 1, 1));
        assertEquals(
                TunnelAvailability.Status.AVAILABLE,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));

        // 10 front + 14 second slice = 24 total fails; 25 total passes.
        blocks.clear();
        blocks.addAll(plan.frontFloor());
        for (int col = -2; col <= 2; col++) {
            blocks.add(BASE.at(col, 1, 1));
        }
        int addedBack = 0;
        for (final BlockPos pos : plan.carveBlocks()) {
            if (!plan.frontFace().contains(pos) && addedBack < 14) {
                blocks.add(pos);
                addedBack++;
            }
        }
        assertEquals(
                TunnelAvailability.Status.INSUFFICIENT_BLOCKS,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));
        blocks.add(BASE.at(2, 4, 2));
        assertEquals(
                TunnelAvailability.Status.AVAILABLE,
                TunnelAvailability.evaluateStart(plan, blocks::contains, TunnelAvailabilityTest::permitAll));
    }

    @Test
    void turnRequiresSameFrontRulesAndThirtyEightOfSeventyFiveCarvedBlocks() {
        final TunnelPlan.StepPlan turn = TunnelPlan.planZone(BASE, TunnelZone.TURN_LEFT);
        final Set<BlockPos> blocks = new HashSet<>(turn.frontFloor());
        for (int col = -2; col <= 2; col++) {
            blocks.add(BASE.at(col, 1, 1));
        }
        int added = blocks.size();
        for (final BlockPos pos : turn.carveBlocks()) {
            if (added < 37 && blocks.add(pos)) {
                added++;
            }
        }
        assertEquals(
                TunnelAvailability.Status.INSUFFICIENT_BLOCKS,
                TunnelAvailability.evaluateZone(
                        BASE, TunnelZone.TURN_LEFT, turn, blocks::contains, TunnelAvailabilityTest::permitAll));

        for (final BlockPos pos : turn.carveBlocks()) {
            if (blocks.add(pos)) {
                break;
            }
        }
        assertEquals(38, blocks.size());
        assertEquals(
                TunnelAvailability.Status.AVAILABLE,
                TunnelAvailability.evaluateZone(
                        BASE, TunnelZone.TURN_LEFT, turn, blocks::contains, TunnelAvailabilityTest::permitAll));
    }

    @Test
    void shiftedOptionsEvaluateTheirOwnShiftedFrontCrossSection() {
        final TunnelPlan.StepPlan rightPlan = TunnelPlan.planZone(BASE, TunnelZone.RIGHT);
        final TunnelPlan.StepPlan centerPlan = TunnelPlan.planZone(BASE, TunnelZone.CENTER);
        final Set<BlockPos> blocks = new HashSet<>(rightPlan.carveBlocks());
        // Remove the base floor block at col -2 (needed by CENTER, outside RIGHT's shifted floor cols -1..+3).
        blocks.remove(BASE.at(-2, 0, 1));

        assertEquals(
                TunnelAvailability.Status.INSUFFICIENT_BLOCKS,
                TunnelAvailability.evaluateZone(
                        BASE, TunnelZone.CENTER, centerPlan, blocks::contains, TunnelAvailabilityTest::permitAll));
        assertEquals(
                TunnelAvailability.Status.AVAILABLE,
                TunnelAvailability.evaluateZone(
                        BASE, TunnelZone.RIGHT, rightPlan, blocks::contains, TunnelAvailabilityTest::permitAll));
    }

    @Test
    void turnLocksBothTurnsUntilAnyNonTurnOptionSucceedsAndStartResetsLock() {
        final TunnelPlan.StepPlan start = TunnelPlan.planStart(BASE);
        assertFalse(start.nextFrame().turnsLocked());

        final TunnelFrame afterTurn =
                TunnelPlan.planZone(BASE, TunnelZone.TURN_RIGHT).nextFrame();
        assertTrue(afterTurn.turnsLocked());

        final TunnelAvailability.ActiveFace face =
                TunnelAvailability.evaluateActiveFace(afterTurn, pos -> true, TunnelAvailabilityTest::permitAll);
        assertEquals(TunnelAvailability.Status.TURN_LOCKED, face.status(TunnelZone.TURN_LEFT));
        assertEquals(TunnelAvailability.Status.TURN_LOCKED, face.status(TunnelZone.TURN_RIGHT));
        assertTrue(face.isAvailable(TunnelZone.CENTER));
        assertTrue(face.isAvailable(TunnelZone.TOP));
        assertTrue(face.isAvailable(TunnelZone.BOTTOM));
        assertTrue(face.isAvailable(TunnelZone.LEFT));
        assertTrue(face.isAvailable(TunnelZone.RIGHT));

        for (final TunnelZone nonTurn : new TunnelZone[] {
            TunnelZone.CENTER, TunnelZone.TOP, TunnelZone.BOTTOM, TunnelZone.LEFT, TunnelZone.RIGHT
        }) {
            assertFalse(TunnelPlan.planZone(afterTurn, nonTurn).nextFrame().turnsLocked());
        }
    }

    @Test
    void executionRevalidationMapsMaterialAndTurnLockToFallbackAndProtectionToCancel() {
        final TunnelPlan.StepPlan center = TunnelPlan.planZone(BASE, TunnelZone.CENTER);
        final TunnelAvailability.Status protectedStatus =
                TunnelAvailability.evaluateZone(BASE, TunnelZone.CENTER, center, pos -> true, fp -> false);
        assertEquals(TunnelAvailability.Status.PROTECTED, protectedStatus);
        assertEquals(
                TunnelAvailability.ExecutionOutcome.CANCEL_PROTECTED,
                TunnelAvailability.executionOutcome(protectedStatus));

        assertEquals(
                TunnelAvailability.ExecutionOutcome.FALLBACK_SINGLE_BLOCK,
                TunnelAvailability.executionOutcome(TunnelAvailability.Status.INSUFFICIENT_BLOCKS));
        assertEquals(
                TunnelAvailability.ExecutionOutcome.FALLBACK_SINGLE_BLOCK,
                TunnelAvailability.executionOutcome(TunnelAvailability.Status.TURN_LOCKED));
        assertEquals(
                TunnelAvailability.ExecutionOutcome.EXECUTE,
                TunnelAvailability.executionOutcome(TunnelAvailability.Status.AVAILABLE));
    }

    @Test
    void activeFaceReportsNoneAvailableWhenEmptyAndDisabledZonesNeverHighlight() {
        final TunnelAvailability.ActiveFace empty =
                TunnelAvailability.evaluateActiveFace(BASE, pos -> false, TunnelAvailabilityTest::permitAll);
        assertFalse(empty.anyAvailable());
        assertEquals(7, empty.disabledZones().size());

        assertEquals(
                "turn_left_disabled",
                TunnelOverlayPackets.modelFor(
                        TunnelZone.TURN_LEFT, TunnelZone.TURN_LEFT, Set.of(TunnelZone.TURN_LEFT)));
        assertEquals(
                "center_aimed",
                TunnelOverlayPackets.modelFor(TunnelZone.CENTER, TunnelZone.CENTER, Set.of(TunnelZone.TURN_LEFT)));
        assertEquals(
                "center",
                TunnelOverlayPackets.modelFor(TunnelZone.CENTER, TunnelZone.TOP, Set.of(TunnelZone.TURN_LEFT)));
    }
}
