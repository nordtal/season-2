package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.Letters;
import org.junit.jupiter.api.Test;

/** Verifies the ride list's 280 px panel sizing and row fit against its reviewed sheet. */
class RideDialogTest {

    @Test
    void theRideListUsesThe280PxPanelsForEmptySinglePageAndPagedStates() {
        assertEquals(DialogPanel.RIDE_LIST_33, RideDialog.panel(0));
        assertEquals(280, RideDialog.panel(0).width());
        assertEquals(33, RideDialog.panel(0).height());

        for (int count = 1; count <= RideDialog.EACH; count++) {
            final DialogPanel panel = RideDialog.panel(count);
            assertEquals(280, panel.width(), count + " stops");
            assertEquals(22 + 20 * count, panel.height(), count + " stops");
        }

        final DialogPanel paged = RideDialog.panel(RideDialog.EACH + 1);
        assertEquals(DialogPanel.RIDE_LIST_146, paged);
        assertEquals(280, paged.width());
        assertEquals(146, paged.height());
        assertEquals(paged.contentWidth(), RideDialog.WIDTH);
    }

    @Test
    void aThirtyTwoCharacterStationNameAndFiveDigitDistanceFitInOneRow() {
        final String longest = "Bernsteinbucht am Nordfjordhafen";
        assertEquals(32, longest.length());
        final int room = BodyLine.rowRoom(RideDialog.ROW, false, "12,400 m");
        assertTrue(Letters.visibleWidth(longest) <= room, "room " + room + " for " + Letters.visibleWidth(longest));
    }
}
