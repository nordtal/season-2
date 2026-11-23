package eu.nordtal.season.smp.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape;
import java.util.List;
import org.junit.jupiter.api.Test;

class AreaDraftTest {

    private static AreaDraft square() {
        return new AreaDraft(
                "nordtal",
                false,
                List.of(new AreaShape("Spawn", PolygonsTest.corners(0, 0, 10, 0, 10, 10, 0, 10), 0, 0)));
    }

    @Test
    void aCornerMovesAndAnEdgeTakesANewCornerAfterItsStart() {
        final AreaDraft draft = square();

        draft.move(0, 2, 12, 12);
        assertEquals(2, draft.insert(0, 1, 11, 5));

        assertEquals(
                PolygonsTest.corners(0, 0, 10, 0, 11, 5, 12, 12, 0, 10),
                draft.shape(0).corners());
    }

    @Test
    void aShapeKeepsItsLastThreeCorners() {
        final AreaDraft draft = square();

        assertTrue(draft.remove(0, 0));
        assertFalse(draft.remove(0, 0));

        assertEquals(PolygonsTest.corners(10, 0, 10, 10, 0, 10), draft.shape(0).corners());
    }

    @Test
    void aNewPolygonIsASquareAroundThePlayerWithHeightsOnlyWhereTheAreaHasThem() {
        final AreaDraft flat = square();
        final AreaDraft tall = new AreaDraft("duels", true, List.of());

        flat.add("Arena", 100, 64, 200);
        tall.add("Arena", 100, 64, 200);
        tall.height(0, true, 90);

        assertEquals(
                new AreaShape("Arena", Polygons.square(100, 200, AreaDraft.NEW_HALF), 0, 0),
                flat.shapes().get(1));
        assertEquals(new AreaShape("Arena", Polygons.square(100, 200, AreaDraft.NEW_HALF), 60, 90), tall.shape(0));
    }
}
