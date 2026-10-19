package eu.nordtal.season.proxy.ping;

import static eu.nordtal.season.proxy.ProxyRenderer.RENDERER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The server list's document: one line per phase under the brand mark.
 *
 * None carries a glyph, since the server list draws a text before any resource pack.
 */
class MotdTextTest {

    private static final ServerListContext LIST = new ServerListContext(12, 100, 8, 5, 16, 9, 7, 2, 8, 4200, 40);

    private static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.GERMAN);

    /** The text of every phase in one language. */
    private static List<String> all(final Locale locale) {
        final List<String> texts = new ArrayList<>();
        for (final SeasonPhase phase : SeasonPhase.values()) {
            texts.add(PlainTextComponentSerializer.plainText()
                    .serialize(RENDERER.format(
                            locale,
                            NetworkPing.SERVER_LIST.with(
                                    new NetworkPing.ServerList(phase, LIST, LaunchCountdown.Stage.DAYS, 3, 4, 0)))));
        }
        return texts;
    }

    @Test
    void everyPhaseGetsItsOwnTextRatherThanOneSharedLine() {
        for (final Locale locale : LOCALES) {
            final Set<String> distinct = new HashSet<>(all(locale));
            assertEquals(SeasonPhase.values().length, distinct.size(), "two phases share a text in " + locale);
        }
    }

    @Test
    void everyTextOpensWithTheOneBrandMarkOnALineOfItsOwn() {
        // One mark, and the phase is what the second line says: a name that changes colour is five marks.
        for (final Locale locale : LOCALES) {
            for (final String text : all(locale)) {
                assertTrue(text.startsWith("nordtal.eu\n"), "opens without the mark: " + text);
                assertEquals(text.indexOf("nordtal.eu"), text.lastIndexOf("nordtal.eu"), "repeats the name: " + text);
                assertEquals(2, text.split("\n").length, "the server list shows two lines: " + text);
            }
        }
    }

    @Test
    void noTextCarriesAGlyph() {
        final List<String> offenders = new ArrayList<>();
        for (final Locale locale : LOCALES) {
            for (final String text : all(locale)) {
                text.codePoints()
                        .filter(MotdTextTest::isPrivateUse)
                        .forEach(codePoint -> offenders.add(
                                "U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + " in " + text));
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "a private-use character in a MOTD is a box in the server browser: the client draws"
                        + " that list in its own font, before it has ever been offered the pack");
    }

    @Test
    void thePreLaunchLineCountsDown() {
        final String text = PlainTextComponentSerializer.plainText()
                .serialize(RENDERER.format(
                        Locale.ENGLISH,
                        NetworkPing.SERVER_LIST.with(new NetworkPing.ServerList(
                                SeasonPhase.PRE_LAUNCH, LIST, LaunchCountdown.Stage.HOURS, 0, 5, 12))));
        assertTrue(text.contains("5 hours 12 minutes"), text);
        assertFalse(text.contains("{"), text);
    }

    /** Both private-use areas, so a character pasted from an older file is still caught. */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
