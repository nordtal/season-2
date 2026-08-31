package eu.nordtal.s2.discordbot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that used to be spread across four classes as {@code roles.german} /
 * {@code roles.english} and four fixed channel keys.
 * <p>
 * Everything here is a rule {@link Languages} owns rather than JDA: {@code GuildState} hands it the
 * ids of the roles a member holds and writes whatever comes back, {@code ManagedMessages} walks
 * {@link Languages#all()}, and {@code PaymentProcessor} asks {@link Languages#forLocale(Locale)}
 * for a channel. None of those three can be exercised without a guild, which is exactly why the
 * decisions live here.
 * </p>
 * <p>
 * What these tests <b>cannot</b> prove: that a role id in a real {@code access.yml} is the role
 * Discord's onboarding actually assigns. A configured role that matches no role in the guild is
 * indistinguishable here from one nobody happens to hold - see
 * {@link #anIdThatMatchesNoRoleIsSimplyNeverHeld()}.
 * </p>
 */
class LanguagesTest {

    private static final Languages.Language EN = new Languages.Language("en", "10", "11", "12", "13");
    private static final Languages.Language DE = new Languages.Language("de", "20", "21", "22", "23");
    private static final Languages.Language FR = new Languages.Language("fr", "30", "31", "32", "33");

    /** The two languages that exist today, in the order {@code DefaultLanguages} writes them. */
    private static Languages today() {
        return Languages.of(List.of(EN, DE));
    }

    // ------------------------------------------------------------- mirroring a member's language

    @Test
    @DisplayName("the German role is mirrored as 'de'")
    void theGermanRoleIsMirroredAsGerman() {
        assertEquals(Locale.GERMAN, today().resolve(Set.of("20")).orElseThrow().locale());
    }

    @Test
    @DisplayName("the English role is mirrored as 'en'")
    void theEnglishRoleIsMirroredAsEnglish() {
        assertEquals(Locale.ENGLISH, today().resolve(Set.of("10")).orElseThrow().locale());
    }

    @Test
    @DisplayName("no language role writes nothing at all")
    void noLanguageRoleResolvesToNothing() {
        // Not "English": the column already defaults to English, and writing it would overwrite a
        // real choice made while onboarding was mid-flight. Empty is what GuildState turns into
        // "leave whatever is stored".
        assertAll(
                () -> assertTrue(today().resolve(Set.of("99", "98")).isEmpty()),
                () -> assertTrue(today().resolve(Set.of()).isEmpty()),
                () -> assertTrue(today().resolve(null).isEmpty())
        );
    }

    @Test
    @DisplayName("holding both English and German is German - the fallback loses to a real choice")
    void theFallbackLosesToAnyOtherLanguage() {
        // This is the rule the fixed-key version had: "taking German over English when somebody
        // holds both". It has to survive the move to a list, and it has to survive 'en' being the
        // FIRST entry of that list, which is how DefaultLanguages writes it.
        assertEquals("de", today().resolve(Set.of("10", "20")).orElseThrow().tag());
    }

    @Test
    @DisplayName("between two non-fallback languages the configured order wins")
    void configuredOrderBreaksATieBetweenRealChoices() {
        // NOT settled by docs/i18n.md - see Languages#resolve. Configured order is the answer this
        // code picked, and the test exists to make the choice visible rather than accidental.
        final Languages deFirst = Languages.of(List.of(EN, DE, FR));
        final Languages frFirst = Languages.of(List.of(EN, FR, DE));

        assertAll(
                () -> assertEquals("de", deFirst.resolve(Set.of("20", "30")).orElseThrow().tag()),
                () -> assertEquals("fr", frFirst.resolve(Set.of("20", "30")).orElseThrow().tag())
        );
    }

    @Test
    @DisplayName("a configured role id that matches no role in the guild is simply never held")
    void anIdThatMatchesNoRoleIsSimplyNeverHeld() {
        // There is nothing else this layer can do: it is handed the ids a member holds, and an id
        // nobody holds is the same shape whether the role was deleted, mistyped, or just unpopular.
        // A mistyped language role therefore means "that language is never mirrored", silently.
        assertTrue(today().resolve(Set.of("does-not-exist")).isEmpty());
    }

    @Test
    @DisplayName("only the configured language roles are worth re-reading a member for")
    void onlyConfiguredRolesAreLanguageRoles() {
        assertAll(
                () -> assertTrue(today().isLanguageRole("10")),
                () -> assertTrue(today().isLanguageRole("20")),
                () -> assertFalse(today().isLanguageRole("21"), "that is a channel id, not a role id"),
                () -> assertFalse(today().isLanguageRole("30"))
        );
    }

    // ------------------------------------------------------------- a third language, no code change

    @Test
    @DisplayName("a third language needs no code change: roles, channels, bundles and message keys")
    void aThirdLanguageIsPurelyConfiguration() {
        final Languages three = Languages.of(List.of(EN, DE, FR));

        assertAll(
                () -> assertEquals("fr", three.resolve(Set.of("30")).orElseThrow().tag()),
                () -> assertEquals(Locale.FRENCH, three.resolve(Set.of("30")).orElseThrow().locale()),
                () -> assertEquals("31", three.forLocale(Locale.FRENCH).contributionChannelId()),
                () -> assertEquals("32", three.forLocale(Locale.FRENCH).linkChannelId()),
                () -> assertTrue(three.isLanguageRole("30")),
                () -> assertEquals(3, three.all().size()),
                // Messages.load gets the whole list, so the French bundle is read without anybody
                // editing AccessBot. A missing fr.properties degrades to English with one warning.
                () -> assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH},
                        three.locales()),
                () -> assertEquals("CONTRIBUTION_FR", FR.contributionKind()),
                () -> assertEquals("LINK_FR", FR.linkKind())
        );
    }

    @Test
    @DisplayName("the managed message keys of en and de are unchanged, so no row is orphaned")
    void theExistingManagedMessageKeysAreUnchanged() {
        // These four strings were an enum and are the primary key of managed_message. If deriving
        // them from the tag produced anything else, the first restart after this change would post
        // four duplicate messages next to the four it can no longer find.
        assertAll(
                () -> assertEquals("CONTRIBUTION_EN", EN.contributionKind()),
                () -> assertEquals("CONTRIBUTION_DE", DE.contributionKind()),
                () -> assertEquals("LINK_EN", EN.linkKind()),
                () -> assertEquals("LINK_DE", DE.linkKind())
        );
    }

    // ------------------------------------------------------------- looking a language up

    @Test
    @DisplayName("a locale resolves to its own channels")
    void aLocaleResolvesToItsOwnChannels() {
        assertAll(
                () -> assertEquals("11", today().forLocale(Locale.ENGLISH).contributionChannelId()),
                () -> assertEquals("21", today().forLocale(Locale.GERMAN).contributionChannelId()),
                () -> assertEquals("12", today().forLocale(Locale.ENGLISH).linkChannelId()),
                () -> assertEquals("22", today().forLocale(Locale.GERMAN).linkChannelId()),
                // de-AT is German: discord_user.locale stores the language only, and Locales.tag
                // is what both sides go through.
                () -> assertEquals("21", today().forLocale(Locale.forLanguageTag("de-AT"))
                        .contributionChannelId())
        );
    }

    @Test
    @DisplayName("a language that is not configured falls back to 'en' rather than to nothing")
    void anUnconfiguredLanguageFallsBackToTheFallback() {
        // A tag left in discord_user.locale by an entry since removed from access.yml. The donation
        // thank-you goes to the English channel; posting it nowhere would be worse.
        assertAll(
                () -> assertEquals("en", today().forLocale(Locale.FRENCH).tag()),
                () -> assertEquals("11", today().forLocale(Locale.FRENCH).contributionChannelId()),
                () -> assertEquals("en", today().forLocale(null).tag()),
                () -> assertEquals("en", today().fallback().tag())
        );
    }

    @Test
    @DisplayName("a tag is looked up case-insensitively, but only lower-case tags are ever stored")
    void tagLookupIsCaseInsensitive() {
        assertAll(
                () -> assertEquals("de", today().byTag("DE").orElseThrow().tag()),
                () -> assertTrue(today().byTag("fr").isEmpty()),
                () -> assertTrue(today().byTag(null).isEmpty())
        );
    }

    @Test
    @DisplayName("the configured order is preserved, because it is what everything else walks")
    void theConfiguredOrderIsPreserved() {
        assertEquals(List.of("fr", "en", "de"),
                Languages.of(List.of(FR, EN, DE)).all().stream().map(Languages.Language::tag).toList());
    }

    // ------------------------------------------------------------- what it refuses to be built from

    @Test
    @DisplayName("a list with no 'en' entry is refused")
    void aListWithoutEnglishIsRefused() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Languages.of(List.of(DE, FR)));
        assertTrue(error.getMessage().contains("fallback"), error.getMessage());
    }

    @Test
    @DisplayName("a list with a duplicate tag is refused")
    void aListWithADuplicateTagIsRefused() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Languages.of(List.of(EN, new Languages.Language("en", "40", "41", "42", "43"))));
        assertTrue(error.getMessage().contains("unique"), error.getMessage());
    }

    @Test
    @DisplayName("an empty list is refused")
    void anEmptyListIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Languages.of(List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Languages.of((List<Languages.Language>) null))
        );
    }
}
