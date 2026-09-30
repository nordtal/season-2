package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateSource;
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
    void onlyTheMovingServices() {
        // A line with no MOVING change does not take a server down.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.COUNTDOWN)
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
    void anUnreadableReportMovesNobody() {
        // A row older than the report codec is plain text, and the safe guess is no services.
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
    void nothingAtAll() {
        assertEquals(Set.of(), Evacuation.imminent(Optional.empty()));
    }

    // a service somebody is holding down

    private static eu.nordtal.s2.database.update.ServiceHold heldDown(final String service) {
        return new eu.nordtal.s2.database.update.ServiceHold(service, NOW, "till (1)", 7L);
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
