package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The values behind {@code /discord} and {@code /rules}; {@code :commands} only picks the key. */
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
    void theRulesAreNotInServiceYet() {
        // A wrong value ships quietly, as a plausible answer nobody checks again.
        assertTrue(
                MESSAGES.get(Locale.ENGLISH, InfoTexts.RULES_TEXT.apply("").key())
                        .contains("PLACEHOLDER"),
                "the English rules text no longer says it is a placeholder");
        assertTrue(
                MESSAGES.get(Locale.GERMAN, InfoTexts.RULES_TEXT.apply("").key())
                        .contains("PLATZHALTER"),
                "the German rules text no longer says it is a placeholder - if the real rules have"
                        + " been written, delete this test with the same commit");
    }
}
