package eu.nordtal.season.smp.protect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.SpawnExceptionSpec;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

class MarkerGeometryTest {

    @Test
    void closedNorthDoorPlacesBothMarkersOnUpperHalfAtEyeLevel() {
        final List<MarkerGeometry.Placement> placements = MarkerGeometry.forDoor(
                BlockFace.NORTH, Door.Hinge.LEFT, false, false, SpawnExceptionSpec.IconSide.BOTH);

        assertEquals(2, placements.size());
        assertEquals(BlockFace.NORTH, placements.get(0).outward());
        assertEquals(BlockFace.SOUTH, placements.get(1).outward());
        assertEquals(1.5, placements.get(0).dy());
        assertEquals(1.5, placements.get(1).dy());
        assertTrue(placements.get(0).dz() < 1.0 - MarkerGeometry.SLAB);
        assertTrue(placements.get(1).dz() > 1.0);
    }

    @Test
    void doorFrontSideRotatesWithHingeWhenOpened() {
        final List<MarkerGeometry.Placement> closed = MarkerGeometry.forDoor(
                BlockFace.NORTH, Door.Hinge.LEFT, false, false, SpawnExceptionSpec.IconSide.FRONT);
        final List<MarkerGeometry.Placement> opened = MarkerGeometry.forDoor(
                BlockFace.NORTH, Door.Hinge.LEFT, true, false, SpawnExceptionSpec.IconSide.FRONT);

        assertEquals(1, closed.size());
        assertEquals(BlockFace.NORTH, closed.getFirst().outward());
        assertEquals(1, opened.size());
        assertEquals(BlockFace.EAST, opened.getFirst().outward());
    }

    @Test
    void flatTrapdoorPlacesMarkersOnTopAndBottomAndRotatesWhenOpened() {
        final List<MarkerGeometry.Placement> flatBoth = MarkerGeometry.forTrapdoor(
                BlockFace.NORTH, Bisected.Half.BOTTOM, false, false, SpawnExceptionSpec.IconSide.BOTH);
        assertEquals(2, flatBoth.size());
        assertEquals(BlockFace.UP, flatBoth.get(0).outward());
        assertEquals(-90f, flatBoth.get(0).pitch());
        assertEquals(BlockFace.DOWN, flatBoth.get(1).outward());
        assertEquals(90f, flatBoth.get(1).pitch());

        final List<MarkerGeometry.Placement> openedFront = MarkerGeometry.forTrapdoor(
                BlockFace.NORTH, Bisected.Half.BOTTOM, true, false, SpawnExceptionSpec.IconSide.FRONT);
        assertEquals(1, openedFront.size());
        assertEquals(BlockFace.NORTH, openedFront.getFirst().outward());
        assertEquals(0.5, openedFront.getFirst().dy());
    }

    @Test
    void iconSideNoneProducesNoMarkers() {
        assertTrue(
                MarkerGeometry.forDoor(BlockFace.NORTH, Door.Hinge.LEFT, false, false, SpawnExceptionSpec.IconSide.NONE)
                        .isEmpty());
        assertTrue(MarkerGeometry.forTrapdoor(
                        BlockFace.NORTH, Bisected.Half.BOTTOM, false, false, SpawnExceptionSpec.IconSide.NONE)
                .isEmpty());
    }
}
