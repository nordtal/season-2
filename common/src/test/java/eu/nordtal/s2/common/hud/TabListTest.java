package eu.nordtal.s2.common.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Keeps the three servers' tab list frames from becoming three pictures.
 *
 * The tab list is the one surface a player carries unchanged across limbo, the hunger games and
 * the SMP: the client never clears it, each backend simply overwrites it. So a difference between
 * the three is not a difference somebody sees side by side and judges - it is the header jumping
 * while a player walks through a portal, which reads as a glitch rather than as a design. The
 * composition is shared ({@link TabList}); the wording is three files, and this is what holds
 * them together.
 *
 * <b>limbo's footer is the one permitted difference</b>, and it is asserted as a difference
 * rather than tolerated as one: everybody there is hidden from everybody else, so the list above
 * the footer holds exactly one name and a player count would contradict it.
 */
class TabListTest {

    /** module -> its bundle directory, in the order a player meets them. */
    private static final Map<String, String> BUNDLES = new LinkedHashMap<>(Map.of(
            "limbo", "limbo/src/main/resources/messages/limbo",
            "hunger-games", "hunger-games/src/main/resources/messages/hunger-games",
            "smp", "smp/src/main/resources/messages/smp"));

    private static final List<String> LANGUAGES = List.of("en", "de");

    @Test
    void allThreeServersWriteTheSameTabListHeader() {
        for (final String language : LANGUAGES) {
            final Map<String, String> headers = new LinkedHashMap<>();
            BUNDLES.forEach((module, directory) -> headers.put(module, value(directory, language, "tab.header")));

            final String smp = headers.get("smp");
            headers.forEach((module, header) -> assertEquals(
                    smp,
                    header,
                    module + "/" + language + ".properties writes a different tab.header from"
                            + " smp's. The tab list is not cleared when a player changes server, so"
                            + " a difference here is the header changing under them mid-walk"));
        }
    }

    @Test
    void theSmpAndTheHungerGamesWriteTheSameFooter() {
        for (final String language : LANGUAGES) {
            assertEquals(
                    value(BUNDLES.get("smp"), language, "tab.footer"),
                    value(BUNDLES.get("hunger-games"), language, "tab.footer"),
                    "both servers show a real player list, so both footers count the same thing");
        }
    }

    @Test
    void limbosFooterDeliberatelyCarriesNoPlayerCount() {
        for (final String language : LANGUAGES) {
            final String footer = value(BUNDLES.get("limbo"), language, "tab.footer");
            assertTrue(
                    !footer.contains("{online}") && !footer.contains("{max}"),
                    "limbo hides every player from every other, so its list holds exactly one name."
                            + " A count in the footer would sit directly over a list of one."
                            + " If that changes, PresenceListener#hideEverybodyFromEachOther is the"
                            + " thing to look at first");
        }
    }

    @Test
    void theHeaderTakesTheLogoAsAParameterNeverAsACharacterInTheFile() {
        for (final String language : LANGUAGES) {
            BUNDLES.forEach((module, directory) -> {
                final String header = value(directory, language, "tab.header");
                assertTrue(header.contains("{logo}"), module + "'s tab.header has to place the logo through {logo}");
                header.codePoints()
                        .forEach(codePoint -> assertTrue(
                                !isPrivateUse(codePoint),
                                module + "/" + language + ".properties has a private-use character in"
                                        + " tab.header. Those belong in Glyphs and reach a bundle as a"
                                        + " parameter - written into the file they are invisible text that"
                                        + " survives until somebody's editor normalises it"));
            });
        }
    }

    /** Returns whether a code point is in any private-use area, including ones this pack does not use. */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF) // basic plane, retired
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD) // SPUA-A, where the pack lives
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD); // SPUA-B, unused
    }

    private static String value(final String directory, final String language, final String key) {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(RepositoryRoot.resolve(directory + "/" + language + ".properties")),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + directory + "/" + language, e);
        }
        final String value = properties.getProperty(key);
        assertNotNull(value, directory + "/" + language + ".properties has no " + key);
        return value;
    }
}
