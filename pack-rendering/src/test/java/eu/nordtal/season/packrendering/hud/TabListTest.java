package eu.nordtal.season.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.messages.document.Document;
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
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Keeps the three servers' tab list frames alike, since the client carries one across servers.
 *
 * The header is paper-common's document, which every server shows. Each server has a footer document. The SMP's and
 * the Hunger Games' count the players and say the same. Only limbo's differs: everybody there is hidden, so a player
 * count would contradict the list.
 */
class TabListTest {

    private static final String HEADER = "paper-common/src/main/resources/documents/paper-common/tab-header.json";

    /** The servers that list their players, each with a footer document. */
    private static final List<String> COUNTED = List.of(
            "smp/src/main/resources/documents/smp/tab-footer.json",
            "hunger-games/src/main/resources/documents/hunger-games/tab-footer.json");

    private static final String LIMBO_FOOTER = "limbo/src/main/resources/documents/limbo/tab-footer.json";

    /** Each module's bundle directory, in the order a player meets them. */
    private static final Map<String, String> BUNDLES =
            new LinkedHashMap<>(Map.of("smp", "smp/src/main/resources/messages/smp"));

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
    void bothCountingFootersSayTheSame() {
        for (final String language : LANGUAGES) {
            final Set<String> footers = new TreeSet<>();
            for (final String document : COUNTED) {
                final String footer = text(document, language);
                assertTrue(
                        footer.contains("{online}") && footer.contains("{max}"),
                        document + " counts the players online against the network's limit");
                footers.add(footer);
            }
            assertEquals(1, footers.size(), "the SMP and the Hunger Games count their players differently");
        }
    }

    @Test
    void limbosFooterDeliberatelyCarriesNoPlayerCount() {
        for (final String language : LANGUAGES) {
            final String footer = text(LIMBO_FOOTER, language);
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
            final String header = text(HEADER, language);
            assertTrue(header.contains("<glyph:logo-large>"), "the tab.header has to place the logo by its name");
            header.codePoints()
                    .forEach(codePoint -> assertTrue(
                            !isPrivateUse(codePoint),
                            "paper-common's tab-header has a private-use character in " + language + "."
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

    private static String text(final String document, final String language) {
        try {
            return Document.decode(Files.readString(RepositoryRoot.resolve(document)))
                    .texts()
                    .getFirst()
                    .versions(language)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + document, e);
        }
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
