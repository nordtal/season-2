package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Whether a player is owed the pack, by the SHA-1 the client applied. */
class PackHashTest {

    private static final String OLD = "0".repeat(40);
    private static final String NEW = "1".repeat(40);

    @Test
    void aPlayerIsOwedEveryPackTheyHaveNotAppliedOrBeenSent() {
        assertTrue(PackHash.owed(false, null, null, NEW));
        assertTrue(PackHash.owed(false, OLD, OLD, NEW));
        assertFalse(PackHash.owed(false, NEW, NEW, NEW));
        assertFalse(PackHash.owed(false, OLD, NEW, NEW), "the offer still waits for its answer");
        assertFalse(PackHash.owed(true, null, null, NEW));
    }

    @Test
    void onlyTheNewestOfferAppliedSettlesThePack() {
        assertTrue(PackHash.settled(false, NEW, NEW));
        assertFalse(PackHash.settled(false, OLD, NEW));
        assertFalse(PackHash.settled(false, null, NEW));
        assertTrue(PackHash.settled(true, null, NEW));
    }
}
