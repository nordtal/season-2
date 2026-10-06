package eu.nordtal.season.messages;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Finds every message bundle in the repository and reads its files, for the tests that hold them all to a rule. */
final class BundleFiles {

    private BundleFiles() {}

    /** Returns every message bundle directory in the repository, with the language codes in it. */
    static Map<String, Set<String>> bundles() {
        final Map<String, Set<String>> found = new TreeMap<>();
        for (final Path module : childDirectories(RepositoryRoot.path())) {
            final Path messages = module.resolve("src/main/resources/messages");
            if (!Files.isDirectory(messages)) {
                continue;
            }
            for (final Path bundle : childDirectories(messages)) {
                found.put(RepositoryRoot.relative(bundle), languagesIn(bundle));
            }
        }
        return found;
    }

    /** Reads a bundle as UTF-8 through a {@link Reader}, since the {@code InputStream} overload reads Latin-1. */
    static Properties load(final String bundle, final String language) {
        final Properties properties = new Properties();
        final Path file = file(bundle, language);
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }

    /** Returns the lines of a bundle file as they stand on disk, comments included. */
    static List<String> lines(final String bundle, final String language) {
        final Path file = file(bundle, language);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static Path file(final String bundle, final String language) {
        return RepositoryRoot.resolve(bundle + "/" + language + ".properties");
    }

    private static Set<String> languagesIn(final Path bundle) {
        try (Stream<Path> files = Files.list(bundle)) {
            final Set<String> languages = new TreeSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".properties"))
                    .forEach(name -> languages.add(name.substring(0, name.length() - ".properties".length())));
            return languages;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + bundle, e);
        }
    }

    private static List<Path> childDirectories(final Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }
}
