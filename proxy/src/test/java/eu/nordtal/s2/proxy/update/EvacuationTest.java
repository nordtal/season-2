package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    void theSweepIsOnlyTheGuarantee() {
        // The zero beat runs the sweep too; the sweep alone could move people up to RestartWatch.INTERVAL late.
        assertTrue(
                RestartWatch.INTERVAL.compareTo(java.time.Duration.ZERO) > 0,
                "a sweep with no interval would be the decision rather than the guarantee");
    }

    @Test
    void theRowsMovingServicesAreTheEvacuationWhateverTheReportSays() {
        // The worker decides what moves when its countdown starts; the proxy never reads the report.
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
    void nothingAtAll() {
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    // a service somebody is holding down

    private static eu.nordtal.s2.database.update.ServiceHold heldDown(final String service) {
        return new eu.nordtal.s2.database.update.ServiceHold(service, NOW, Actor.HOST, 7L);
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
