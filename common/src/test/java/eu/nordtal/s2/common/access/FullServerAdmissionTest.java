package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions in {@link FullServerAdmission}: when the admin flag is worth a query, and what
 * happens to the answer afterwards.
 * <p>
 * Both matter because the class exists to be right on the one login nobody rehearses - an admin
 * arriving at a network that is already full, which is the moment the network needs them. Neither
 * decision can be exercised on a running server without first filling it.
 * </p>
 */
class FullServerAdmissionTest {

    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("an empty server is not worth a query, a full one is")
    void worthAskingFollowsTheCap() {
        assertFalse(FullServerAdmission.worthAsking(0, 500),
                "an empty server asked the database whether the arriving player is an admin."
                        + " limbo is crossed by every login on the network; that is one query per"
                        + " login for an answer that cannot change anything.");
        assertTrue(FullServerAdmission.worthAsking(500, 500),
                "a server Paper will refuse this login on did not read the admin flag, so the"
                        + " admin coming to fix a full network is refused with \"Server full\"");
        assertTrue(FullServerAdmission.worthAsking(501, 500),
                "a server already over its cap did not read the admin flag");
    }

    @Test
    @DisplayName("the headroom covers logins arriving while the decision is in flight")
    void worthAskingLeavesHeadroom() {
        // The count is read on the pre-login thread and acted on at PlayerLoginEvent; players join
        // in between. Exactly at the boundary, so a change to HEADROOM has to come here first.
        assertTrue(FullServerAdmission.worthAsking(500 - FullServerAdmission.HEADROOM, 500),
                "a login HEADROOM short of the cap was not considered, so the whole point of the"
                        + " constant is gone");
        assertFalse(FullServerAdmission.worthAsking(500 - FullServerAdmission.HEADROOM - 1, 500),
                "the headroom reaches further than it says it does");
    }

    @Test
    @DisplayName("an admin is admitted every time the same login is checked")
    void admitsDoesNotConsume() {
        // Paper's own note on the deprecated PlayerLoginEvent says the login validation runs twice
        // for one login, so the fullness check can be asked twice. An answer that changed between
        // the two would refuse the admin it had just admitted, and nothing would log it.
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);

        assertTrue(admission.admits(ADMIN), "the warmed admin was not admitted to a full server");
        assertTrue(admission.admits(ADMIN),
                "the second check of one login got a different answer from the first");
        assertEquals(1, admission.size(), "reading the answer threw it away");
    }

    @Test
    @DisplayName("a player nobody warmed, and one warmed as no admin, are both refused")
    void nonAdminsAreNotAdmitted() {
        final FullServerAdmission admission = new FullServerAdmission();

        assertFalse(admission.admits(PLAYER),
                "a login nobody looked up was let onto a full server - which is every login on a"
                        + " server that was not near its cap when they connected");

        admission.remember(PLAYER, false);
        assertEquals(0, admission.size(), "a non-admin is being held for the whole session");
        assertFalse(admission.admits(PLAYER), "a player read as no admin was let onto a full server");
    }

    @Test
    @DisplayName("remembering false clears an earlier true")
    void rememberOverwrites() {
        // The path that makes this matter: an admin joins while the server is full, the flag is
        // revoked in Discord, they reconnect. remember(uuid, false) has to be able to undo itself,
        // or the entry from the first connection admits the second one.
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);
        admission.remember(ADMIN, false);

        assertFalse(admission.admits(ADMIN), "a revoked admin was still admitted to a full server");
    }

    @Test
    @DisplayName("forget clears a warmed answer the login never came for")
    void forgetClears() {
        final FullServerAdmission admission = new FullServerAdmission();
        admission.remember(ADMIN, true);
        admission.forget(ADMIN);

        assertEquals(0, admission.size(), "forget left the entry behind");
        assertFalse(admission.admits(ADMIN), "a forgotten answer still admitted a login");
    }
}
