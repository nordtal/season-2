package eu.nordtal.season.proxy.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The values behind {@code /discord} and {@code /rules}. */
class InfoTextTest {

    private static final Messages MESSAGES =
            Messages.load(InfoTextTest.class.getClassLoader(), "messages/proxy", Locale.ENGLISH, Locale.GERMAN);

    private static final List<String> KEYS = List.of(
            InfoTexts.DISCORD_TEXT.apply("").key(),
            InfoTexts.RULES_TEXT.apply("").key());

    @Test
    void theKeysExist() {
        for (final String key : KEYS) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(
                        MESSAGES.hasTranslation(locale, key),
                        key + " is missing in " + locale.getLanguage() + ", so the command would"
                                + " print its own key at a player");
            }
        }
    }

    @Test
    void bothNameTheInvite() {
        for (final String key : KEYS) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(
                        MESSAGES.get(locale, key).contains("{invite}"),
                        key + " (" + locale.getLanguage() + ") does not substitute the Discord"
                                + " invite. It is gate.yml's one copy of that link, and a bundle"
                                + " that spells the URL out itself is the copy that goes stale -"
                                + " read by exactly the people who cannot get in.");
            }
        }
    }

    @Test
    void theRulesAreWrittenNotAPlaceholder() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final String rules =
                    MESSAGES.get(locale, InfoTexts.RULES_TEXT.apply("").key());
            assertFalse(
                    rules.toUpperCase(Locale.ROOT).contains("PLACEHOLDER")
                            || rules.toUpperCase(Locale.ROOT).contains("PLATZHALTER"),
                    "the " + locale.getLanguage() + " rules are a placeholder again");
        }
    }

    @Test
    void theRulesAreThreeSentencesAtMost() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final String text =
                    MESSAGES.get(locale, InfoTexts.RULES_TEXT.apply("").key()).replaceAll("<[^>]*>|\\{invite}", " ");
            final long sentences =
                    Pattern.compile("[.!?](\\s|$)").matcher(text).results().count();
            assertTrue(
                    sentences <= 3,
                    "the " + locale.getLanguage() + " rules run to " + sentences + " sentences: " + text);
        }
    }
}
