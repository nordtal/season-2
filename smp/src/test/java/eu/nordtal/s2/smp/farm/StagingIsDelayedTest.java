package eu.nordtal.s2.smp.farm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tomorrow's farm world is not built in the tick that swapped today's away.
 *
 * <h2>What this is about</h2>
 * {@code Bukkit.createWorld} runs on the server thread and the API has no asynchronous world
 * creation. On a real 26.2 server on 2026-09-06 it took <b>15 seconds</b> and Paper's watchdog
 * dumped the stack twice (finding 126). At 05:00 with nobody online that costs nothing; the reason
 * it is worth a delay at all is {@code /smp farmreset now}, which can be typed at any hour and used
 * to stand the server still for a quarter of a minute immediately after moving everybody out of a
 * world. The owner decided on 2026-09-07 to move it out by a minute rather than leave it.
 *
 * <h2>Why a source test and not a behaviour test</h2>
 * Everything involved is a Bukkit scheduler, a Bukkit world and a Bukkit plugin, so there is no
 * seam to drive from a JVM with no server in it. What can be checked is the shape - and the shape
 * is the whole of this change, because <b>every wrong version of it still works</b>: the world does
 * get built, one start later, by a caller that was going to run anyway. A delay that is silently
 * cancelled and a delay that holds are indistinguishable from the outside, which is exactly the
 * kind of thing that needs an assertion rather than a rehearsal step.
 */
class StagingIsDelayedTest {

    private static final String SOURCE =
            "smp/src/main/java/eu/nordtal/s2/smp/farm/FarmWorldReset.java";

    @Test
    @DisplayName("the swap schedules tomorrow's world instead of building it on the spot")
    void theSwapDoesNotBuildInline() {
        final String body = block("        } finally {", "        }");

        assertTrue(body.contains("scheduleStaging()"),
                "the finally block after a swap must hand the staging build to scheduleStaging(),"
                        + " which puts it a minute out - see STAGING_DELAY and finding 126");
        assertFalse(body.contains("ensureStaging()"),
                "the finally block after a swap called ensureStaging() directly, which is a"
                        + " 15-second Bukkit.createWorld on the server thread in the tick that has"
                        + " just teleported everybody out of the farm world");
    }

    @Test
    @DisplayName("re-arming the clock does not cancel the staging build")
    void schedulingTheNextResetKeepsTheStagingTask() {
        final String body = block("    private void scheduleNext() {", "\n    }");

        assertTrue(body.contains("clearSchedule()"),
                "scheduleNext() must clear only the warnings and the reset");
        assertFalse(body.contains("stop()"),
                "scheduleNext() called stop(), which cancels the delayed staging build - and it is"
                        + " called immediately AFTER the swap schedules that build, so the delay"
                        + " would be undone one line later. Nothing would look wrong: the next"
                        + " server start calls ensureStaging() again and the world appears");
    }

    @Test
    @DisplayName("the staging build is cancelled when the plugin goes down")
    void stopCancelsTheStagingTask() {
        final String body = block("    public void stop() {", "\n    }");

        assertTrue(body.contains("stagingTask"),
                "stop() is the plugin being disabled, and a scheduler task holding a reference to"
                        + " this plugin has to be cancelled there - clearSchedule() only covers the"
                        + " warnings and the reset");
    }

    @Test
    @DisplayName("the delay is a minute, and it is a named constant")
    void theDelayIsAMinute() {
        assertTrue(source().contains("STAGING_DELAY = Duration.ofMinutes(1)"),
                "the delay is a number somebody chose (finding 126, owner 2026-09-07) and it is"
                        + " carried by a named constant with the measurement written beside it,"
                        + " not by a literal at the call site");
    }

    /**
     * The code between the first {@code start} and the next {@code end} after it, comments removed.
     *
     * <p>Stripping comments is not tidiness. The comment that explains why this block does
     * <em>not</em> call {@code ensureStaging()} names the method, so a raw-text check fails on the
     * sentence that documents the rule it is checking - which is finding 146 exactly, one class
     * over.</p>
     */
    private static String block(final String start, final String end) {
        final String text = source();
        final int from = text.indexOf(start);
        if (from < 0) {
            throw new IllegalStateException("FarmWorldReset no longer contains: " + start);
        }
        final int to = text.indexOf(end, from + start.length());
        if (to < 0) {
            throw new IllegalStateException("no '" + end + "' after: " + start);
        }
        return stripComments(text.substring(from, to));
    }

    /** Drops whole-line comments; nothing here needs to see a trailing one. */
    private static String stripComments(final String code) {
        return code.lines()
                .filter(line -> {
                    final String trimmed = line.strip();
                    return !trimmed.startsWith("//") && !trimmed.startsWith("*")
                            && !trimmed.startsWith("/*");
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String source() {
        try {
            return Files.readString(repositoryRoot().resolve(SOURCE), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
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
}
