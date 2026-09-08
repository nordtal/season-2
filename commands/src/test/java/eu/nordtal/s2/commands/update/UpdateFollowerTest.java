package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One decision about a row, made once for the proxy and the three Paper consoles. Every branch here
 * used to live in {@code :paper-common} alone, which is the module the proxy cannot see.
 */
class UpdateFollowerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T20:00:00Z");
    private static final Instant DEADLINE = NOW.plus(UpdateFollower.PATIENCE);

    private static UpdateRequest row(final UpdateStatus status, final String result) {
        return new UpdateRequest(7L, UpdateKind.REPORT, status, UpdateSource.GAME, "tester",
                NOW, NOW, null, null, result);
    }

    private static UpdateFollower following(final LongFunction<Optional<UpdateRequest>> reader) {
        return new UpdateFollower(7L, reader, DEADLINE);
    }

    @Test
    @DisplayName("a row that is still open says nothing and keeps the surface polling")
    void waits() {
        final UpdateFollower.Step step = following(id -> Optional.of(row(UpdateStatus.RUNNING, null)))
                .poll(NOW.plusSeconds(30));
        assertTrue(step.says().isEmpty());
        assertFalse(step.finished());
    }

    @Test
    @DisplayName("a finished report is printed line by line, as literals, never through a key")
    void printsTheReport() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp.jar", "0.7.0", "<0.7.1>")), null));
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower.Step step = following(id -> Optional.of(
                row(UpdateStatus.DONE, UpdateReports.toJson(report)))).poll(NOW);
        step.deliver(user);

        assertTrue(step.finished());
        assertEquals(List.of("<literal>", "<literal>"), user.keys(),
                "a version string with a '<' in it reaches chat as text, not as a MiniMessage tag");
        assertEquals("What is new", user.replies.get(0).of("text"));
        assertEquals("smp: waiting - smp.jar 0.7.0 -> <0.7.1>", user.replies.get(1).of("text"));
    }

    @Test
    @DisplayName("a failed run says so first, and then still prints what the updater wrote")
    void failedSaysSo() {
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.FAILED, "plain text from before V12")))
                .poll(NOW).deliver(user);
        assertEquals(List.of("update.failed", "<literal>"), user.keys());
        assertEquals("plain text from before V12", user.replies.get(1).of("text"));
    }

    @Test
    @DisplayName("a cancelled countdown is not a failure and is printed without the failure line")
    void cancelledIsNotFailed() {
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.CANCELLED, "Cancelled by tester")))
                .poll(NOW).deliver(user);
        assertEquals(List.of("<literal>"), user.keys());
    }

    @Test
    @DisplayName("a report longer than the chat can hold is cut, and the cut is announced")
    void longReportsAreCut() {
        final String longResult = String.join("\n",
                java.util.Collections.nCopies(UpdateFollower.MAX_LINES + 3, "line"));
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.DONE, longResult))).poll(NOW).deliver(user);
        assertEquals(UpdateFollower.MAX_LINES + 1, user.replies.size());
        assertEquals("update.truncated", user.replies.getLast().key());
        assertEquals(3, user.replies.getLast().of("lines"));
    }

    @Test
    @DisplayName("a row deleted by hand is 'gone', not an endless wait")
    void gone() {
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower.Step step = following(id -> Optional.empty()).poll(NOW);
        step.deliver(user);
        assertTrue(step.finished());
        assertEquals(List.of("update.gone"), user.keys());
    }

    @Test
    @DisplayName("past the deadline the row's own status is named, because PENDING means the updater is down")
    void timesOutNamingTheStatus() {
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower.Step step = following(id -> Optional.of(row(UpdateStatus.PENDING, null)))
                .poll(DEADLINE.plusSeconds(1));
        step.deliver(user);
        assertTrue(step.finished());
        assertEquals("update.timeout", user.only().key());
        assertEquals(UpdateStatus.PENDING, user.only().of("status"));
    }

    @Test
    @DisplayName("a database that cannot be read ends the watch with a sentence and hands the cause back")
    void unreadable() {
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower.Step step = following(id -> {
            throw new IllegalStateException("pool closed");
        }).poll(NOW);
        step.deliver(user);
        assertTrue(step.finished());
        assertNotNull(step.failure(), "the surface logs it; the follower has no log of its own");
        assertEquals(List.of("update.failed"), user.keys());
    }
}
