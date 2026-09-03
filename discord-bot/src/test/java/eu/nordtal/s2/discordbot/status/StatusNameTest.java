package eu.nordtal.s2.discordbot.status;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.NetworkSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the status channel is called, in every phase and at every distance from the opening.
 *
 * <p>Against the real message bundles, not a stub: half of what is asserted here is that the
 * placeholders in {@code en.properties} are the ones the renderer passes, which a stub bundle would
 * hide. The other half is the granularity, which <em>is</em> the Discord rate limit - a name that
 * changed more often than these tests allow would spend a budget of two renames per ten minutes in
 * the first minute of every hour.
 */
class StatusNameTest {

    private static final Messages MESSAGES = Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN);
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private static final NetworkSnapshot RUNNING = new NetworkSnapshot(
            "RUNNING", 8, 3, 24, 7, 17, "NETHER", 40, 3, 8, 12_400L, 31);

    private static String at(final Duration untilLaunch) {
        return StatusName.render(MESSAGES, Locale.ENGLISH, SeasonPhase.PRE_LAUNCH,
                NetworkSnapshot.EMPTY, NOW.plus(untilLaunch), NOW);
    }

    // ---------------------------------------------------------------- the countdown

    @Test
    void moreThanADayOutShowsDaysAndWholeHours() {
        assertEquals("Opens in 3d 4h", at(Duration.ofDays(3).plusHours(4).plusMinutes(59)));
    }

    @Test
    @DisplayName("under a day the minutes are dropped, because they would cost sixty renames an hour")
    void underADayShowsWholeHoursOnly() {
        assertEquals("Opens in 5h", at(Duration.ofHours(5).plusMinutes(59)));
        assertEquals("Opens in 1h", at(Duration.ofHours(1)));
    }

    @Test
    void theLastHourCountsDownInStepsOfTen() {
        assertEquals("Opens in 50 min", at(Duration.ofMinutes(59)));
        assertEquals("Opens in 50 min", at(Duration.ofMinutes(50)));
        assertEquals("Opens in 40 min", at(Duration.ofMinutes(49)));
        assertEquals("Opens in 10 min", at(Duration.ofMinutes(19)));
    }

    @Test
    @DisplayName("rounding is down, so the countdown never claims more time than there is")
    void theStepsUnderstateRatherThanOverstate() {
        // 49 minutes reads as 40, not as 50: somebody who leaves on this number arrives early.
        assertEquals("Opens in 40 min", at(Duration.ofMinutes(49)));
    }

    @Test
    void underTenMinutesIsAFixedLineThatCannotChangeAgain() {
        assertEquals("Opens any moment", at(Duration.ofMinutes(9)));
        assertEquals("Opens any moment", at(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a date that has passed is not a negative number")
    void aPassedInstantStillReadsAsImminent() {
        // Nothing switches the phase when the date passes - that stays an admin's decision - so the
        // gap between the announced instant and the switch is a normal state.
        assertEquals("Opens any moment", at(Duration.ofHours(-6)));
    }

    @Test
    void noDateAtAllSaysSoRatherThanCountingFromNothing() {
        assertEquals("Opening date to come", StatusName.render(MESSAGES, Locale.ENGLISH,
                SeasonPhase.PRE_LAUNCH, NetworkSnapshot.EMPTY, null, NOW));
    }

    // ---------------------------------------------------------------- the phase table

    @Test
    void preEventShowsWhoHasRegistered() {
        assertEquals("8 teams registered", render(SeasonPhase.PRE_EVENT));
    }

    @Test
    void theEventShowsWhoIsLeft() {
        // The surviving teams and the surviving players - hgTeamsAlive and hgAlive, not the totals.
        assertEquals("3 teams left | 7 alive", render(SeasonPhase.START_EVENT));
    }

    @Test
    @DisplayName("the SMP shows registered players, deliberately not the milestone")
    void theSmpShowsPlayers() {
        // Chosen 2026-09-03 over the active milestone and its percentage: a percentage moves on
        // every hand-in, and the channel has budget for two renames per ten minutes.
        assertEquals("31 players", render(SeasonPhase.SMP));
    }

    @Test
    void maintenanceSaysSoAndReadsNoCounts() {
        assertEquals("Maintenance", render(SeasonPhase.MAINTENANCE));
    }

    @Test
    void everyPhaseRendersInGermanToo() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final String german = StatusName.render(MESSAGES, Locale.GERMAN, phase, RUNNING,
                    NOW.plus(Duration.ofDays(2)), NOW);
            assertTrue(german != null && !german.isBlank(), phase + " has no German name");
            assertNotEquals(phase.name(), german, phase + " fell through to its own enum name");
        }
    }

    @Test
    void everyNameFitsInAChannelName() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                final String name = StatusName.render(MESSAGES, locale, phase, RUNNING,
                        NOW.plus(Duration.ofDays(365)), NOW);
                assertTrue(name.length() <= StatusName.MAX_LENGTH,
                        phase + "/" + locale + " is " + name.length() + " characters: " + name);
            }
        }
    }

    private static String render(final SeasonPhase phase) {
        return StatusName.render(MESSAGES, Locale.ENGLISH, phase, RUNNING, null, NOW);
    }
}
