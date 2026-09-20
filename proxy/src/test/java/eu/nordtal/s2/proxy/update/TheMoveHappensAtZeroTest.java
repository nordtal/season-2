package eu.nordtal.s2.proxy.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody is moved before the counter reaches zero, and nothing waits for a poll to notice.
 *
 * <p>season-2-ops/118. The evacuation ran off the five-second sweep with an eight-second head
 * start, so that one pass was guaranteed to land inside the window - and the price of that
 * guarantee was that players left their server while the counter still showed eight. Till,
 * 2026-09-20: <i>thrown out four seconds before the end of the countdown</i>. Measured on this host
 * the same day: the countdown was announced at 03:20:02 as twelve beats over 29.97s, and the move
 * happened at 03:20:26 - six seconds before the zero it had promised.</p>
 *
 * <p>The fix is two halves and each can be undone on its own, which is why both are asserted here
 * rather than left to the compiler. Taking the head start away without scheduling the sweep on zero
 * makes the move up to five seconds <em>late</em>; scheduling it without taking the head start away
 * changes nothing at all. Neither half fails a test that only knows about the other.</p>
 *
 * <p>These are source rules because the wiring is Velocity's scheduler and the thing being wired is
 * a method reference - there is no seam between them that does not need a running proxy, and a test
 * that needed one would not be run.</p>
 */
class TheMoveHappensAtZeroTest {

    @Test
    @DisplayName("the decision reads the running row only - there is no window left to be early in")
    void thereIsNoWindow() {
        final String source = read("proxy/src/main/java/eu/nordtal/s2/proxy/update/Evacuation.java");

        // `countingDown()` is the row whose instant has NOT passed. Reading it here is the head
        // start, whatever it is called and whatever number it is given.
        assertEquals(-1, body(source).indexOf("countingDown"),
                "Evacuation decides off the running row alone (season-2-ops/118): a countdown that"
                        + " has not run out yet is a countdown that can still be cancelled, and"
                        + " moving somebody for it is the thing Till saw");
        assertEquals(-1, body(source).indexOf("EVACUATE_BEFORE"),
                "the head start is gone, not renamed");
    }

    @Test
    @DisplayName("the countdown's zero beat moves them and parks them, in that order")
    void zeroRunsBothHalves() {
        final String plugin = read("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java");

        final int wired = plugin.indexOf("whenZeroReached(");
        assertTrue(wired > 0, "nothing is wired to the end of the countdown - without it the sweep"
                + " is the only trigger, and the sweep is " + RestartWatch.INTERVAL + " wide, so"
                + " the move would be that much LATE instead of early");

        final int moved = plugin.indexOf("this.evacuation.check()", wired);
        final int parked = plugin.indexOf("swap.check()", wired);
        assertTrue(moved > 0, "the evacuation is not on the zero beat");
        assertTrue(parked > 0, "the proxy swap is not on the zero beat - measured 2026-09-20 it was"
                + " two seconds late off its own sweep");
        assertTrue(moved < parked, "the order is the order a player travels: off the backends into"
                + " the waiting room first, then the whole network onto the standby proxy."
                + " Parking first would move everybody twice");

        // Both sweeps stay, behind it. They are the guarantee for scheduled tasks that never fired
        // - a proxy restarted mid-countdown has no tasks and must still move people.
        assertTrue(plugin.contains("this.evacuation::check") && plugin.contains("swap::check"),
                "the repeating sweeps are what catch a countdown whose scheduled beats were lost;"
                        + " they must not be removed just because the moment is wired");
    }

    @Test
    @DisplayName("what is scheduled for zero runs on the zero beat, not beside it")
    void theHookRunsOnTheBeat() {
        final String source =
                read("proxy/src/main/java/eu/nordtal/s2/proxy/update/RestartWatch.java");

        final int beat = source.indexOf("Announcement.Kind.NOW");
        final int hook = source.indexOf("atZero.run()");
        final int said = source.indexOf("say(beat.announcement())");
        assertTrue(beat > 0 && hook > 0 && said > 0, "the zero beat no longer looks like this");
        assertTrue(beat < hook && hook < said,
                "atZero belongs inside the NOW beat and before the announcement: the two are one"
                        + " event, and the half a player can be hurt by is the move");
    }

    @Test
    @DisplayName("the counts go fast during the countdown, not only once somebody is being moved")
    void theCountsAreFreshBeforeZero() {
        // The half of this that is easy to lose. steward-worker asks how many players are on a
        // service at the instant the counter reaches zero; the writer's ordinary cadence is ten
        // seconds. Hurrying from the move alone starts the fast cadence in the same instant as the
        // question, so the first answer is either stale - and the run waits its whole ten-second
        // cap for nothing - or lucky. Measured on this host 2026-09-20, before the fix: the proxy
        // was stopped inside the same second as the move, off a count nobody should have trusted.
        final String plugin = read("proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java");

        final int hurry = plugin.indexOf("whenHurrying(");
        assertTrue(hurry > 0, "the counts have no fast cadence at all");
        final int end = plugin.indexOf(";", hurry);
        final String signal = plugin.substring(hurry, end);
        assertTrue(signal.contains("isCountingDown"),
                "the fast cadence has to start with the countdown and not with the move: " + signal);
    }

    /** Everything after the class declaration, so a javadoc may still name what the code may not. */
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
