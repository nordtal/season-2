package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The countdown happens after the plan is known, and only when the plan has work in it.
 *
 * Read as source text, since resolving a plan needs GitHub, Modrinth and PaperMC.
 */
class CountdownComesAfterResolvingTest {

    private final String source = read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/Runner.java");
    private final String sequence =
            read("steward-worker/src/main/java/eu/nordtal/s2/steward/worker/serve/UpdateSequence.java");

    @Test
    void nothingIsCountedDownBeforeThePlanIsResolved() {
        final String update = updateMethod();
        // Resolving lives in prepareUpdate and the countdown in UpdateSequence.run; update calls them in that order.
        assertTrue(slice(sequence, "static Preparation prepareUpdate(", "\n    static Outcome updateWithNoServer(")
                .contains("Runs.resolve(runner.config"));
        assertTrue(sequenceRun().contains("countDown(request.id()"));
        final int resolved = at(update, "UpdateSequence.prepareUpdate(");
        final int countdown = at(update, "UpdateSequence.run(");

        assertTrue(
                resolved < countdown,
                "the countdown is started before the plan is known. Every /update now would warn"
                        + " every player on the network for thirty seconds, including the"
                        + " overwhelmingly common one that then answers 'everything is already"
                        + " current'.");
    }

    @Test
    void aRunWithNoWorkReturnsBeforeAnyCountdownIsStarted() {
        final String update = updateMethod();
        final int nothingToDo = at(update, "if (!prep.planned().isWork())");
        final int countdown = at(update, "UpdateSequence.run(");

        assertTrue(nothingToDo < countdown, "the countdown is reachable on a run that has nothing to install");
    }

    @Test
    void aCancelledCountdownStopsTheRunBeforeAnythingIsStopped() {
        // The order that matters most: countDown answers false on "Stop", and the next step takes servers away.
        final String update = sequenceRun();
        final int countdown = at(update, "countDown(request.id()");
        final int firstStop = at(update, "run.stop(planned, runtime)");

        assertTrue(firstStop > countdown, "a service is stopped before the countdown has been committed");
        assertTrue(
                update.contains("return Runner.cancelled();"),
                "the cancelled branch must leave the sequence, not fall through it");
    }

    /** The body of {@code update}, so an ordering assertion cannot straddle two methods. */
    private String sequenceRun() {
        return slice(sequence, "static Outcome run(", "\n    private static UpdateReport openUpdateStandbys(");
    }

    private static String slice(final String text, final String from, final String to) {
        final int start = text.indexOf(from);
        assertTrue(start > 0, "`" + from + "` is gone; this test moves with it or silently stops checking");
        final int end = text.indexOf(to, start);
        assertTrue(end > start, "`" + to + "` no longer follows `" + from + "`; the slice needs both ends");
        return text.substring(start, end);
    }

    private String updateMethod() {
        final int from = source.indexOf("private Outcome update(");
        assertTrue(
                from > 0,
                "Runner#update is gone - if it was renamed, this test moves with it,"
                        + " because a check that cannot find its subject silently stops running");
        final int to = source.indexOf("\n    boolean countDown(");
        assertTrue(
                to > from,
                "Runner#countDown is gone or has moved above update; this test"
                        + " brackets one method and needs both ends");
        return source.substring(from, to);
    }

    /** Where {@code token} is, refusing {@code -1} so a deleted call cannot pass an ordering check. */
    private static int at(final String haystack, final String token) {
        final int index = haystack.indexOf(token);
        assertTrue(
                index >= 0,
                "Runner#update no longer contains `" + token + "`. If that call was"
                        + " removed the guard is gone; if it was renamed, rename it here too.");
        return index;
    }

    private static String read(final String relative) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        try {
            return joined(Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
