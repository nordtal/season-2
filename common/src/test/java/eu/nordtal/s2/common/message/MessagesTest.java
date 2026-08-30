package eu.nordtal.s2.common.message;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the message system's contract: locale resolution, EN fallback, and never throwing. */
class MessagesTest {

    private static final String ROOT = "messages/test";

    private static Messages messages() {
        return Messages.load(ROOT, Locale.GERMAN);
    }

    @Test
    void picksTheRequestedLanguage() {
        final Messages messages = messages();

        assertEquals("Hallo Till!", messages.format(Locale.GERMAN, "greeting", "name", "Till"));
        assertEquals("Hello Till!", messages.format(Locale.ENGLISH, "greeting", "name", "Till"));
    }

    @Test
    void onlyTheLanguageMattersSoDeAtAndDeDeShareABundle() {
        final Messages messages = messages();

        assertEquals("Keine Parameter hier.", messages.get(Locale.GERMANY, "plain"));
        assertEquals("Keine Parameter hier.", messages.get(Locale.forLanguageTag("de-AT"), "plain"));
    }

    @Test
    void fallsBackToEnglishForAnUntranslatedKey() {
        final Messages messages = messages();

        assertEquals("This key exists in English only.", messages.get(Locale.GERMAN, "only-english"));
        assertFalse(messages.hasTranslation(Locale.GERMAN, "only-english"));
        assertTrue(messages.hasTranslation(Locale.ENGLISH, "only-english"));
    }

    @Test
    void fallsBackToEnglishForALanguageWithNoBundleAtAll() {
        final Messages messages = messages();

        assertEquals("Hello Till!", messages.format(Locale.FRENCH, "greeting", "name", "Till"));
        assertEquals("Hello Till!", messages.format(null, "greeting", "name", "Till"));
        assertEquals(java.util.Set.of("en", "de"), messages.languages());
    }

    @Test
    void anUnknownKeyReturnsTheKeyRatherThanThrowing() {
        final Messages messages = messages();

        // Twice, because the "log it once" bookkeeping must not change the answer.
        assertEquals("no.such.key", messages.get(Locale.GERMAN, "no.such.key"));
        assertEquals("no.such.key", messages.get(Locale.GERMAN, "no.such.key"));
    }

    @Test
    void bundlesAreReadAsUtf8() {
        assertEquals("Grüße aus Nordtal - schöne Größe.", messages().get(Locale.GERMAN, "umlauts"));
    }

    @Test
    void anUnknownPlaceholderIsLeftInPlaceRatherThanBlanked() {
        assertEquals("Left {alone} and Till.",
                messages().format(Locale.ENGLISH, "braces", "name", "Till"));
    }

    @Test
    void aParameterValueContainingBracesIsNotRescanned() {
        // A player calling themselves {alone} must not be able to expand into another parameter.
        assertEquals("Left {alone} and {alone}.",
                messages().format(Locale.ENGLISH, "braces", Map.of("name", "{alone}")));
    }

    @Test
    void anOddNumberOfFormatArgumentsIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class,
                () -> messages().format(Locale.ENGLISH, "greeting", "name"));
    }

    @Test
    void aBundleWithoutItsEnglishFallbackFailsAtLoadTime() {
        assertThrows(IllegalStateException.class, () -> Messages.load("messages/does-not-exist"));
    }
}
