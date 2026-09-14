package eu.nordtal.s2.steward.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing in Steward is German - the three services and the script that deploys them.
 *
 * <h2>Why a test and not a rule</h2>
 * It was a rule, it was carried out by hand, and it drifted. {@code Ampel} survived in
 * {@link eu.nordtal.s2.steward.ui.config.UiSpec}'s {@code @Comment} text, which is not an internal
 * note at all: jcore writes those comments into {@code steward-ui.yml}, so the one German word left
 * in this module was in the file an operator opens. The frontend had lost more than that -
 * {@code Strg} on two pages and {@code lang="de"} on the document - and its own guard lives next to
 * it in {@code frontend/src/language.test.ts}, because that is the runner that sees those files.
 *
 * <p>The bot is the bilingual half of this project and is deliberately not scanned. That is what
 * its language configuration is for, and its message bundles are German on purpose.</p>
 *
 * <h2>The word list is small on purpose, and there is no umlaut rule here</h2>
 * These are the words that actually appeared plus their nearest neighbours, not a dictionary. A
 * guard that cries wolf once is a guard somebody deletes - which is also why the frontend's
 * "no umlaut anywhere" rule is not repeated on this side. {@code LogFrames} explains chunked UTF-8
 * with the sentence "an \u00e4 in a death message", and that character is the subject of the
 * comment rather than a word of German: a rule that failed on it would be teaching the code to
 * describe bytes worse.
 */
class NothingIsGermanTest {

    private static final Pattern GERMAN = Pattern.compile(
            // The words that were actually still here, and then the function words a whole German
            // sentence is made of. Every one was checked against English first: `die`, `war`, `man`
            // and `mit` are English words too and are deliberately absent - one false positive is
            // what gets a guard deleted. The frontend's guard carries the same list.
            "\\b(Strg|Ampel|Lauf|Faktor|zweiten|gibt|kein|keine|keinen|keinem|keiner|nicht"
                    + "|nichts|und|oder|wird|werden|sind|wurde|wurden|ein|eine|einen|einem|eines"
                    + "|der|den|dem|des|das|diese|dieser|dieses|auch|aber|noch|schon|immer|jetzt"
                    + "|sehr|wenn|weil|dass|damit|bereits|Abbrechen|Anmelden|Willkommen|Fehler"
                    + "|Datei|Seite|Passwort|Benutzer|Anmeldung)\\b");

    /** What Steward is: three services and the one script that puts them on a host. */
    private static final List<String> TREES = List.of(
            "steward-ui/src", "steward-worker/src", "steward-deployer/src");
    private static final List<String> FILES = List.of(
            "deploy/setup.sh", "steward-worker/README.md", "steward-deployer/README.md",
            "deploy/README.md");

    @Test
    @DisplayName("no German word is in any of Steward's own files")
    void noGermanWord() {
        assertEquals(List.of(), offences(GERMAN),
                "Steward is English - the interface, the logs, the comments and the files it"
                        + " writes. The bot is the bilingual half and has its own bundles.");
    }

    private static List<String> offences(final Pattern pattern) {
        final Path root = repository();
        final List<Path> files = new ArrayList<>();
        for (final String tree : TREES) {
            final Path directory = root.resolve(tree);
            assertTrue(Files.isDirectory(directory), directory + " is not there any more, so this"
                    + " test was scanning nothing. Fix the path rather than the assertion.");
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        // This file is a list of German words, which is the whole of the exception.
                        .filter(path -> !path.getFileName().toString()
                                .equals("NothingIsGermanTest.java"))
                        .forEach(files::add);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        for (final String name : FILES) {
            final Path file = root.resolve(name);
            assertTrue(Files.isRegularFile(file), file + " is not there any more, so this test was"
                    + " scanning less than it says. Fix the path rather than the assertion.");
            files.add(file);
        }

        final List<String> found = new ArrayList<>();
        for (final Path file : files) {
            final List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            for (int number = 0; number < lines.size(); number++) {
                if (pattern.matcher(lines.get(number)).find()) {
                    found.add(root.relativize(file) + ":" + (number + 1) + ": "
                            + lines.get(number).strip());
                }
            }
        }
        return found;
    }

    /**
     * The repository root, found rather than assumed.
     *
     * <p>A test's working directory is its module, and every path here is relative to the root, so
     * getting this wrong would not fail - it would quietly scan nothing. Walking up to the file
     * that defines the build is the one landmark that cannot move.</p>
     */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
