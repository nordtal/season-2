package eu.nordtal.s2.common.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That no Paper plugin's {@code onDisable} has a step that can take the rest down with it.
 *
 * <p>A text search, like {@code ReadinessWiringTest}: the failure it guards ({@link Shutdown})
 * needs a jar replaced under a running JVM, which no test here can arrange. What it can see is the
 * shape - every zero-argument call on a field inside {@code onDisable} goes through
 * {@code quietly(...)}, and none is bare.</p>
 */
class DisableStepsAreIsolatedTest {

    private static final List<String> PAPER_PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    /** {@code    field.method();} on its own line - a disable step that is not wrapped. */
    private static final Pattern BARE_STEP = Pattern.compile("^\\s+(\\w+)\\.(\\w+)\\(\\);\\s*$", Pattern.MULTILINE);

    @Test
    @DisplayName("every disable step is wrapped in quietly(...)")
    void noBareStepInOnDisable() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);
            final int start = text.indexOf("    public void onDisable() {");
            assertTrue(start >= 0, relative + " has no onDisable");
            final int end = text.indexOf("\n    }\n", start);
            final String body = text.substring(start, end);
            final List<String> bare = new ArrayList<>();
            final Matcher matcher = BARE_STEP.matcher(body);
            while (matcher.find()) {
                bare.add(matcher.group(1) + "." + matcher.group(2) + "()");
            }
            assertEquals(List.of(), bare, relative + ": these disable steps are bare, so the first"
                    + " one that throws - a NoClassDefFoundError from a jar the updater has just"
                    + " replaced is the known way - skips every step after it");
            assertTrue(body.contains("quietly("), relative + ": onDisable wraps nothing");
        }
    }

    @Test
    @DisplayName("every plugin loads the guard at enable, while its jar still exists")
    void theGuardIsWarmedInOnEnable() throws IOException {
        // The guard's own class is loaded on first use, and its only use is in onDisable - so on
        // the first restart after a jar swap it threw ClassNotFoundException for itself and took
        // the whole sequence down at step one, which is worse than the failure it fixes. Seen on
        // the local stack the same day it was written (finding 115).
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);
            final int start = text.indexOf("    public void onEnable() {");
            assertTrue(start >= 0, relative + " has no onEnable");
            final String body = text.substring(start, text.indexOf("\n    }\n", start));
            assertTrue(body.contains("Shutdown.warmUp()"),
                    relative + ": onEnable does not call Shutdown.warmUp(), so the first disable"
                            + " step after a jar swap loads the guard from a jar that is gone");
        }
    }

    private static String read(final String relative) throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        final Path path = candidate.resolve(relative);
        assertTrue(Files.isRegularFile(path), relative + " no longer exists");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
