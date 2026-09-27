package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Nobody is moved before the counter reaches zero, and nothing waits for a poll to notice.
 *
 * Source rules, since the wiring is Velocity's scheduler and a method reference.
 */
class TheMoveHappensAtZeroTest {

    @Test
    void thereIsNoWindow() {
        final String source = read("proxy/src/main/java/eu/nordtal/s2/proxy/update/Evacuation.java");

        // `countingDown()` is the row whose instant has not passed, so reading it here is a head start.
        assertEquals(
                -1,
                body(source).indexOf("countingDown"),
                "Evacuation decides off the running row alone: a countdown that has not run out yet"
                        + " is a countdown that can still be cancelled, and moving somebody for it"
                        + " is exactly the bug this guards against");
        assertEquals(-1, body(source).indexOf("EVACUATE_BEFORE"), "the head start is gone, not renamed");
    }

    @Test
    void zeroRunsBothHalves() {
        final String plugin = read("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java");

        final int wired = plugin.indexOf("whenZeroReached(");
        assertTrue(
                wired > 0,
                "nothing is wired to the end of the countdown - without it the sweep"
                        + " is the only trigger, and the sweep is " + RestartWatch.INTERVAL + " wide, so"
                        + " the move would be that much LATE instead of early");

        final int moved = plugin.indexOf("this.evacuation.check()", wired);
        final int parked = plugin.indexOf("swap.check()", wired);
        assertTrue(moved > 0, "the evacuation is not on the zero beat");
        assertTrue(parked > 0, "the proxy swap is not on the zero beat - it was seen running late off its own sweep");
        assertTrue(
                moved < parked,
                "the order is the order a player travels: off the backends into"
                        + " the waiting room first, then the whole network onto the standby proxy."
                        + " Parking first would move everybody twice");

        // Both sweeps remain as the guarantee for a proxy restarted mid-countdown, which has no tasks.
        assertTrue(
                plugin.contains("this.evacuation::check") && plugin.contains("swap::check"),
                "the repeating sweeps are what catch a countdown whose scheduled beats were lost;"
                        + " they must not be removed just because the moment is wired");
    }

    @Test
    void theHookRunsOnTheBeat() {
        final String source = read("proxy/src/main/java/eu/nordtal/s2/proxy/update/RestartWatch.java");

        final int beat = source.indexOf("Announcement.Kind.NOW");
        final int hook = source.indexOf("atZero.run()");
        final int said = source.indexOf("say(beat.announcement())");
        assertTrue(beat > 0 && hook > 0 && said > 0, "the zero beat must keep this shape");
        assertTrue(
                beat < hook && hook < said,
                "atZero belongs inside the NOW beat and before the announcement: the two are one"
                        + " event, and the half a player can be hurt by is the move");
    }

    @Test
    void theCountsAreFreshBeforeZero() {
        // steward-worker counts players the instant the counter hits zero, but the writer's cadence is ten seconds.
        final String plugin = read("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java");

        final int hurry = plugin.indexOf("whenHurrying(");
        assertTrue(hurry > 0, "the counts have no fast cadence at all");
        final int end = plugin.indexOf(";", hurry);
        final String signal = plugin.substring(hurry, end);
        assertTrue(
                signal.contains("isCountingDown"),
                "the fast cadence has to start with the countdown and not with the move: " + signal);
    }

    /** Everything after the class declaration, so a doc may still name what the code may not. */
    private static String body(final String source) {
        final int at = source.indexOf("public final class Evacuation");
        assertTrue(at > 0, "Evacuation's class declaration has changed shape");
        return source.substring(at);
    }

    private static String read(final String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        try {
            return Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
