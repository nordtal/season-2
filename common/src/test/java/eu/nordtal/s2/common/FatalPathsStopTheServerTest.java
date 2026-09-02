package eu.nordtal.s2.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * That a season plugin which cannot start takes its server down with it.
 *
 * <h2>Why this is a text search and not a real test</h2>
 * Because the thing it protects cannot be reached from a JVM with no server in it, and the failure
 * it protects against is invisible from everywhere else. On the first deployment {@code smp}'s
 * config threw on every start, the plugin disabled itself, and Paper carried on: the container
 * stayed up, the jars were all in {@code plugins/} so the entrypoint's guard passed, the port was
 * open so the healthcheck passed, and what was actually running was a Minecraft server with no
 * season on it. Nothing outside the JVM can tell that state from a healthy one.
 *
 * <p>So the rule lives inside the plugin - {@code disablePlugin} is followed by {@code shutdown} -
 * and this asserts the rule is still written down. A grep is a weak test; it is also the only one
 * available, and it is strictly better than the nothing that was here before. Reintroducing the
 * bare {@code disablePlugin} is the regression, and this is what fails on it.</p>
 *
 * <h2>This is deliberately NOT the convention for every plugin</h2>
 * {@code papermc-display-tags} runs on servers that are not ours, and there "the plugin goes down,
 * the server keeps running" is right - a third-party plugin has no business stopping somebody
 * else's server. The three modules here are dedicated backends that exist to run one thing.
 */
class FatalPathsStopTheServerTest {

    /** The three Paper plugins. {@code network-control} is Velocity and fails closed differently. */
    private static final List<String> PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    @Test
    @DisplayName("every Paper plugin's fatal path disables itself and then stops the server")
    void aPluginThatCannotStartStopsTheServer() throws IOException {
        final List<String> wrong = new ArrayList<>();

        for (final String relative : PLUGINS) {
            final Path source = repositoryRoot().resolve(relative);
            assertTrue(Files.isRegularFile(source), source + " is not where this test expects it");
            final String text = Files.readString(source, StandardCharsets.UTF_8);

            final int disables = count(text, "disablePlugin(this)");
            final int shutdowns = count(text, "getServer().shutdown()");

            if (disables == 0) {
                wrong.add(relative + " no longer disables itself on a fatal start");
            } else if (shutdowns == 0) {
                wrong.add(relative + " disables itself but leaves the server running - which is a"
                        + " backend with no season on it, up and reporting healthy");
            } else if (disables > shutdowns) {
                wrong.add(relative + " has " + disables + " disablePlugin call(s) and only "
                        + shutdowns + " shutdown(): one fatal path still leaves the server up");
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
        throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
