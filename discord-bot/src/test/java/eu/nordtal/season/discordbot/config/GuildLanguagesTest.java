package eu.nordtal.season.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The language rules {@link GuildLanguages} owns, which the guild-facing classes cannot exercise without a guild. */
class LanguagesTest {

    private static final Languages NETWORK = new Languages(List.of("en", "de"));
    private static final GuildLanguages.Language EN =
            new GuildLanguages.Language("en", "English", "11", "12", "13", "14");
    private static final GuildLanguages.Language DE =
            new GuildLanguages.Language("de", "Deutsch", "21", "22", "23", "24");
    private static final GuildLanguages.Language FR =
            new GuildLanguages.Language("fr", "Français", "31", "32", "33", "");

    /** The two languages that exist today, in the order {@code DefaultLanguages} writes them. */
    private static GuildLanguages today() {
        return GuildLanguages.of(List.of(EN, DE), NETWORK);
    }

    @Test
    void theAnnouncementChannelIsTheSecondOptionalId() {
        // The six-id constructor has no announcement channel; the seventh id switches it on.
        assertFalse(EN.hasAnnouncementChannel());
        final GuildLanguages.Language withChannel =
                new GuildLanguages.Language("en", "English", "11", "12", "13", "14", "15");
        assertTrue(withChannel.hasAnnouncementChannel());
        assertEquals("15", withChannel.announcementChannelId());
        assertTrue(withChannel.hasStatusChannel());
    }

    @Test
    void aThirdLanguageNeedsNoCodeChangeRolesChannelsBundlesAndMessageKeys() {
        final GuildLanguages three = GuildLanguages.of(List.of(EN, DE, FR), new Languages(List.of("en", "de", "fr")));

        assertAll(
                () -> assertEquals(
                        Locale.FRENCH, three.byTag("fr").orElseThrow().locale()),
                () -> assertEquals("31", three.forLocale(Locale.FRENCH).contributionChannelId()),
                () -> assertEquals("32", three.forLocale(Locale.FRENCH).linkChannelId()),
                () -> assertEquals(3, three.all().size()),
                // Messages.load gets the whole list, so the French bundle is read without a code change.
                () -> assertArrayEquals(new Locale[] {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH}, three.locales()),
                () -> assertEquals("CONTRIBUTION_FR", FR.contributionKind()),
                () -> assertEquals("LINK_FR", FR.linkKind()));
    }

    @Test
    void theManagedMessageKeysOfEnAndDeAreUnchangedSoNoRowIsOrphaned() {
        // These four strings are the primary key of managed_message.
        assertAll(
                () -> assertEquals("CONTRIBUTION_EN", EN.contributionKind()),
                () -> assertEquals("CONTRIBUTION_DE", DE.contributionKind()),
                () -> assertEquals("LINK_EN", EN.linkKind()),
                () -> assertEquals("LINK_DE", DE.linkKind()));
    }

    @Test
    void aLocaleResolvesToItsOwnChannels() {
        assertAll(
                () -> assertEquals("11", today().forLocale(Locale.ENGLISH).contributionChannelId()),
                () -> assertEquals("21", today().forLocale(Locale.GERMAN).contributionChannelId()),
                () -> assertEquals("12", today().forLocale(Locale.ENGLISH).linkChannelId()),
                () -> assertEquals("22", today().forLocale(Locale.GERMAN).linkChannelId()),
                // discord_user.locale stores the language only, so de-AT is German.
                () -> assertEquals(
                        "21", today().forLocale(Locale.forLanguageTag("de-AT")).contributionChannelId()));
    }

    @Test
    void aLanguageThatIsNotConfiguredFallsBackToEnRatherThanToNothing() {
        // A tag whose entry was removed from the access group falls back to English.
        assertAll(
                () -> assertEquals("en", today().forLocale(Locale.FRENCH).tag()),
                () -> assertEquals("11", today().forLocale(Locale.FRENCH).contributionChannelId()),
                () -> assertEquals("en", today().forLocale(null).tag()),
                () -> assertEquals("en", today().fallback().tag()));
    }

    @Test
    void aTagIsLookedUpCaseInsensitivelyButOnlyLowerCaseTagsAreEverStored() {
        assertAll(
                () -> assertEquals("de", today().byTag("DE").orElseThrow().tag()),
                () -> assertTrue(today().byTag("fr").isEmpty()),
                () -> assertTrue(today().byTag(null).isEmpty()));
    }

    @Test
    void theNetworksOrderIsKeptBecauseItIsWhatEverythingElseWalks() {
        assertEquals(
                List.of("en", "fr", "de"),
                GuildLanguages.of(List.of(FR, DE, EN), new Languages(List.of("en", "fr", "de"))).all().stream()
                        .map(GuildLanguages.Language::tag)
                        .toList());
    }

    @Test
    void aListWithNoEntryForTheDefaultLanguageIsRefused() {
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> GuildLanguages.of(List.of(DE), NETWORK));
        assertTrue(error.getMessage().contains("no entry for 'en'"), error.getMessage());
    }

    @Test
    void aListWithAnEntryTheNetworkDoesNotSpeakIsRefused() {
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> GuildLanguages.of(List.of(EN, DE, FR), NETWORK));
        assertTrue(error.getMessage().contains("entry for 'fr'"), error.getMessage());
    }

    @Test
    void aListWithADuplicateTagIsRefused() {
        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> GuildLanguages.of(
                        List.of(EN, new GuildLanguages.Language("en", "English", "41", "42", "43", "44"), DE),
                        NETWORK));
        assertTrue(error.getMessage().contains("unique"), error.getMessage());
    }
}
