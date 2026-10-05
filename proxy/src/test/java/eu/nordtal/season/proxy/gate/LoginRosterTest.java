package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.MemberState;
import eu.nordtal.season.database.access.PlayerCard;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.messagerendering.NameCards;
import eu.nordtal.season.messages.value.DisplayName;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the login query may be remembered for.
 *
 * An account the roster has never heard of is not an admin, since {@code /phase} is authorised off this.
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
        assertEquals(
                DiscordId.of(DISCORD_ID),
                roster.of(PLAYER).orElseThrow().discordId(),
                "the Discord id the roster knows the player by");
        assertEquals(Locale.GERMAN, roster.localeOf(PLAYER));
    }

    @Test
    void losingTheFlagAtTheNextLoginLosesIt() {
        roster.remember(PLAYER, state(true, Locale.ENGLISH));
        roster.remember(PLAYER, state(false, Locale.ENGLISH));

        assertFalse(
                roster.isAdmin(PLAYER), "the flag is a permission mirrored from Discord, so losing the role loses it");
    }

    @Test
    void anUnlinkedStateIsNotRememberedAndEvictsAnyEarlierEntry() {
        roster.remember(PLAYER, state(true, Locale.ENGLISH));

        roster.remember(PLAYER, AccessState.unlinked(PLAYER, SeasonPhase.SMP));

        assertEquals(0, roster.size(), "there is no Discord id to remember, so there is no entry");
        assertFalse(roster.isAdmin(PLAYER));
    }

    @Test
    void thePackIsEnforcedForAnybodyTheLoginQueryDidNotExempt() {
        assertFalse(roster.isPackExempt(STRANGER), "a login the fallback cache answered is not in the roster");
        assertFalse(roster.isPackExempt(null));
        roster.remember(PLAYER, state(false, Locale.ENGLISH));
        assertFalse(roster.isPackExempt(PLAYER));
    }

    @Test
    void anExemptionComesFromTheLoginQueryAndSurvivesAnAdminRefresh() {
        roster.remember(
                PLAYER,
                new AccessState(
                        PLAYER,
                        DiscordId.of(DISCORD_ID),
                        MemberState.MEMBER,
                        true,
                        null,
                        false,
                        false,
                        true,
                        0L,
                        Locale.ENGLISH,
                        SeasonPhase.SMP,
                        null));
        assertTrue(roster.isPackExempt(PLAYER));
        roster.refreshAdmins(java.util.Set.of(DISCORD_ID));
        assertTrue(roster.isPackExempt(PLAYER), "refreshing the admin flag dropped the exemption");
    }

    @Test
    void aConnectedPlayerHasTheCardOfTheirLoginAndAnAdminRefreshReachesIt() {
        final Prestige prestige = Prestige.defaults();
        final NameCards cards = roster.cards(() -> prestige);
        roster.remember(
                PLAYER,
                new AccessState(
                        PLAYER,
                        DiscordId.of(DISCORD_ID),
                        MemberState.MEMBER,
                        true,
                        null,
                        true,
                        false,
                        false,
                        7200L,
                        Locale.ENGLISH,
                        SeasonPhase.SMP,
                        null));
        final DisplayName name = new DisplayName(PlayerId.of(PLAYER), "Alex");

        assertEquals(PlayerCard.of(name, false, true, 7200L, prestige), cards.card(name));
        roster.refreshAdmins(java.util.Set.of(DISCORD_ID));
        assertEquals(PlayerCard.of(name, true, true, 7200L, prestige), cards.card(name));
        assertNull(cards.card(new DisplayName(PlayerId.of(STRANGER), "Sam")), "not connected here");
    }

    @Test
    void anUnknownAccountsLocaleFallsBackToEnglish() {
        assertEquals(Locale.ENGLISH, roster.localeOf(STRANGER));
    }

    private static AccessState state(final boolean admin, final Locale locale) {
        return new AccessState(
                PLAYER,
                DiscordId.of(DISCORD_ID),
                MemberState.MEMBER,
                true,
                null,
                false,
                admin,
                false,
                0L,
                locale,
                SeasonPhase.SMP,
                null);
    }

    // revocation reaches a live session

    @Test
    void aRevokedAdminLosesItWhileStillConnected() {
        // An emergency revocation must not wait for a reconnect.
        roster.remember(PLAYER, state(true, Locale.GERMAN));
        assertTrue(roster.isAdmin(PLAYER));

        final int changed = roster.refreshAdmins(java.util.Set.of());

        assertEquals(1, changed);
        assertFalse(roster.isAdmin(PLAYER), "the revocation did not reach the live session");
    }

    @Test
    void aGrantedAdminGainsItAndKeepsEverythingElse() {
        roster.remember(PLAYER, state(false, Locale.GERMAN));

        assertEquals(1, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));

        assertTrue(roster.isAdmin(PLAYER));
        assertEquals(
                Locale.GERMAN,
                roster.localeOf(PLAYER),
                "language is not this refresh's business - it changes on a rhythm nobody needs told"
                        + " about in seconds, and the next login reads it again anyway");
        assertEquals(DiscordId.of(DISCORD_ID), roster.of(PLAYER).orElseThrow().discordId());
    }

    @Test
    void anUnchangedRefreshIsANoOp() {
        // It rides the poll and the notification, so it usually finds nothing to do.
        roster.remember(PLAYER, state(true, Locale.ENGLISH));

        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));
        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID)));
        assertTrue(roster.isAdmin(PLAYER));
        assertEquals(1, roster.size(), "a refresh must never add or drop a session");
    }

    @Test
    void aRefreshNeverInventsASession() {
        assertEquals(0, roster.refreshAdmins(java.util.Set.of(DISCORD_ID, "999")));
        assertEquals(0, roster.size());
        assertFalse(roster.isAdmin(PLAYER));
    }
}
