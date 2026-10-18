package eu.nordtal.season.discordbot.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
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
        assertEquals("3 teams left, 7 alive", render(SeasonPhase.START_EVENT));
    }

    @Test
    void theSmpShowsRegisteredPlayersDeliberatelyNotTheMilestone() {
        // Chosen over the milestone percentage, which moves faster than the rename budget.
        assertEquals("31 players", render(SeasonPhase.SMP));
        assertEquals(
                "1,200 players",
                StatusName.render(
                        MESSAGES,
                        Locale.ENGLISH,
                        SeasonPhase.SMP,
                        new NetworkSnapshot(8, 3, 24, 7, 17, "NETHER", 40, 3, 8, 12_400L, 1200),
                        null,
                        NOW));
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
                        name.length() <= Channel.MAX_NAME_LENGTH,
                        phase + "/" + locale + " is " + name.length() + " characters: " + name);
            }
        }
    }

    @Test
    void aPhaseIsAnnouncedByItsPhraseInTheReadersLanguage() {
        assertEquals(
                "The network phase is now **Season running** (it was **Before the event**).",
                announce(Locale.ENGLISH, SeasonPhase.SMP, SeasonPhase.PRE_EVENT));
        assertEquals(
                "Die Netzwerkphase ist jetzt **Saison läuft** (vorher **Vor dem Event**).",
                announce(Locale.GERMAN, SeasonPhase.SMP, SeasonPhase.PRE_EVENT));
    }

    @Test
    void noPhaseIsAnnouncedByItsEnumKey() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            for (final SeasonPhase phase : SeasonPhase.values()) {
                final String sentence = announce(locale, phase, phase);
                for (final SeasonPhase key : SeasonPhase.values()) {
                    assertFalse(
                            sentence.contains(
                                    key.name().toLowerCase(Locale.ROOT).replace('_', '-')),
                            locale + " announces " + phase + " as a raw key: " + sentence);
                }
            }
        }
    }

    private static String announce(final Locale locale, final SeasonPhase phase, final SeasonPhase previous) {
        return MESSAGES.plain(StatusChannels.ANNOUNCE, new StatusChannels.Phase(phase, previous), locale);
    }

    private static String render(final SeasonPhase phase) {
        return StatusName.render(MESSAGES, Locale.ENGLISH, phase, RUNNING, null, NOW);
    }
}
