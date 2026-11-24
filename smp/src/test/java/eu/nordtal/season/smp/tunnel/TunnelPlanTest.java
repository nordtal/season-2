package eu.nordtal.season.smp.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

class TunnelPlanTest {

    @Test
    void startOriginAlignsToGroundWhenTargetLiesWithinFiveRows() {
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 66, 20), true, 64));
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 68, 20), true, 64));
        assertEquals(new BlockPos(10, 68, 20), TunnelFrame.startOrigin(new BlockPos(10, 70, 20), true, 64));
        assertEquals(new BlockPos(10, 64, 20), TunnelFrame.startOrigin(new BlockPos(10, 66, 20), false, 64));
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

        assertEquals(1, plan.removeBlocks().size());
        assertEquals(new BlockPos(-2, 68, -1), plan.removeBlocks().getFirst());
        assertEquals(1, plan.rotateStairs().size());
        assertEquals(new BlockPos(-2, 68, 0), plan.rotateStairs().getFirst().pos());
        assertEquals(BlockFace.SOUTH, plan.rotateStairs().getFirst().newFacing());

        assertEquals(TunnelHeading.WEST, plan.nextFrame().heading());
        assertEquals(new BlockPos(-3, 64, -2), plan.nextFrame().origin());
        assertEquals(5, plan.nextFrame().length());

        final Set<BlockPos> footprint = plan.protectionFootprint();
        assertTrue(footprint.contains(new BlockPos(0, 63, -2)));
        assertTrue(footprint.contains(new BlockPos(-2, 68, -1)));
        assertTrue(footprint.contains(new BlockPos(-2, 68, 0)));
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
