package eu.nordtal.season.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Keeps the three servers' tab list frames alike, since the client carries one across servers.
 *
 * The header and the counting footer are written once, in paper-common's bundle, which every server loads beneath its
 * own. Only limbo's footer differs: everybody there is hidden, so a player count would contradict the list.
 */
class TabListTest {

    private static final String PAPER_COMMON = "paper-common/src/main/resources/messages/paper-common";

    /** Each module's bundle directory, in the order a player meets them. */
    private static final Map<String, String> BUNDLES = new LinkedHashMap<>(Map.of(
            "limbo", "limbo/src/main/resources/messages/limbo",
            "hunger-games", "hunger-games/src/main/resources/messages/hunger-games",
            "smp", "smp/src/main/resources/messages/smp"));

    private static final List<String> LANGUAGES = List.of("en", "de");

    @Test
    void noServerWritesAHeaderOfItsOwn() {
        for (final String language : LANGUAGES) {
            BUNDLES.forEach((module, directory) -> assertNull(
                    value(directory, language, "tab.header"),
                    module + "/" + language + ".properties writes its own tab.header. The tab list is not"
                            + " cleared when a player changes server, so a second header is the header changing"
                            + " under them mid-walk; paper-common's is the one"));
        }
    }

    @Test
    void onlyLimboWritesAFooterOfItsOwn() {
        for (final String language : LANGUAGES) {
            BUNDLES.forEach((module, directory) -> {
                if (!module.equals("limbo")) {
                    assertNull(
                            value(directory, language, "tab.footer"),
                            module + "/" + language + ".properties writes its own tab.footer. Both servers that"
                                    + " list their players show paper-common's, so both count the same thing");
                }
            });
            final String footer = required(PAPER_COMMON, language, "tab.footer");
            assertTrue(
                    footer.contains("{online}") && footer.contains("{max}"),
                    "paper-common's footer counts the players online against the network's limit");
        }
    }

    @Test
    void limbosFooterDeliberatelyCarriesNoPlayerCount() {
        for (final String language : LANGUAGES) {
            final String footer = required(BUNDLES.get("limbo"), language, "tab.footer");
            assertTrue(
                    !footer.contains("{online}") && !footer.contains("{max}"),
                    "limbo hides every player from every other, so its list holds exactly one name."
                            + " A count in the footer would sit directly over a list of one."
                            + " If that changes, WaitingRoomRules#hideEverybodyFromEachOther is the"
                            + " thing to look at first");
        }
    }

    @Test
    void theHeaderNamesTheLogoNeverWritesItsCharacter() {
        for (final String language : LANGUAGES) {
            final String header = required(PAPER_COMMON, language, "tab.header");
            assertTrue(header.contains("<glyph:logo-large>"), "the tab.header has to place the logo by its name");
            header.codePoints()
                    .forEach(codePoint -> assertTrue(
                            !isPrivateUse(codePoint),
                            "paper-common/" + language + ".properties has a private-use character in tab.header."
                                    + " Those belong in the pack's glyph table and reach a text by name - written"
                                    + " into the file they are invisible text that survives until somebody's"
                                    + " editor normalises it"));
        }
    }

    /** Returns whether a code point is in any private-use area, including ones this pack does not use. */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF) // basic plane, retired
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD) // SPUA-A, where the pack lives
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD); // SPUA-B, unused
    }

    private static String required(final String directory, final String language, final String key) {
        final String value = value(directory, language, key);
        assertNotNull(value, directory + "/" + language + ".properties has no " + key);
        return value;
    }

    private static @Nullable String value(final String directory, final String language, final String key) {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(RepositoryRoot.resolve(directory + "/" + language + ".properties")),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + directory + "/" + language, e);
        }
        return properties.getProperty(key);
    }
}
