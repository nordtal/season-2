package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StationMenusTest {

    @Test
    void aRiderWhoClosesTheRideMenuWithoutARideGetsOut() {
        assertTrue(StationMenus.getsOut(false, false));
    }

    @Test
    void aPickedRideOrTheNextPageKeepsTheRiderSeated() {
        assertFalse(StationMenus.getsOut(true, false), "the cart is driven");
        assertFalse(StationMenus.getsOut(false, true), "another page of the ride menu shows");
    }
}
