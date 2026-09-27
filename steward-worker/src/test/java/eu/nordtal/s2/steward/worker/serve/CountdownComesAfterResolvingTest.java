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
 * Why this is a text search and not a run: {@code Runner#update} resolves a plan, which means asking GitHub,
 * Modrinth and PaperMC what the newest version of nine artefacts is. There is no way to reach the branch this is
 * about from a JVM with no network in it, and mocking the resolver would mean asserting the order of calls on a mock
 * - which is this same assertion with more machinery in front of it.
 *
 * What the mechanism itself does is driven for real in {@code :common} 's {@code UpdateDirectoryIntegrationTest},
 * against a PostgreSQL running the real migrations: a claimed row gets a countdown, the countdown is cancellable,
 * and committing it ends the window. What no test there can see is whether {@code Runner} calls those in the right
 * order, and the wrong order is not a crash - it is thirty seconds of "the servers are going down" shown to
 * everybody playing, followed by "everything is already current". That is the ordinary outcome of
 * {@code /update now}, so the wrong order would be the common case rather than the rare one.
 *
 * What it would catch: Somebody hoisting {@code countDown(...)} above the {@code isWork()} guard to "start the
 * warning earlier", which is exactly the shape the code had before this test and reads as an improvement.
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

    /**
     * The body of {@code update}, so an ordering assertion cannot straddle two methods.
     *
     * {@code countDown(request.id()} and {@code run.stop(planned, runtime)} each appear three times in this file - in
     * {@code update}, in {@code backupUnderLock} and in {@code restartUnderLock}. Searching the whole source would
     * compare a call in one method against a call in another and pass while proving nothing about either.
     *
     * The end of the bracket is the method that really follows {@code update}. It was {@code restartUnderLock}
     * once, five methods further down: this said it bracketed one method and actually spanned six,
     * including both of the others that call {@code countDown}. The assertions below were right anyway, but only by
     * accident - {@link #at} takes the first occurrence and {@code update} happens to come first in the file. Moving
     * {@code update} below {@code backup} would have turned every one of them into a comparison between two different
     * methods, still green.
     */
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

    /**
     * Where {@code token} is, refusing {@code -1}.
     *
     * The reason this is not {@code indexOf} at the call site: a missing token answers -1, and -1 is smaller than every
     * real position - so an ordering assertion goes green the moment the call it protects is deleted. That is the
     * failure mode this whole file exists to prevent, arriving through the file itself.
     */
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
