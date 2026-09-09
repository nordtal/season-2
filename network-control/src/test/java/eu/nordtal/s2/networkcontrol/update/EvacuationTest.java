package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When players are moved out of the way, and which servers that means.
 *
 * <p>The whole of it without a proxy and without a database: {@link Evacuation#imminent} takes the
 * two rows and an instant, which is every input the decision has. What is left in the class -
 * connecting a player, refusing when the waiting room is itself being updated - is Velocity calls
 * and a log line.</p>
 */
class EvacuationTest {

    private static final Instant NOW = Instant.parse("2026-09-09T18:00:00Z");

    /** A row with a report naming those services as moving, due in {@code in}. */
    private static UpdateRequest request(final UpdateStatus status, final Duration in,
                                         final String... moving) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.COUNTDOWN);
        for (final String service : moving) {
            report = report.with(new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("smp", "0.8.0", "0.8.1")), null));
        }
        return new UpdateRequest(1L, UpdateKind.UPDATE, status, UpdateSource.DISCORD, "till",
                NOW, NOW.plus(in), null, null, UpdateReports.toJson(report));
    }

    @Test
    @DisplayName("a countdown with time left moves nobody - it can still be cancelled")
    void aCountdownWithTimeLeftMovesNobody() {
        // The one that would be worst to get wrong. A player held in the waiting room for an outage
        // that is then cancelled has been taken off their server for nothing, and the cancel button
        // exists precisely so that can happen.
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty(),
                Optional.of(request(UpdateStatus.PENDING, Duration.ofSeconds(30), "smp")), NOW));
    }

    @Test
    @DisplayName("inside the last eight seconds the countdown's servers are cleared")
    void insideTheWindowTheServersAreCleared() {
        assertEquals(Set.of("smp"), Evacuation.imminent(Optional.empty(),
                Optional.of(request(UpdateStatus.PENDING, Evacuation.EVACUATE_BEFORE, "smp")), NOW));
    }

    @Test
    @DisplayName("the window is wider than the poll, so no tick can step over it")
    void theWindowIsWiderThanThePoll() {
        // Ticks are RestartWatch.INTERVAL apart and the window is EVACUATE_BEFORE wide. If the
        // window were the narrower of the two, a countdown could pass between two passes with
        // nobody moved and nothing saying so - which looks exactly like this class not existing.
        assertTrue(Evacuation.EVACUATE_BEFORE.compareTo(RestartWatch.INTERVAL) > 0,
                "EVACUATE_BEFORE (" + Evacuation.EVACUATE_BEFORE + ") must exceed the poll interval ("
                        + RestartWatch.INTERVAL + ") or a countdown can slip past unevacuated");
    }

    @Test
    @DisplayName("a run that is under way clears its servers whatever its instant says")
    void aRunningRunAlwaysClears() {
        // not_before is in the past by then, so untilDue is zero - but the reason this reads the
        // running row at all is the five minutes AFTER the countdown, during which the waiting room
        // still has to say why it is holding anybody.
        assertEquals(Set.of("smp", "hunger-games"),
                Evacuation.imminent(
                        Optional.of(request(UpdateStatus.RUNNING, Duration.ofSeconds(-60),
                                "smp", "hunger-games")),
                        Optional.empty(), NOW));
    }

    @Test
    @DisplayName("the running row wins over a countdown, so a second request cannot hide the first")
    void theRunningRowWins() {
        assertEquals(Set.of("smp"), Evacuation.imminent(
                Optional.of(request(UpdateStatus.RUNNING, Duration.ofSeconds(-10), "smp")),
                Optional.of(request(UpdateStatus.PENDING, Duration.ofSeconds(30), "limbo")), NOW));
    }

    @Test
    @DisplayName("only the services that are actually moving, never every service in the report")
    void onlyTheMovingServices() {
        // A line with no MOVING change is an artefact waiting for a build - news, and no reason to
        // take a server down. Evacuating for one would be an outage the run itself never causes.
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.COUNTDOWN)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp", "0.8.0", "0.8.1")), null))
                .with(new UpdateReport.ServiceLine("hunger-games", UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")), null));
        final UpdateRequest row = new UpdateRequest(2L, UpdateKind.UPDATE, UpdateStatus.RUNNING,
                UpdateSource.GAME, "till", NOW, NOW.minusSeconds(5), null, null,
                UpdateReports.toJson(report));

        assertEquals(Set.of("smp"), Evacuation.imminent(Optional.of(row), Optional.empty(), NOW));
    }

    @Test
    @DisplayName("a row whose report cannot be read moves nobody rather than everybody")
    void anUnreadableReportMovesNobody() {
        // The safe direction: the behaviour this class replaced, not a guess at the whole network.
        // A row written by an updater older than the report codec is plain text and lands here.
        final UpdateRequest row = new UpdateRequest(3L, UpdateKind.UPDATE, UpdateStatus.RUNNING,
                UpdateSource.CONSOLE, null, NOW, NOW.minusSeconds(5), null, null,
                "Restart triggered.");
        assertEquals(Set.of(), Evacuation.imminent(Optional.of(row), Optional.empty(), NOW));
    }

    @Test
    @DisplayName("nothing running and nothing counting down is nothing to clear")
    void nothingAtAll() {
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty(), Optional.empty(), NOW));
    }
}
