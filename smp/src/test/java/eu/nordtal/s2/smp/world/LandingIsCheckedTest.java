package eu.nordtal.s2.smp.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * A world spawn is a coordinate, not a promise: every teleport to one goes through {@link LandingSite}.
 *
 * The allowlist names only files that never move a player.
 */
class LandingIsCheckedTest {

    private static final String ROOT = "smp/src/main/java";

    /** Files allowed to name a world spawn without routing it through a landing check. */
    private static final List<String> ALLOWED = List.of(
            // The class that implements the check, and whose own fallback is the raw spawn.
            "smp/src/main/java/eu/nordtal/s2/smp/world/LandingSite.java",
            // Reads the three coordinates into a message; moves nobody.
            "smp/src/main/java/eu/nordtal/s2/smp/navigate/NavigateGui.java");

    @Test
    void noRawWorldSpawn() {
        final List<String> raw = new ArrayList<>();
        for (final Path source : sources()) {
            final String relative = relative(source);
            if (ALLOWED.contains(relative)) {
                continue;
            }
            final List<String> lines = read(source);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                final String trimmed = line.strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (line.contains("getSpawnLocation()") && !checked(line)) {
                    raw.add(relative + ":" + (i + 1));
                }
            }
        }
        assertEquals(
                List.of(),
                raw,
                "a world spawn reached a player without a landing check - wrap it in"
                        + " LandingSite.safeAt(world, world.getSpawnLocation()), which is free when"
                        + " the spot is already good and is the difference between arriving and"
                        + " suffocating when it is not; a caller that may refuse the trip takes"
                        + " findSafeAt instead and uses its own \"not available\" branch");
    }

    /** Either helper counts: {@code safeAt} falls back to the spot, {@code findSafeAt} hands back empty. */
    private static boolean checked(final String line) {
        return line.contains("LandingSite.safeAt(") || line.contains("findSafeAt(");
    }

    private static String relative(final Path source) {
        return repositoryRoot().relativize(source).toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }

    private static List<Path> sources() {
        final Path directory = repositoryRoot().resolve(ROOT);
        final List<Path> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(found::add);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot walk " + ROOT, e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException(ROOT + " holds no sources - it has moved");
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
