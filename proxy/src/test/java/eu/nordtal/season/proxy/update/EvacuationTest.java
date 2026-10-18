package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.update.RunProgress;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** When players are moved out of the way, and which servers that means, from the one row alone. */
class EvacuationTest {

    private static final Instant NOW = Instant.parse("2026-09-09T18:00:00Z");

    /** A row whose countdown started with those services as moving and ends in {@code in}. */
    private static UpdateRequest request(final UpdateStatus status, final Duration in, final String... moving) {
        return new UpdateRequest(
                1L,
                UpdateKind.UPDATE,
                status,
                Actor.HOST,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                NOW.plus(in),
                List.of(moving),
                NOW.minusSeconds(60),
                null,
                null);
    }

    @Test
    void theProgressOfARunNeverGoesDown() {
        assertEquals(OptionalInt.of(40), Evacuation.higher(OptionalInt.of(40), OptionalInt.of(36)));
        assertEquals(OptionalInt.of(40), Evacuation.higher(OptionalInt.of(40), OptionalInt.empty()));
        assertEquals(OptionalInt.of(5), Evacuation.higher(OptionalInt.empty(), OptionalInt.of(5)));
    }

    @Test
    void aCountdownMovesNobody() {
        // Nobody moves for a cancelled outage, nor while the counter still shows a number.
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    @Test
    void aRunningRunAlwaysClears() {
        // `running()` holds for the whole outage, so the waiting room says UPDATE rather than BACKEND.
        assertEquals(
                Set.of("smp", "hunger-games"),
                Evacuation.imminent(
                        Optional.of(request(UpdateStatus.RUNNING, Duration.ofMinutes(-1), "smp", "hunger-games"))));
    }

    @Test
    void theRowsMovingServicesAreTheEvacuationWhateverTheReportSays() {
        // Steward decides what moves when its countdown starts; the proxy never reads the report.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(
                        "hunger-games",
                        UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp", "0.8.0", "0.8.1")),
                        null));
        final UpdateRequest row = new UpdateRequest(
                2L,
                UpdateKind.UPDATE,
                UpdateStatus.RUNNING,
                Actor.HOST,
                NOW,
                NOW,
                NOW.minusSeconds(5),
                List.of("smp"),
                NOW,
                null,
                UpdateReports.toJson(report));

        assertEquals(Set.of("smp"), Evacuation.imminent(Optional.of(row)));
    }

    @Test
    void aServiceWhoseHealthCheckFinishedNoLongerMoves() {
        // Run 157 at the second evacuation: limbo and proxy were checked, smp was still being checked.
        final List<RunProgress.Step> steps = List.of(
                new RunProgress.Step(RunStep.Kind.INSTALL, "limbo", RunStep.Outcome.DONE),
                new RunProgress.Step(RunStep.Kind.INSTALL, "smp", RunStep.Outcome.DONE),
                new RunProgress.Step(RunStep.Kind.VERIFY, "limbo", RunStep.Outcome.DONE),
                new RunProgress.Step(RunStep.Kind.VERIFY, "proxy", RunStep.Outcome.WARNING),
                new RunProgress.Step(RunStep.Kind.VERIFY, "smp", null),
                new RunProgress.Step(RunStep.Kind.VERIFY, "steward", RunStep.Outcome.FAILED));

        assertEquals(
                Set.of("smp", "caddy", "steward"),
                Evacuation.notYetReplaced(Set.of("limbo", "proxy", "smp", "caddy", "steward"), steps));
    }

    @Test
    void aProxyStartedAfterTheCountdownIsTheRunsOwnReplacement() {
        final UpdateRequest run = request(UpdateStatus.RUNNING, Duration.ofMinutes(-2), "proxy", "limbo");

        assertTrue(Evacuation.startedByTheRun(NOW, run), "its players were moved by the proxy it replaced");
        assertFalse(Evacuation.startedByTheRun(NOW.minusSeconds(600), run), "a proxy that saw the countdown moves");
        assertFalse(Evacuation.startedByTheRun(NOW, request(UpdateStatus.PENDING, Duration.ofMinutes(2), "smp")));
    }

    @Test
    void nothingAtAll() {
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    // a service somebody is holding down

    private static eu.nordtal.season.database.update.ServiceHold heldDown(final String service) {
        return new eu.nordtal.season.database.update.ServiceHold(service, NOW, Actor.HOST, 7L);
    }

    @Test
    void theHoldsBecomeASet() {
        // The DOWN run is DONE in seconds, so the hold keeps the waiting room explaining the outage.
        assertEquals(Set.of("smp"), Evacuation.heldServices(List.of(heldDown("smp"))));
    }

    @Test
    void aHoldOnAnythingElseIsHarmless() {
        assertEquals(Set.of("smp", "caddy"), Evacuation.heldServices(List.of(heldDown("smp"), heldDown("caddy"))));
    }

    @Test
    void noHoldIsEmpty() {
        assertTrue(Evacuation.heldServices(List.of()).isEmpty());
    }
}
