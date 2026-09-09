package eu.nordtal.s2.networkcontrol.command;

import eu.nordtal.s2.commands.info.InfoCommands;
import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far half of {@code /discord} and {@code /rules}.
 *
 * <h2>Why this test is in this module and not next to the commands</h2>
 * Because the seam runs between the two. {@code :commands} decides which key each command prints and
 * has a test for that; the value lives here, in the proxy's own bundle, because it wants a clickable
 * link and a colour and the shared bundle carries no markup at all. Neither module can see both
 * ends, so each pins its own - and without this half the failure is a player being shown the literal
 * string {@code info.rules}, which {@code Messages} produces rather than throwing.
 */
class InfoTextTest {

    private static final Messages MESSAGES = Messages.load(InfoTextTest.class.getClassLoader(),
            "messages/network-control", Locale.ENGLISH, Locale.GERMAN);

    @Test
    @DisplayName("both keys the commands name exist, in both languages")
    void theKeysExist() {
        for (final String key : List.of(InfoCommands.DISCORD_TEXT, InfoCommands.RULES_TEXT)) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(MESSAGES.hasTranslation(locale, key),
                        key + " is missing in " + locale.getLanguage() + ", so the command would"
                                + " print its own key at a player");
            }
        }
    }

    @Test
    @DisplayName("both texts carry the invite, because that is the one thing they are for")
    void bothNameTheInvite() {
        for (final String key : List.of(InfoCommands.DISCORD_TEXT, InfoCommands.RULES_TEXT)) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(MESSAGES.get(locale, key).contains("{invite}"),
                        key + " (" + locale.getLanguage() + ") does not substitute the Discord"
                                + " invite. It is gate.yml's one copy of that link, and a bundle"
                                + " that spells the URL out itself is the copy that goes stale -"
                                + " read by exactly the people who cannot get in.");
            }
        }
    }

    @Test
    @DisplayName("the rules are still marked as a placeholder in both languages")
    void theRulesAreNotInServiceYet() {
        // Till writes the rules (todo.md A10). Until then the one way this ships wrong is quietly:
        // a /rules that answers with something plausible is a /rules nobody checks again. When the
        // real text lands, this test is deleted in the same commit - it is a reminder with a build
        // behind it, not a rule about the wording.
        assertTrue(MESSAGES.get(Locale.ENGLISH, InfoCommands.RULES_TEXT).contains("PLACEHOLDER"),
                "the English rules text no longer says it is a placeholder");
        assertTrue(MESSAGES.get(Locale.GERMAN, InfoCommands.RULES_TEXT).contains("PLATZHALTER"),
                "the German rules text no longer says it is a placeholder - if the real rules have"
                        + " been written, delete this test with the same commit");
    }
}
