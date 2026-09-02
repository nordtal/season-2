package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the login query is allowed to be remembered for. The one assertion that matters is that an
 * account the roster has never heard of is <b>not</b> an admin: {@code /phase} is authorised off
 * this, and a lookup that defaulted the other way would hand the emergency phase switch to
 * everybody.
 */
class LoginRosterTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000001";

    private LoginRoster roster;

    @BeforeEach
    void freshRoster() {
        roster = new LoginRoster();
    }

    @Test
    void anAccountNobodyHasLoggedInIsNotAnAdmin() {
        assertFalse(roster.isAdmin(STRANGER));
        assertFalse(roster.isAdmin(null));
        assertTrue(roster.of(STRANGER).isEmpty());
    }

    @Test
    void theAdminFlagComesFromTheLoginQueryAndNowhereElse() {
        roster.remember(PLAYER, state(true, Locale.GERMAN));

        assertTrue(roster.isAdmin(PLAYER));
        assertEquals(DISCORD_ID, roster.of(PLAYER).orElseThrow().discordId(),
                "the actor written into audit_log by /phase set");
        assertEquals(Locale.GERMAN, roster.localeOf(PLAYER));
    }

    @Test
    void losingTheFlagAtTheNextLoginLosesIt() {
        roster.remember(PLAYER, state(true, Locale.ENGLISH));
        roster.remember(PLAYER, state(false, Locale.ENGLISH));

        assertFalse(roster.isAdmin(PLAYER),
                "the flag is a permission mirrored from Discord, so losing the role loses it");
    }

    @Test
    void anUnlinkedStateIsNotRememberedAndEvictsAnyEarlierEntry() {
        roster.remember(PLAYER, state(true, Locale.ENGLISH));

        roster.remember(PLAYER, AccessState.unlinked(PLAYER, SeasonPhase.SMP));

        assertEquals(0, roster.size(), "there is no Discord id to remember, so there is no entry");
        assertFalse(roster.isAdmin(PLAYER));
    }

    @Test
    void anUnknownAccountsLocaleFallsBackToEnglish() {
        assertEquals(Locale.ENGLISH, roster.localeOf(STRANGER));
    }

    private static AccessState state(final boolean admin, final Locale locale) {
        return new AccessState(PLAYER, DISCORD_ID, MemberState.MEMBER, true, null, false, admin,
                locale, SeasonPhase.SMP);
    }

    // ---------------------------------------------------------------- M9: revocation reaches a live session

    @Test
    @org.junit.jupiter.api.DisplayName("M9: losing the role in Discord loses it in game without a reconnect")
    void aRevokedAdminLosesItWhileStillConnected() {
        // The roster was filled at login and never touched again, so this player kept /phase on the
        // proxy and /smp on the backend until they disconnected. An emergency revocation is exactly
        // the case where waiting for a reconnect is the wrong direction.
        roster.remember(PLAYER, state(true, Locale.GERMAN));
        assertTrue(roster.isAdmin(PLAYER));

        final int changed = roster.refreshAdmins(java.util.Set.of());

        assertEquals(1, changed);
        assertFalse(roster.isAdmin(PLAYER), "the revocation did not reach the live session");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("M9: a grant reaches a live session too, and nothing else moves")
    void aGrantedAdminGainsItAndKeepsEverythingElse() {
        roster.remember(PLAYER, state(false, Locale.GERMAN));

        assertEquals(1, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));

        assertTrue(roster.isAdmin(PLAYER));
        assertEquals(Locale.GERMAN, roster.localeOf(PLAYER),
                "language is not this refresh's business - it changes on a rhythm nobody needs told"
                        + " about in seconds, and the next login reads it again anyway");
        assertEquals(DISCORD_ID, roster.of(PLAYER).orElseThrow().discordId());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("M9: a refresh that changes nothing says so, and is safe to run on a timer")
    void anUnchangedRefreshIsANoOp() {
        // It rides the 30-second poll as well as the notification, so the ordinary case is that it
        // finds nothing to do - and it has to be cheap and quiet when it does.
        roster.remember(PLAYER, state(true, Locale.ENGLISH));

        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));
        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));
        assertTrue(roster.isAdmin(PLAYER));
        assertEquals(1, roster.size(), "a refresh must never add or drop a session");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("M9: a player nobody knows is not created by a refresh")
    void aRefreshNeverInventsASession() {
        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID, "999")));
        assertEquals(0, roster.size());
        assertFalse(roster.isAdmin(PLAYER));
    }

}
