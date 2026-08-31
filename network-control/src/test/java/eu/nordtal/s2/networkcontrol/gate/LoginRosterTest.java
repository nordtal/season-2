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
}
