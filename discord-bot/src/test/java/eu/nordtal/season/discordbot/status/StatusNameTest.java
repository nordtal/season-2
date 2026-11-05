package eu.nordtal.season.discordbot.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.entities.channel.Channel;
import org.junit.jupiter.api.Test;

class StatusNameTest {

    private static final DocumentRenderer MESSAGES = StoredDocumentsStandIn.renderer();
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private static final NetworkSnapshot RUNNING =
            new NetworkSnapshot(8, 3, 24, 7, 17, "NETHER", 40, 3, 8, 12_400L, 31);

    private static final SeasonState CLOSED = new SeasonState(null, false, SeasonMode.HUNGER_GAMES);
    private static final SeasonState LAUNCHED = CLOSED.withLaunch(NOW.minus(Duration.ofDays(1)));

    /** Every state the gate tells apart, the countdown two days out. */
    private static final List<SeasonState> STATES = List.of(
            CLOSED,
            CLOSED.withLaunch(NOW.plus(Duration.ofDays(2))),
            LAUNCHED.withMaintenance(true),
            LAUNCHED.withMode(SeasonMode.SMP),
            LAUNCHED);

    private static String at(final Duration untilLaunch) {
        return StatusName.render(
                MESSAGES, Locale.ENGLISH, CLOSED.withLaunch(NOW.plus(untilLaunch)), NetworkSnapshot.EMPTY, NOW);
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
    void aDateThatHasPassedOpensTheSeasonByItself() {
        assertEquals("3 teams left, 7 alive", render(CLOSED.withLaunch(NOW.minus(Duration.ofSeconds(1)))));
    }

    @Test
    void noDateAtAllSaysSoRatherThanCountingFromNothing() {
        assertEquals("Opening date to come", render(CLOSED));
    }

    @Test
    void aClosedSeasonInMaintenanceStillShowsTheClosedSeason() {
        assertEquals("Opening date to come", render(CLOSED.withMaintenance(true)));
        assertEquals("Opens in 2d 0h", render(CLOSED.withMaintenance(true).withLaunch(NOW.plus(Duration.ofDays(2)))));
    }

    @Test
    void theHungerGamesShowWhoHasRegisteredUntilTheFirstElimination() {
        assertEquals(
                "8 teams registered",
                StatusName.render(
                        MESSAGES,
                        Locale.ENGLISH,
                        LAUNCHED,
                        new NetworkSnapshot(8, 8, 24, 24, 0, "", 0, 0, 0, 0L, 31),
                        NOW));
    }

    @Test
    void theHungerGamesShowWhoIsLeftOnceAnyoneIsEliminated() {
        assertEquals(
                "3 teams left, 7 alive",
                StatusName.render(
                        MESSAGES,
                        Locale.ENGLISH,
                        LAUNCHED,
                        new NetworkSnapshot(8, 3, 24, 7, 17, "", 0, 0, 0, 0L, 31),
                        NOW));
    }

    @Test
    void theSmpShowsRegisteredPlayersDeliberatelyNotTheMilestone() {
        // Chosen over the milestone percentage, which moves faster than the rename budget.
        final SeasonState smp = LAUNCHED.withMode(SeasonMode.SMP);
        assertEquals("31 players", render(smp));
        assertEquals(
                "1,200 players",
                StatusName.render(
                        MESSAGES,
                        Locale.ENGLISH,
                        smp,
                        new NetworkSnapshot(8, 3, 24, 7, 17, "NETHER", 40, 3, 8, 12_400L, 1200),
                        NOW));
    }

    @Test
    void maintenanceOfALaunchedSeasonSaysSoInEitherMode() {
        assertEquals("Maintenance", render(LAUNCHED.withMaintenance(true)));
        assertEquals("Maintenance", render(LAUNCHED.withMaintenance(true).withMode(SeasonMode.SMP)));
    }

    @Test
    void onlyAnOpenModeReadsTheCounts() {
        assertTrue(StatusName.needsCounts(LAUNCHED, NOW));
        assertTrue(StatusName.needsCounts(LAUNCHED.withMode(SeasonMode.SMP), NOW));
        assertFalse(StatusName.needsCounts(LAUNCHED.withMaintenance(true), NOW));
        assertFalse(StatusName.needsCounts(CLOSED, NOW));
        assertFalse(StatusName.needsCounts(CLOSED.withLaunch(NOW.plus(Duration.ofHours(1))), NOW));
    }

    @Test
    void aSeasonThatCannotBeReadShowsMaintenance() {
        assertEquals("Maintenance", render(SeasonState.UNREAD));
    }

    @Test
    void everyStateRendersInGermanToo() {
        final List<String> german = STATES.stream()
                .map(state -> StatusName.render(MESSAGES, Locale.GERMAN, state, RUNNING, NOW))
                .toList();
        assertEquals(
                List.of("Starttermin folgt", "Start in 2T 0Std", "Wartung", "31 Spieler", "3 Teams übrig, 7 am Leben"),
                german);
    }

    @Test
    void everyNameFitsInAChannelName() {
        for (final SeasonState state : STATES) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                final String name = StatusName.render(MESSAGES, locale, state, RUNNING, NOW);
                assertTrue(
                        name.length() <= Channel.MAX_NAME_LENGTH,
                        state + "/" + locale + " is " + name.length() + " characters: " + name);
            }
        }
    }

    private static String render(final SeasonState state) {
        return StatusName.render(MESSAGES, Locale.ENGLISH, state, RUNNING, NOW);
    }
}
