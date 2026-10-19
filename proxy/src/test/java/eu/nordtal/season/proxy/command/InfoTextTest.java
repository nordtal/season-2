package eu.nordtal.season.proxy.command;

import static eu.nordtal.season.proxy.ProxyRenderer.RENDERER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.document.DocumentSpec;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The texts of {@code /discord} and {@code /rules}. */
class InfoTextTest {

    private static final String INVITE = "https://discord.gg/test-invite";

    private static final List<DocumentSpec<InfoTexts.Invite>> TEXTS = List.of(InfoTexts.DISCORD, InfoTexts.RULES);

    @Test
    void bothAreThereInBothLanguages() {
        for (final DocumentSpec<InfoTexts.Invite> text : TEXTS) {
            assertFalse(plain(Locale.ENGLISH, text).isBlank(), text.name());
            assertNotEquals(
                    plain(Locale.ENGLISH, text),
                    plain(Locale.GERMAN, text),
                    text.name() + " has no German text, so the command would print English at a German player");
        }
    }

    @Test
    void bothLinkTheInvite() {
        for (final DocumentSpec<InfoTexts.Invite> text : TEXTS) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(
                        opens(RENDERER.format(locale, text.with(new InfoTexts.Invite(INVITE)))),
                        text.name() + " (" + locale.getLanguage() + ") does not link the Discord"
                                + " invite. It is gate.yml's one copy of that link, and a text"
                                + " that spells the URL out itself is the copy that goes stale -"
                                + " read by exactly the people who cannot get in.");
            }
        }
    }

    @Test
    void theRulesAreWrittenNotAPlaceholder() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final String rules = plain(locale, InfoTexts.RULES).toUpperCase(Locale.ROOT);
            assertFalse(
                    rules.contains("PLACEHOLDER") || rules.contains("PLATZHALTER"),
                    "the " + locale.getLanguage() + " rules are a placeholder again");
        }
    }

    @Test
    void theRulesAreThreeSentencesAtMost() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final String text = plain(locale, InfoTexts.RULES);
            final long sentences =
                    Pattern.compile("[.!?:](\\s|$)").matcher(text).results().count();
            assertTrue(
                    sentences <= 3,
                    "the " + locale.getLanguage() + " rules run to " + sentences + " sentences: " + text);
        }
    }

    private static String plain(final Locale locale, final DocumentSpec<InfoTexts.Invite> text) {
        return PlainTextComponentSerializer.plainText()
                .serialize(RENDERER.format(locale, text.with(new InfoTexts.Invite(INVITE))));
    }

    /** Returns whether a part of the text opens the invite on a click. */
    private static boolean opens(final Component component) {
        final ClickEvent click = component.clickEvent();
        if (click != null
                && ClickEvent.Action.OPEN_URL.equals(click.action())
                && ((ClickEvent.Payload.Text) click.payload()).value().equals(INVITE)) {
            return true;
        }
        return component.children().stream().anyMatch(InfoTextTest::opens);
    }
}
