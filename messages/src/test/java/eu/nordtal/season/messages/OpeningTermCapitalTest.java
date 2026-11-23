package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Text;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Node;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every packaged text that opens with a term block binds {@code capital=first}. */
class OpeningTermCapitalTest {

    /** Keys whose text continues a figure or embeds inside a sentence, rather than opening one. */
    private static final Set<String> CONTINUATIONS = Set.of(
            "messages/src/main/resources/messages/values/en.properties values.mode",
            "messages/src/main/resources/messages/values/de.properties values.mode",
            "steward/src/main/resources/messages/steward/en.properties steward.router.players",
            "steward/src/main/resources/messages/steward/en.properties steward.network.players-online");

    @Test
    void everyBundleTextOpeningWithATermBlockBindsCapitalFirst() {
        final Set<String> uncapitalised = new TreeSet<>();
        BundleFiles.bundles().forEach((bundle, languages) -> {
            for (final String language : languages) {
                final Properties texts = BundleFiles.load(bundle, language);
                for (final String key : texts.stringPropertyNames()) {
                    final String id = bundle + "/" + language + ".properties " + key;
                    if (CONTINUATIONS.contains(id)) {
                        continue;
                    }
                    if (opensWithUncapitalisedTerm(texts.getProperty(key), true)) {
                        uncapitalised.add(id);
                    }
                }
            }
        });
        assertEquals(
                Set.of(),
                uncapitalised,
                "a packaged text opening with a term block must bind capital=first so an admin's lower-case"
                        + " override still starts with a capital");
    }

    @Test
    void everyDocumentTextOpeningWithATermBlockBindsCapitalFirst() {
        final Set<String> uncapitalised = new TreeSet<>();
        for (final Path file : documents()) {
            final Document document = Document.decode(read(file));
            final String path = RepositoryRoot.relative(file);
            for (final Text text : document.texts()) {
                text.languages().forEach((language, versions) -> {
                    for (final String version : versions) {
                        if (opensWithUncapitalisedTerm(version, true)) {
                            uncapitalised.add(path + " [" + language + "]: " + version);
                        }
                    }
                });
            }
        }
        assertEquals(
                Set.of(), uncapitalised, "a packaged document text opening with a term block must bind capital=first");
    }

    @Test
    void anOpeningTermWithoutCapitalFirstIsCaughtWhileMidSentenceTermsPass() {
        assertTrue(opensWithUncapitalisedTerm("{@terms.season} launched", true));
        assertTrue(opensWithUncapitalisedTerm("<warn>{@terms.access} expired {since}.</warn>", true));
        assertTrue(opensWithUncapitalisedTerm(
                "{action, select, launch-now {{@terms.season} launched} other {{action}}}", true));
        assertFalse(opensWithUncapitalisedTerm("{@terms.season, capital=first} launched", true));
        assertFalse(opensWithUncapitalisedTerm("<warn>{@terms.access, capital=first} expired {since}.</warn>", true));
        assertFalse(opensWithUncapitalisedTerm("Launched the {@terms.season} now.", true));
        assertFalse(opensWithUncapitalisedTerm("Switch {@terms.game-mode}", true));
    }

    private static boolean opensWithUncapitalisedTerm(final String source, final boolean markup) {
        return opensWithUncapitalisedTerm(MessageText.parse(source, markup).nodes());
    }

    private static boolean opensWithUncapitalisedTerm(final List<Node> nodes) {
        for (final Node node : nodes) {
            switch (node) {
                case Node.Tag ignored -> {
                    // Opening MiniMessage tags precede the first visible word.
                }
                case Node.Literal literal -> {
                    if (!literal.text().isBlank()) {
                        return false;
                    }
                }
                case Node.Block block -> {
                    return block.key().startsWith(Messages.TERMS + ".") && !block.capital();
                }
                case Node.Choice choice -> {
                    for (final List<Node> branch : choice.cases().values()) {
                        if (opensWithUncapitalisedTerm(branch)) {
                            return true;
                        }
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    private static List<Path> documents() {
        final Path root = RepositoryRoot.path();
        try (Stream<Path> modules = Files.list(root)) {
            return modules.filter(Files::isDirectory)
                    .map(module -> module.resolve("src/main/resources/documents"))
                    .filter(Files::isDirectory)
                    .flatMap(directory -> jsonFiles(directory).stream())
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + root, e);
        }
    }

    private static List<Path> jsonFiles(final Path directory) {
        try (Stream<Path> walked = Files.walk(directory)) {
            return walked.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + directory, e);
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
