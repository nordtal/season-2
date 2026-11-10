package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StationMenusTest {

    @Test
    void anAcceptedNameIsSavedAndEveryOtherVerdictRedrawsWithItsRefusal() {
        assertNull(StationMenus.refusal(StationNames.Verdict.ACCEPTED));
        assertEquals("name-empty", StationMenus.refusal(StationNames.Verdict.EMPTY));
        assertEquals("name-too-long", StationMenus.refusal(StationNames.Verdict.TOO_LONG));
        assertEquals("name-taken", StationMenus.refusal(StationNames.Verdict.TAKEN));
    }

    @Test
    void aSaveTogglesTheMapOnlyWhenTheCheckboxDiffersFromTheStation() {
        assertTrue(StationMenus.togglesHidden(true, true), "a hidden station the save shows");
        assertTrue(StationMenus.togglesHidden(false, false), "a shown station the save hides");
        assertFalse(StationMenus.togglesHidden(false, true));
        assertFalse(StationMenus.togglesHidden(true, false));
    }
}
