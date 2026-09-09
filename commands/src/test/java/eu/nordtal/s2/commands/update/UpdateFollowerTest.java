package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.common.message.Tone;
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
    @DisplayName("a row that has written nothing yet says nothing and keeps the surface polling")
    void waits() {
        final UpdateFollower.Step step = following(id -> Optional.of(row(UpdateStatus.RUNNING, null)))
                .poll(NOW.plusSeconds(30));
        assertTrue(step.says().isEmpty());
        assertFalse(step.finished());
    }

    @Test
    @DisplayName("a finished report is said as keys, so a German admin reads German")
    void printsTheReport() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp.jar", "0.7.0", "<0.7.1>")), null));
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower.Step step = following(id -> Optional.of(
                row(UpdateStatus.DONE, UpdateReports.toJson(report)))).poll(NOW);
        step.deliver(user);

        assertTrue(step.finished());
        // Not one literal anywhere: a report used to be printed as the updater's own English text,
        // which is what a German admin got on the longest answer in the network.
        assertEquals(List.of("update.stage.PLANNED", "update.line.PLANNED", "update.change"),
                user.keys());
        assertEquals("smp", user.replies.get(1).of("service"));
        assertEquals("smp.jar", user.replies.get(2).of("artefact"));
        assertEquals("<0.7.1>", user.replies.get(2).of("to"),
                "a version string with a '<' in it travels as a placeholder, which MessageRenderer"
                        + " escapes - it must never be composed into the template");
    }

    @Test
    @DisplayName("an artefact with no build yet is its own line, and not a failure")
    void printsAnUnsupportedArtefact() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.NOTHING_TO_DO)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")), null));
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(
                row(UpdateStatus.DONE, UpdateReports.toJson(report)))).poll(NOW).deliver(user);

        // Its own key, because the sentence is different: nothing is moving, so "{artefact} {from}
        // -> {to}" has nothing to put on either side of the arrow. And its own line rather than a
        // silent omission - an artefact dropped from the report is one somebody has to remember.
        assertEquals(List.of("update.stage.NOTHING_TO_DO", "update.line.UNCHANGED",
                "update.change.unsupported"), user.keys());
        assertEquals("coreprotect", user.replies.get(2).of("artefact"));
        assertEquals(Tone.MUTED, user.replies.get(2).tone(),
                "nothing has gone wrong here; a run that shouts about it trains people to skip it");
    }

    @Test
    @DisplayName("the failed service is the one line that is coloured differently")
    void theFailureIsFindable() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY,
                        List.of(), null))
                .with(new UpdateReport.ServiceLine("limbo", UpdateReport.State.FAILED,
                        List.of(), "did not come back"))
                .withNote("one service did not come back");
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.FAILED, UpdateReports.toJson(report))))
                .poll(NOW).deliver(user);

        assertEquals(List.of("update.stage.FAILED", "update.line.HEALTHY", "update.line.FAILED",
                "update.detail", "update.note"), user.keys());
        assertEquals(Tone.BAD, user.replies.get(0).tone());
        assertEquals(Tone.GOOD, user.replies.get(1).tone(), "the service that came back");
        assertEquals(Tone.BAD, user.replies.get(2).tone(), "the one a reader has to find");
        assertEquals(Tone.BAD, user.replies.get(3).tone(),
                "a detail carries the tone of the line it belongs to, not its own");
    }

    @Test
    @DisplayName("a stage is announced once, when the run reaches it - not on every poll")
    void stagesAreSaidOnce() {
        final UpdateReport[] current = {UpdateReport.at(UpdateReport.Stage.RESOLVING)};
        final FakeUser user = FakeUser.inGame();
        final UpdateFollower follower = following(id -> Optional.of(
                row(UpdateStatus.RUNNING, UpdateReports.toJson(current[0]))));

        follower.poll(NOW).deliver(user);
        follower.poll(NOW.plusSeconds(2)).deliver(user);
        follower.poll(NOW.plusSeconds(4)).deliver(user);
        assertEquals(List.of("update.stage.RESOLVING"), user.keys(),
                "a run rewrites its report every few seconds and this polls every two; without the"
                        + " memory a player is told 'Stopping the servers' a dozen times");

        current[0] = current[0].withStage(UpdateReport.Stage.STOPPING);
        final UpdateFollower.Step step = follower.poll(NOW.plusSeconds(6));
        step.deliver(user);
        assertFalse(step.finished(), "a stage change is news, not an ending");
        assertEquals(List.of("update.stage.RESOLVING", "update.stage.STOPPING"), user.keys());
    }

    @Test
    @DisplayName("a cancelled countdown names who stopped it, and is not a failure")
    void cancelledIsNotFailed() {
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.CANCELLED, "Cancelled by tester")))
                .poll(NOW).deliver(user);
        assertEquals(List.of("update.stopped-by"), user.keys());
        assertEquals("Cancelled by tester", user.only().of("reason"));
        assertEquals(Tone.WARN, user.only().tone(),
                "somebody used the way out on purpose; red would read as something going wrong");
    }

    @Test
    @DisplayName("a row from before V12 is plain text and is still printed as it is")
    void legacyRowsArePrintedVerbatim() {
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.FAILED, "plain text from before V12")))
                .poll(NOW).deliver(user);
        assertEquals(List.of("update.failed", "<literal>"), user.keys());
        assertEquals("plain text from before V12", user.replies.get(1).of("text"));
    }

    @Test
    @DisplayName("a report longer than the chat can hold is cut, and the cut is announced")
    void longReportsAreCut() {
        UpdateReport building = UpdateReport.at(UpdateReport.Stage.DONE);
        for (int i = 0; i < UpdateFollower.MAX_LINES + 3; i++) {
            building = building.with(new UpdateReport.ServiceLine("service-" + i,
                    UpdateReport.State.HEALTHY, List.of(), null));
        }
        final UpdateReport report = building;
        final FakeUser user = FakeUser.inGame();
        following(id -> Optional.of(row(UpdateStatus.DONE, UpdateReports.toJson(report))))
                .poll(NOW).deliver(user);

        assertEquals(UpdateFollower.MAX_LINES + 1, user.replies.size());
        assertEquals("update.truncated", user.replies.getLast().key());
        // MAX_LINES + 3 services plus the headline is MAX_LINES + 4 lines; MAX_LINES are kept.
        assertEquals(4, user.replies.getLast().of("lines"));
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
        assertFalse(user.only().placeholders().containsKey("id"),
                "the request id is a primary key read out to somebody who cannot use it; the one"
                        + " reader who can is looking at the updater's log, where it still is");
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
