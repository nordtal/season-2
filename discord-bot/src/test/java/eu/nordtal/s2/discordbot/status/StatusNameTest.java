package eu.nordtal.s2.discordbot.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.network.NetworkSnapshot;
import eu.nordtal.s2.discordbot.DiscordRenderer;
import eu.nordtal.s2.messages.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The status channel name in every phase and at every distance from the opening, against the real bundles. */
class StatusNameTest {

    private static final DiscordRenderer MESSAGES =
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN));
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private static final NetworkSnapshot RUNNING =
            new NetworkSnapshot("RUNNING", 8, 3, 24, 7, 17, "NETHER", 40, 3, 8, 12_400L, 31);

    private static String at(final Duration untilLaunch) {
        return StatusName.render(
                MESSAGES, Locale.ENGLISH, SeasonPhase.PRE_LAUNCH, NetworkSnapshot.EMPTY, NOW.plus(untilLaunch), NOW);
    }

    @Test
    void moreThanADayOutShowsDaysAndWholeHours() {
        assertEquals("Opens in 3d 4h", at(Duration.ofDays(3).plusHours(4).plusMinutes(59)));
    }

    @Test
    void underADayTheMinutesAreDroppedBecauseTheyWouldCostSixtyRenamesAnHour() {
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
    void roundingIsDownSoTheCountdownNeverClaimsMoreTimeThanThereIs() {
        // 49 minutes reads as 40, so people arrive early.
        assertEquals("Opens in 40 min", at(Duration.ofMinutes(49)));
    }

    @Test
    void underTenMinutesIsAFixedLineThatCannotChangeAgain() {
        assertEquals("Opens any moment", at(Duration.ofMinutes(9)));
        assertEquals("Opens any moment", at(Duration.ofSeconds(1)));
    }

    @Test
    void aDateThatHasPassedIsNotANegativeNumber() {
        // A passed date is normal until an admin switches the phase.
        assertEquals("Opens any moment", at(Duration.ofHours(-6)));
    }

    @Test
    void noDateAtAllSaysSoRatherThanCountingFromNothing() {
        assertEquals(
                "Opening date to come",
                StatusName.render(MESSAGES, Locale.ENGLISH, SeasonPhase.PRE_LAUNCH, NetworkSnapshot.EMPTY, null, NOW));
    }

    @Test
    void preEventShowsWhoHasRegistered() {
        assertEquals("8 teams registered", render(SeasonPhase.PRE_EVENT));
    }

    @Test
    void theEventShowsWhoIsLeft() {
        assertEquals("3 teams left | 7 alive", render(SeasonPhase.START_EVENT));
    }

    @Test
    void theSmpShowsRegisteredPlayersDeliberatelyNotTheMilestone() {
        // Chosen over the milestone percentage, which moves faster than the rename budget.
        assertEquals("31 players", render(SeasonPhase.SMP));
    }

    @Test
    void maintenanceSaysSoAndReadsNoCounts() {
        assertEquals("Maintenance", render(SeasonPhase.MAINTENANCE));
    }

    @Test
    void everyPhaseRendersInGermanToo() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            final String german =
                    StatusName.render(MESSAGES, Locale.GERMAN, phase, RUNNING, NOW.plus(Duration.ofDays(2)), NOW);
            assertTrue(german != null && !german.isBlank(), phase + " has no German name");
            assertNotEquals(phase.name(), german, phase + " fell through to its own enum name");
        }
    }

    @Test
    void everyNameFitsInAChannelName() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                final String name =
                        StatusName.render(MESSAGES, locale, phase, RUNNING, NOW.plus(Duration.ofDays(365)), NOW);
                assertTrue(
                        name.length() <= StatusName.MAX_LENGTH,
                        phase + "/" + locale + " is " + name.length() + " characters: " + name);
            }
        }
    }

    private static String render(final SeasonPhase phase) {
        return StatusName.render(MESSAGES, Locale.ENGLISH, phase, RUNNING, null, NOW);
    }
}
