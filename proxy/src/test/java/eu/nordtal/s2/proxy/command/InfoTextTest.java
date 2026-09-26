package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The far half of {@code /discord} and {@code /rules}.
 *
 * <b>Why this test is in this module and not next to the commands.</b>
 * Because the seam runs between the two. {@code :commands} decides which key each command prints and
 * has a test for that; the value lives here, in the proxy's own bundle, because it wants a clickable
 * link and a colour and the shared bundle carries no markup at all. Neither module can see both
 * ends, so each pins its own - and without this half the failure is a player being shown the literal
 * string {@code info.rules}, which {@code Messages} produces rather than throwing.
 */
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
        // The one way this ships wrong is quietly, with a plausible answer nobody checks again; deleted with the text.
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
