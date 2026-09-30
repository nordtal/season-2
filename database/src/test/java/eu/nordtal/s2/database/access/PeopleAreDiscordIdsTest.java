package eu.nordtal.s2.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that a person in this schema is a Discord id and a Minecraft UUID is never spelled as a string.
 *
 * A 36-character UUID overflows the {@code varchar(32)} person columns only at write time.
 */
class PeopleAreDiscordIdsTest {

    /** Every module that talks to a player and to the database. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "database/src/main/java",
            "paper-common/src/main/java",
            "smp/src/main/java",
            "limbo/src/main/java",
            "hunger-games/src/main/java",
            "proxy/src/main/java");

    private static final String FORBIDDEN = "getUniqueId().toString()";

    @Test
    void noMinecraftUuidIsTurnedIntoAStringOnItsWayToTheDatabase() {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : sources()) {
            final List<String> lines = read(source);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                final String trimmed = line.strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (line.contains(FORBIDDEN)) {
                    offenders.add(RepositoryRoot.relative(source) + ":" + (i + 1));
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "a person in this schema is a discord id in a varchar(32) column, and a Minecraft"
                        + " UUID printed as text is 36 characters - resolve the discord id"
                        + " (Identities#discordIdOf) or bind a real java.util.UUID to a uuid column");
    }

    private static List<Path> sources() {
        final List<Path> found = new ArrayList<>();
        for (final String root : SOURCE_ROOTS) {
            final Path directory = RepositoryRoot.resolve(root);
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException(root + " is not a directory - the roots have moved");
            }
            try (Stream<Path> tree = Files.walk(directory)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no sources found - the roots have moved");
        }
        return found;
    }

    private static List<String> read(final Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }
}
