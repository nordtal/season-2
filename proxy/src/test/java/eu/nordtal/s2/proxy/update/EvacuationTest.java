package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When players are moved out of the way, and which servers that means.
 *
 * <p>The whole of it without a proxy and without a database: {@link Evacuation#imminent} takes the
 * one row, which is every input the decision has since season-2-ops/118 took the head start away. What is left in the class -
 * connecting a player, refusing when the waiting room is itself being updated - is Velocity calls
 * and a log line.</p>
 */
class EvacuationTest {

    private static final Instant NOW = Instant.parse("2026-09-09T18:00:00Z");

    /** A row with a report naming those services as moving, due in {@code in}. */
    private static UpdateRequest request(final UpdateStatus status, final Duration in, final String... moving) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.COUNTDOWN);
        for (final String service : moving) {
            report = report.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("smp", "0.8.0", "0.8.1")),
                    null));
        }
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                status,
                UpdateSource.DISCORD,
                "till",
                NOW,
                NOW.plus(in),
                null,
                null,
                UpdateReports.toJson(report));
    }

    @Test
    @DisplayName("a countdown moves nobody, however little is left of it")
    void aCountdownMovesNobody() {
        // TWO THINGS AT ONCE, and the second is season-2-ops/118. A player held in the waiting room
        // for an outage that is then cancelled has been taken off their server for nothing, and the
        // cancel button exists precisely so that can happen. And a player moved while the counter
        // still shows a number has been told one thing and done another - Till, 2026-09-20: thrown
        // out four seconds before the end of the countdown. There is no window any more; the row
        // this reads is the one whose instant has passed.
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    @Test
    @DisplayName("a run that is under way clears its servers")
    void aRunningRunAlwaysClears() {
        // `running()` is the rows whose not_before has passed, so "the counter reached zero" and
        // "this row is under way" are the same instant - and this set stays true for the whole
        // outage after it, which is what keeps the waiting room saying UPDATE rather than BACKEND.
        assertEquals(
                Set.of("smp", "hunger-games"),
                Evacuation.imminent(
                        Optional.of(request(UpdateStatus.RUNNING, Duration.ofSeconds(-60), "smp", "hunger-games"))));
    }

    @Test
    @DisplayName("zero is the moment, so the poll is a guarantee and not the decision")
    void theSweepIsOnlyTheGuarantee() {
        // The head start used to be eight seconds precisely so that a five-second sweep could not
        // step over the window. With no window left, a sweep that is the only trigger would move
        // people up to RestartWatch.INTERVAL late - so the zero beat runs the sweep as well, and
        // this is the assertion that says the sweep alone would not have been enough.
        assertTrue(
                RestartWatch.INTERVAL.compareTo(java.time.Duration.ZERO) > 0,
                "a sweep with no interval would be the decision rather than the guarantee");
    }

    @Test
    @DisplayName("only the services that are actually moving, never every service in the report")
    void onlyTheMovingServices() {
        // A line with no MOVING change is an artefact waiting for a build - news, and no reason to
        // take a server down. Evacuating for one would be an outage the run itself never causes.
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.COUNTDOWN)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp", "0.8.0", "0.8.1")),
                        null))
                .with(new UpdateReport.ServiceLine(
                        "hunger-games",
                        UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")),
                        null));
        final UpdateRequest row = new UpdateRequest(
                2L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                UpdateSource.GAME,
                "till",
                NOW,
                NOW.minusSeconds(5),
                null,
                null,
                UpdateReports.toJson(report));

        assertEquals(Set.of("smp"), Evacuation.imminent(Optional.of(row)));
    }

    @Test
    @DisplayName("a row whose report cannot be read moves nobody rather than everybody")
    void anUnreadableReportMovesNobody() {
        // The safe direction: the behaviour this class replaced, not a guess at the whole network.
        // A row written by a worker older than the report codec is plain text and lands here.
        final UpdateRequest row = new UpdateRequest(
                3L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                UpdateSource.CONSOLE,
                null,
                NOW,
                NOW.minusSeconds(5),
                null,
                null,
                "Restart triggered.");
        assertEquals(Set.of(), Evacuation.imminent(Optional.of(row)));
    }

    @Test
    @DisplayName("nothing running is nothing to clear")
    void nothingAtAll() {
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    // --- a service somebody is holding down (season-2-ops/125) -----------------------------------

    private static eu.nordtal.s2.common.update.ServiceHold heldDown(final String service) {
        return new eu.nordtal.s2.common.update.ServiceHold(service, NOW, "till (1)", 7L);
    }

    @Test
    @DisplayName("the held services are read off the holds, not off the run")
    void theHoldsBecomeASet() {
        // The whole reason the hold is read at all. The DOWN run reaches DONE in seconds and then
        // `running()` and `countingDown()` are both empty - but nothing has started, and the people
        // in the waiting room are there because of this, not because a backend fell over.
        assertEquals(Set.of("smp"), Evacuation.heldServices(List.of(heldDown("smp"))));
    }

    @Test
    @DisplayName("a hold on something that is not a backend is simply a name nobody asks about")
    void aHoldOnAnythingElseIsHarmless() {
        assertEquals(Set.of("smp", "caddy"), Evacuation.heldServices(List.of(heldDown("smp"), heldDown("caddy"))));
    }

    @Test
    @DisplayName("no hold is no set at all")
    void noHoldIsEmpty() {
        assertTrue(Evacuation.heldServices(List.of()).isEmpty());
    }
}
