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
 * A world spawn is a coordinate, not a promise.
 *
 * <b>Why this is a rule and not a habit</b>
 *
 * A duel ending "at the spawn" can teleport a fighter into solid rock, out of the world, or into lava. A
 * player-built portal can need carrying someone out by hand, to somewhere survivable. The balloon's whole design is
 * "always the world spawn, so that a world spawn is a landmark everybody knows" - which fails exactly when the spawn
 * itself is not landable, dropping a player inside netherrack instead.
 *
 * {@link LandingSite#safeAt} costs nothing where the spot is already good - it takes the preferred location whenever
 * a player actually fits there - so a built world keeps its landmark exactly. It only does anything at all in the
 * case that used to be a death.
 *
 * <b>The allowlist</b>
 *
 * One file, and it is not a teleport: {@code NavigateGui} prints the spawn's three numbers into a line of text.
 * Anything added here has to be something that does not <em>move</em> a player.
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

    /**
     * Either helper counts, and the difference between them is what the caller does with nothing.
     *
     * {@code safeAt} ends with the preferred spot when its search comes back empty, which is right for a duel that has
     * to end. {@code findSafeAt} hands the emptiness back, which is right for the balloon: it can say the destination
     * is
     * unavailable instead of announcing an arrival nobody survives.
     */
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
