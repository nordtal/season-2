package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that every message a Minecraft client reads is MiniMessage, with no legacy section code in it.
 *
 * That the code renders them through {@code MessageRenderer} is {@code :architecture}'s rule.
 */
class OneMessageFormatTest {

    /** Every module that renders messages to a Minecraft client. */
    private static final List<String> MODULES = List.of("smp", "limbo", "hunger-games", "proxy", "paper-common");

    @Test
    void noBundleCarriesALegacySectionCode() {
        final List<String> offenders = new ArrayList<>();
        for (final String module : MODULES) {
            for (final Path bundle : bundles(module)) {
                properties(bundle).forEach((key, value) -> {
                    if (String.valueOf(value).indexOf('§') >= 0) {
                        offenders.add(RepositoryRoot.relative(bundle) + " " + key);
                    }
                });
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "MiniMessage does not read section codes and never will - a value carrying one"
                        + " reaches the player with the code in it. Write the tag instead:"
                        + " §l is <bold>, §r is </bold> or the end of the component");
    }

    private static List<Path> bundles(final String module) {
        final Path root = RepositoryRoot.resolve(module + "/src/main/resources/messages");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".properties"))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static Properties properties(final Path path) {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return properties;
    }
}
