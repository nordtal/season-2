package eu.nordtal.s2.common;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks that a season plugin which cannot start shuts its server down.
 *
 * A text search, since it needs a running server: {@code disablePlugin} must be followed by
 * {@code shutdown}. Docker restarts nothing on health alone. Only these dedicated backends follow this
 * rule; {@code papermc-display-tags} runs on other people's servers and must not.
 */
class FatalPathsStopTheServerTest {

    /** The three Paper plugins. {@code proxy} is Velocity and fails closed differently. */
    private static final List<String> PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    @Test
    void everyPaperPluginsFatalPathDisablesItselfAndThenStopsTheServer() throws IOException {
        final List<String> wrong = new ArrayList<>();

        for (final String relative : PLUGINS) {
            final Path source = repositoryRoot().resolve(relative);
            assertTrue(Files.isRegularFile(source), source + " is not where this test expects it");
            final String text = Files.readString(source, StandardCharsets.UTF_8);

            final int disables = count(text, "disablePlugin(this)");
            final int shutdowns = count(text, "getServer().shutdown()");

            if (disables == 0) {
                wrong.add(relative + " does not disable itself on a fatal start");
            } else if (shutdowns == 0) {
                wrong.add(relative + " disables itself but leaves the server running - which is a"
                        + " backend with no season on it, up and reporting healthy");
            } else if (disables > shutdowns) {
                wrong.add(relative + " has " + disables + " disablePlugin call(s) and only " + shutdowns
                        + " shutdown(): one fatal path still leaves the server up");
            }
        }

        if (!wrong.isEmpty()) {
            fail(String.join("\n", wrong));
        }
    }

    private static int count(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    /** The directory holding {@code settings.gradle.kts}, not the nearest file by name. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
