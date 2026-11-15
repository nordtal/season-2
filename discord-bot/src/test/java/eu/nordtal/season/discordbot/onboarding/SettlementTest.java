package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettlementTest {
    private static final Choices CHOICES = choices("Europe/Berlin", "de");

    private static Choices choices(final String zone, final String language) {
        return new Choices(
                List.of(
                        new Choices.Choice(Choices.Kind.LANGUAGE, "en", "English", "en"),
                        new Choices.Choice(Choices.Kind.LANGUAGE, "de", "Deutsch", "de")),
                List.of(
                        new Choices.Choice(
                                Choices.Kind.COUNTRY, "germany", "Deutschland", "de", zone, language, "flag"),
                        new Choices.Choice(Choices.Kind.COUNTRY, "other", "Other", "other", "", "en", "flag")),
                "Lock");
    }

    private static Settlement settle(
            final Choices choices,
            final Set<String> held,
            final Set<String> chosen,
            final Records.Recorded recorded,
            final boolean locking) {
        return Settlement.of(choices, Optional::of, held, chosen, recorded, "lock", locking);
    }

    private static Records.Recorded german() {
        return new Records.Recorded("de", "de", "Europe/Berlin");
    }

    @Test
    void choosingACountryGivesItsLanguageAndRemovesTheLock() {
        final var result = settle(CHOICES, Set.of("lock"), Set.of("germany"), Records.Recorded.NONE, true);
        assertEquals(Set.of("germany", "de"), result.add());
        assertEquals(Set.of("lock"), result.remove());
        assertTrue(result.record().contains(new Settlement.Change(Choices.Kind.COUNTRY, "de", "Europe/Berlin")));
        assertTrue(result.record().contains(new Settlement.Change(Choices.Kind.LANGUAGE, "de")));
    }

    @Test
    void changingCountryKeepsAnExistingLanguage() {
        final var result = settle(CHOICES, Set.of("germany", "de"), Set.of("other"), german(), true);
        assertEquals(Set.of("other"), result.add());
        assertEquals(Set.of("germany"), result.remove());
        assertEquals(List.of(new Settlement.Change(Choices.Kind.COUNTRY, "other", null)), result.record());
    }

    @Test
    void removingALanguageByHandRestoresTheCountrysLanguage() {
        assertEquals(
                Set.of("de"),
                settle(CHOICES, Set.of("germany"), Set.of(), german(), true).add());
    }

    @Test
    void changingACountrysLanguageKeepsAHeldLanguage() {
        assertTrue(settle(choices("Europe/Berlin", "en"), Set.of("germany", "de"), Set.of(), german(), true)
                .isEmpty());
    }

    @Test
    void changingACountrysZoneUpdatesItsMembers() {
        assertEquals(
                List.of(new Settlement.Change(Choices.Kind.COUNTRY, "de", "Europe/Vienna")),
                settle(choices("Europe/Vienna", "de"), Set.of("germany", "de"), Set.of(), german(), true)
                        .record());
    }

    @Test
    void removingACountryClearsCountryAndZoneAndLocksItsMembers() {
        final var removed = new Choices(
                CHOICES.of(Choices.Kind.LANGUAGE),
                List.of(CHOICES.of(Choices.Kind.COUNTRY).getLast()),
                "Lock");
        final var result = settle(removed, Set.of("germany", "de"), Set.of(), german(), true);
        assertEquals(Set.of("lock"), result.add());
        assertEquals(List.of(new Settlement.Change(Choices.Kind.COUNTRY, null, null)), result.record());
    }

    @Test
    void lockOffRemovesTheLockEvenWithoutACountry() {
        assertEquals(
                Set.of("lock"),
                settle(CHOICES, Set.of("lock"), Set.of(), Records.Recorded.NONE, false)
                        .remove());
    }

    @Test
    void languageAloneDoesNotReleaseTheLock() {
        assertEquals(
                Set.of("lock"),
                settle(CHOICES, Set.of("en"), Set.of(), new Records.Recorded("en", null, null), true)
                        .add());
    }

    @Test
    void theAddedChoiceWinsThenTheRecordedOneThenTheFirstHeldOne() {
        final var offered = CHOICES.of(Choices.Kind.LANGUAGE);
        assertEquals(
                offered.getLast(),
                Choices.kept(offered, List.of(offered.getLast()), "en").orElseThrow());
        assertEquals(offered.getLast(), Choices.kept(offered, List.of(), "de").orElseThrow());
        assertEquals(offered.getFirst(), Choices.kept(offered, List.of(), null).orElseThrow());
    }

    @Test
    void choosingALanguageRemovesTheOtherOne() {
        final var result = settle(CHOICES, Set.of("germany", "de"), Set.of("en"), german(), true);
        assertEquals(Set.of("en"), result.add());
        assertEquals(Set.of("de"), result.remove());
    }
}
