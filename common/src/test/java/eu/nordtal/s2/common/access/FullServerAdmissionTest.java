package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks when {@link FullServerAdmission} queries the admin flag and what it does with the answer. */
class FullServerAdmissionTest {

    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void anEmptyServerIsNotWorthAQueryAFullOneIs() {
        assertFalse(
                FullServerAdmission.worthAsking(0, 500),
                "an empty server asked the database whether the arriving player is an admin."
                        + " limbo is crossed by every login on the network; that is one query per"
                        + " login for an answer that cannot change anything.");
        assertTrue(
                FullServerAdmission.worthAsking(500, 500),
                "a server Paper will refuse this login on did not read the admin flag, so the"
                        + " admin coming to fix a full network is refused with \"Server full\"");
        assertTrue(
                FullServerAdmission.worthAsking(501, 500), "a server already over its cap did not read the admin flag");
    }

    @Test
    void theHeadroomCoversLoginsArrivingWhileTheDecisionIsInFlight() {
        // Exactly at the boundary, so a change to HEADROOM has to come here first.
        assertTrue(
                FullServerAdmission.worthAsking(500 - FullServerAdmission.HEADROOM, 500),
                "a login HEADROOM short of the cap was not considered, so the whole point of the"
                        + " constant is gone");
        assertFalse(
                FullServerAdmission.worthAsking(500 - FullServerAdmission.HEADROOM - 1, 500),
                "the headroom reaches further than it says it does");
    }

    @Test
    void anAdminIsAdmittedEveryTimeTheSameLoginIsChecked() {
        // Login validation can run twice for one login, so the answer must not change between the two.
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);

        assertTrue(admission.admits(ADMIN), "the warmed admin was not admitted to a full server");
        assertTrue(admission.admits(ADMIN), "the second check of one login got a different answer from the first");
        assertEquals(1, admission.size(), "reading the answer threw it away");
    }

    @Test
    void aPlayerNobodyWarmedAndOneWarmedAsNoAdminAreBothRefused() {
        final FullServerAdmission admission = new FullServerAdmission();

        assertFalse(
                admission.admits(PLAYER),
                "a login nobody looked up was let onto a full server - which is every login on a"
                        + " server that was not near its cap when they connected");

        admission.remember(PLAYER, false);
        assertEquals(0, admission.size(), "a non-admin is being held for the whole session");
        assertFalse(admission.admits(PLAYER), "a player read as no admin was let onto a full server");
    }

    @Test
    void rememberingFalseClearsAnEarlierTrue() {
        // A revoked admin reconnecting must not be admitted by the entry from the first connection.
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);
        admission.remember(ADMIN, false);

        assertFalse(admission.admits(ADMIN), "a revoked admin was still admitted to a full server");
    }

    @Test
    void forgetClearsAWarmedAnswerTheLoginNeverCameFor() {
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);
        admission.forget(ADMIN);

        assertEquals(0, admission.size(), "forget left the entry behind");
        assertFalse(admission.admits(ADMIN), "a forgotten answer still admitted a login");
    }
}
