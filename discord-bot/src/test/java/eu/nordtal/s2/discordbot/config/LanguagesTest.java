package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The language rules {@link Languages} owns, which the guild-facing classes cannot exercise without a guild. */
class LanguagesTest {

    private static final Languages.Language EN = new Languages.Language("en", "English", "11", "12", "13", "14");
    private static final Languages.Language DE = new Languages.Language("de", "Deutsch", "21", "22", "23", "24");
    private static final Languages.Language FR = new Languages.Language("fr", "Français", "31", "32", "33", "");

    /** The two languages that exist today, in the order {@code DefaultLanguages} writes them. */
    private static Languages today() {
        return Languages.of(List.of(EN, DE));
    }

    @Test
    void theAnnouncementChannelIsTheSecondOptionalId() {
        // The six-id constructor has no announcement channel; the seventh id switches it on.
        assertFalse(EN.hasAnnouncementChannel());
        final Languages.Language withChannel = new Languages.Language("en", "English", "11", "12", "13", "14", "15");
        assertTrue(withChannel.hasAnnouncementChannel());
        assertEquals("15", withChannel.announcementChannelId());
        assertTrue(withChannel.hasStatusChannel());
    }

    @Test
    void aThirdLanguageNeedsNoCodeChangeRolesChannelsBundlesAndMessageKeys() {
        final Languages three = Languages.of(List.of(EN, DE, FR));

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
    void theConfiguredOrderIsPreservedBecauseItIsWhatEverythingElseWalks() {
        assertEquals(
                List.of("fr", "en", "de"),
                Languages.of(List.of(FR, EN, DE)).all().stream()
                        .map(Languages.Language::tag)
                        .toList());
    }

    @Test
    void aListWithNoEnEntryIsRefused() {
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Languages.of(List.of(DE, FR)));
        assertTrue(error.getMessage().contains("fallback"), error.getMessage());
    }

    @Test
    void aListWithADuplicateTagIsRefused() {
        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Languages.of(List.of(EN, new Languages.Language("en", "English", "41", "42", "43", "44"))));
        assertTrue(error.getMessage().contains("unique"), error.getMessage());
    }

    @Test
    void anEmptyListIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Languages.of(List.of())),
                () -> assertThrows(
                        IllegalArgumentException.class, () -> Languages.of((List<Languages.Language>) null)));
    }
}
