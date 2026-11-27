package eu.nordtal.season.smp.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

class TunnelPlanTest {

    @Test
    void startOriginAlignsToGroundWithinFiveRowsAcrossFullBlocksSlabsStairsAndElevatedPositions() {
        // Full block at y=63 (playerY = 64.0): inclusive band [64, 68].
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 64, 20), true, 64.0));
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 66, 20), true, 64.0));
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 68, 20), true, 64.0));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 63, 20), true, 64.0));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 69, 20), true, 64.0));

        // Bottom slab or lower stair step at y=64 (playerY = 64.5): floor is 64, band [64, 68].
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 64, 20), true, 64.5));
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 68, 20), true, 64.5));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 63, 20), true, 64.5));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 69, 20), true, 64.5));

        // Top slab or upper stair step at y=64 (playerY = 65.0): floor is 65, band [65, 69].
        assertEquals(new BlockPos(10, 65, 20), TunnelFrame.startOrigin(new BlockPos(10, 65, 20), true, 65.0));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 64, 20), true, 65.0));

        // Negative Y bottom slab (playerY = -10.5): floor is -11, band [-11, -7].
        assertEquals(new BlockPos(10, -11, 20), TunnelFrame.startOrigin(new BlockPos(10, -11, 20), true, -10.5));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, -12, 20), true, -10.5));

        // Elevated player standing above a candidate and looking down through a gap gets no preview.
        assertEquals(new BlockPos(10, 72, 20), TunnelFrame.startOrigin(new BlockPos(10, 72, 20), true, 72.0));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 68, 20), true, 72.0));
        assertNull(TunnelFrame.startOrigin(new BlockPos(10, 71, 20), true, 72.0));

        // Airborne placement remains target-centred regardless of player Y.
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 66, 20), false, 72.0));
        assertEquals(new BlockPos(10, 68, 20), TunnelFrame.startOrigin(new BlockPos(10, 70, 20), false, 64.0));
    }

    @Test
    void topCornersAreUnhighlightedAndAllSevenZonesMapCorrectly() {
        assertEquals(TunnelZone.CENTER, TunnelZone.at(0, 2));
        assertEquals(TunnelZone.TOP, TunnelZone.at(0, 4));
        assertEquals(TunnelZone.BOTTOM, TunnelZone.at(0, 0));
        assertEquals(TunnelZone.LEFT, TunnelZone.at(-2, 2));
        assertEquals(TunnelZone.RIGHT, TunnelZone.at(2, 2));
        assertEquals(TunnelZone.TURN_LEFT, TunnelZone.at(-2, 0));
        assertEquals(TunnelZone.TURN_RIGHT, TunnelZone.at(2, 0));
        assertNull(TunnelZone.at(-2, 4));
        assertNull(TunnelZone.at(2, 4));
    }

    @Test
    void startStepCarvesFiftyBlocksPlacesTwoRailsAndOutwardCeilingStairs() {
        final TunnelFrame start = new TunnelFrame("nordtal", new BlockPos(0, 64, 0), TunnelHeading.NORTH, 0);
        final TunnelPlan.StepPlan plan = TunnelPlan.planStart(start);

        assertEquals(50, plan.carveBlocks().size());
        assertEquals(2, plan.rails().size());
        assertEquals(new BlockPos(0, 64, 0), plan.rails().get(0).pos());
        assertEquals(Rail.Shape.NORTH_SOUTH, plan.rails().get(0).shape());
        assertEquals(new BlockPos(0, 64, -1), plan.rails().get(1).pos());
        assertEquals(4, plan.stairs().size());
        assertEquals(BlockFace.WEST, plan.stairs().get(0).facing());
        assertEquals(BlockFace.EAST, plan.stairs().get(1).facing());
        assertEquals(new BlockPos(0, 64, -2), plan.nextFrame().origin());
        assertEquals(2, plan.nextFrame().length());
        assertTrue(plan.lanterns().isEmpty());
    }

    @Test
    void lateralRightShiftPlacesSidewaysConnectorOnFirstSliceAndStraightRailOnSecond() {
        final TunnelFrame active = new TunnelFrame("nordtal", new BlockPos(0, 64, -2), TunnelHeading.NORTH, 2);
        final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, TunnelZone.RIGHT);

        assertEquals(50, plan.carveBlocks().size());
        assertEquals(3, plan.rails().size());
        assertEquals(new BlockPos(0, 64, -2), plan.rails().get(0).pos());
        assertEquals(Rail.Shape.SOUTH_EAST, plan.rails().get(0).shape());
        assertEquals(new BlockPos(1, 64, -2), plan.rails().get(1).pos());
        assertEquals(Rail.Shape.NORTH_WEST, plan.rails().get(1).shape());
        assertEquals(new BlockPos(1, 64, -3), plan.rails().get(2).pos());
        assertEquals(Rail.Shape.NORTH_SOUTH, plan.rails().get(2).shape());
        assertEquals(new BlockPos(1, 64, -4), plan.nextFrame().origin());
    }

    @Test
    void ninetyDegreeLeftTurnCarvesSeventyFiveBlocksBendsRailAndRotatesActiveFace() {
        final TunnelFrame active = new TunnelFrame("nordtal", new BlockPos(0, 64, -2), TunnelHeading.NORTH, 2);
        final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, TunnelZone.TURN_LEFT);

        assertEquals(75, plan.carveBlocks().size());
        assertEquals(3, plan.rails().size());
        assertEquals(new BlockPos(0, 64, -2), plan.rails().get(0).pos());
        assertEquals(Rail.Shape.SOUTH_WEST, plan.rails().get(0).shape());
        assertEquals(new BlockPos(-1, 64, -2), plan.rails().get(1).pos());
        assertEquals(Rail.Shape.EAST_WEST, plan.rails().get(1).shape());
        assertEquals(new BlockPos(-2, 64, -2), plan.rails().get(2).pos());
        assertEquals(Rail.Shape.EAST_WEST, plan.rails().get(2).shape());

        assertEquals(List.of(new BlockPos(-2, 68, -1), new BlockPos(-2, 68, 0)), plan.removeBlocks());
        assertEquals(7, plan.stairs().size());
        assertFalse(plan.stairs().stream().anyMatch(s -> s.pos().equals(new BlockPos(-2, 68, 0))));

        assertEquals(TunnelHeading.WEST, plan.nextFrame().heading());
        assertEquals(new BlockPos(-3, 64, -2), plan.nextFrame().origin());
        assertEquals(5, plan.nextFrame().length());

        final Set<BlockPos> footprint = plan.protectionFootprint();
        assertTrue(footprint.contains(new BlockPos(0, 63, -2)));
        assertTrue(footprint.contains(new BlockPos(-2, 68, -1)));
        assertTrue(footprint.contains(new BlockPos(-2, 68, 0)));
    }

    @Test
    void ninetyDegreeRightTurnRemovesOverhangingInnerCornerStairAndPlacesOuterCornerStairs() {
        final TunnelFrame active = new TunnelFrame("nordtal", new BlockPos(0, 64, -2), TunnelHeading.NORTH, 2);
        final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, TunnelZone.TURN_RIGHT);

        assertEquals(75, plan.carveBlocks().size());
        assertEquals(3, plan.rails().size());
        assertEquals(new BlockPos(0, 64, -2), plan.rails().get(0).pos());
        assertEquals(Rail.Shape.SOUTH_EAST, plan.rails().get(0).shape());
        assertEquals(new BlockPos(1, 64, -2), plan.rails().get(1).pos());
        assertEquals(Rail.Shape.EAST_WEST, plan.rails().get(1).shape());
        assertEquals(new BlockPos(2, 64, -2), plan.rails().get(2).pos());
        assertEquals(Rail.Shape.EAST_WEST, plan.rails().get(2).shape());

        assertEquals(List.of(new BlockPos(2, 68, -1), new BlockPos(2, 68, 0)), plan.removeBlocks());
        assertEquals(7, plan.stairs().size());
        assertFalse(plan.stairs().stream().anyMatch(s -> s.pos().equals(new BlockPos(2, 68, 0))));

        assertEquals(TunnelHeading.EAST, plan.nextFrame().heading());
        assertEquals(new BlockPos(3, 64, -2), plan.nextFrame().origin());
        assertEquals(5, plan.nextFrame().length());

        final Set<BlockPos> footprint = plan.protectionFootprint();
        assertTrue(footprint.contains(new BlockPos(0, 63, -2)));
        assertTrue(footprint.contains(new BlockPos(2, 68, -1)));
        assertTrue(footprint.contains(new BlockPos(2, 68, 0)));
    }

    @Test
    void everyCeilingStairFacesUncarvedWallAcrossLeftAndRightTurnsInAllHeadings() {
        for (final TunnelHeading heading : TunnelHeading.values()) {
            for (final TunnelZone turn : List.of(TunnelZone.TURN_LEFT, TunnelZone.TURN_RIGHT)) {
                final TunnelFrame start = new TunnelFrame("nordtal", new BlockPos(0, 64, 0), heading, 0);
                final TunnelPlan.StepPlan step1 = TunnelPlan.planStart(start);
                final TunnelPlan.StepPlan step2 = TunnelPlan.planZone(step1.nextFrame(), TunnelZone.CENTER);
                final TunnelPlan.StepPlan turnStep = TunnelPlan.planZone(step2.nextFrame(), turn);
                final TunnelPlan.StepPlan afterTurn = TunnelPlan.planZone(turnStep.nextFrame(), TunnelZone.CENTER);

                final Set<BlockPos> carved = new HashSet<>();
                final Map<BlockPos, BlockFace> stairs = new HashMap<>();
                for (final TunnelPlan.StepPlan step : List.of(step1, step2, turnStep, afterTurn)) {
                    carved.addAll(step.carveBlocks());
                    step.removeBlocks().forEach(stairs::remove);
                    for (final TunnelPlan.StairPlacement stair : step.stairs()) {
                        stairs.put(stair.pos(), stair.facing());
                    }
                }
                final int turnCol = turn.isTurnLeft() ? -2 : 2;
                assertFalse(stairs.containsKey(step2.nextFrame().at(turnCol, 4, 0)));
                assertFalse(stairs.containsKey(step2.nextFrame().at(turnCol, 4, -1)));
                for (final Map.Entry<BlockPos, BlockFace> entry : stairs.entrySet()) {
                    final BlockPos pos = entry.getKey();
                    final BlockFace face = entry.getValue();
                    final BlockPos backing = pos.offset(face.getModX(), 0, face.getModZ());
                    assertFalse(
                            carved.contains(backing),
                            "Stair at " + pos + " facing " + face + " overhangs carved " + backing);
                }
            }
        }
    }

    @Test
    void hangingLanternPlacesEveryEightBlocks() {
        final TunnelFrame active = new TunnelFrame("nordtal", new BlockPos(0, 64, -6), TunnelHeading.NORTH, 6);
        final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, TunnelZone.CENTER);

        assertEquals(1, plan.lanterns().size());
        assertEquals(new BlockPos(0, 68, -7), plan.lanterns().getFirst().pos());
        assertEquals(new BlockPos(0, 69, -7), plan.lanterns().getFirst().ceilingPos());
    }

    @Test
    void activeTunnelRangeCutsOffPastTwelveBlocks() {
        final TunnelFrame frame = new TunnelFrame("nordtal", new BlockPos(0, 64, 0), TunnelHeading.NORTH, 2);
        assertTrue(frame.withinActiveRange(0.5, 66.5, 12.5));
        assertFalse(frame.withinActiveRange(0.5, 66.5, 12.6));
    }

    @Test
    void upwardShiftIncludesPreviousRailInProtectionFootprint() {
        final TunnelFrame active = new TunnelFrame("nordtal", new BlockPos(0, 64, -2), TunnelHeading.NORTH, 2);
        final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, TunnelZone.TOP);
        assertEquals(List.of(new BlockPos(0, 64, -1)), plan.slopeEditBlocks());
        assertTrue(plan.protectionFootprint().contains(new BlockPos(0, 64, -1)));
    }
}
