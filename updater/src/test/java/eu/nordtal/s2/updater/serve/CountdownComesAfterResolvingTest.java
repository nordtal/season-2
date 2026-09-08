package eu.nordtal.s2.updater.serve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The countdown happens after the plan is known, and only when the plan has work in it.
 *
 * <h2>Why this is a text search and not a run</h2>
 * {@code Runner#update} resolves a plan, which means asking GitHub, Modrinth and PaperMC what the
 * newest version of nine artefacts is. There is no way to reach the branch this is about from a JVM
 * with no network in it, and mocking the resolver would mean asserting the order of calls on a mock
 * - which is this same assertion with more machinery in front of it.
 *
 * <p>What the mechanism itself does is driven for real in {@code :common}'s
 * {@code UpdateDirectoryIntegrationTest}, against a PostgreSQL running the real migrations: a
 * claimed row gets a countdown, the countdown is cancellable, and committing it ends the window.
 * What no test there can see is whether {@code Runner} calls those in the right order, and the
 * wrong order is not a crash - it is thirty seconds of "the servers are going down" shown to
 * everybody playing, followed by "everything is already current". That is the ordinary outcome of
 * {@code /update now}, so the wrong order would be the common case rather than the rare one.</p>
 *
 * <h2>What it would catch</h2>
 * Somebody hoisting {@code countDown(...)} above the {@code isWork()} guard to "start the warning
 * earlier", which is exactly the shape the code had before 2026-09-08 and reads as an improvement.
 */
class CountdownComesAfterResolvingTest {

    private final String source = read("updater/src/main/java/eu/nordtal/s2/updater/serve/Runner.java");

    @Test
    @DisplayName("nothing is counted down before the plan is resolved")
    void resolvingComesFirst() {
        final int resolved = source.indexOf("final UpdatePlan plan = Runs.resolve(config);");
        final int countdown = source.indexOf("countDown(request.id()");

        assertTrue(resolved > 0, "the update path no longer resolves a plan; this test is stale");
        assertTrue(countdown > 0, "the update path no longer counts down; this test is stale");
        assertTrue(resolved < countdown,
                "the countdown is started before the plan is known. Every /update now would warn"
                        + " every player on the network for thirty seconds, including the"
                        + " overwhelmingly common one that then answers 'everything is already"
                        + " current'.");
    }

    @Test
    @DisplayName("a run with no work returns before any countdown is started")
    void nothingToDoCountsNothingDown() {
        final int nothingToDo = source.indexOf("if (!planned.isWork())");
        final int countdown = source.indexOf("countDown(request.id()");

        assertTrue(nothingToDo > 0, "the 'nothing to do' guard is gone; this test is stale");
        assertTrue(nothingToDo < countdown,
                "the countdown is reachable on a run that has nothing to install");
    }

    @Test
    @DisplayName("a cancelled countdown stops the run before anything is stopped")
    void aCancelEndsTheRunBeforeTheFirstStop() {
        // The order that matters most: countDown answers false when somebody pressed "Stop the
        // countdown", and the very next thing in the sequence takes servers away. A run that
        // logged the cancellation and carried on would be the worst possible reading of the button.
        final int countdown = source.indexOf("countDown(request.id()");
        final int firstStop = source.indexOf("run.stop(planned, runtime)");

        assertTrue(firstStop > countdown,
                "a service is stopped before the countdown has been committed");
        assertTrue(source.contains("return cancelled();"),
                "the cancelled branch must leave the sequence, not fall through it");
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
            return Files.readString(candidate.resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
