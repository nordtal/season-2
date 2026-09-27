package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.steward.worker.plan.Change;
import eu.nordtal.s2.steward.worker.plan.Topology;
import eu.nordtal.s2.steward.worker.plan.UpdatePlan;
import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Whether an update is run by this process or handed to the steward-worker it is about to install.
 */
class HandoverTest {

    @Test
    void aNewerWorkerInTheReleaseIsInstalledAndHandedTheRun() {
        final Handover.Decision decision =
                Handover.decide(plan(worker(Change.Status.OUTDATED, "0.9.7", "0.9.8")), "0.9.7", null);

        final Handover.HandOver handOver = assertInstanceOf(Handover.HandOver.class, decision);
        assertEquals("0.9.8", handOver.version());
        assertTrue(handOver.install(), "the jar is not in the volume yet, so it has to be placed first");
    }

    @Test
    void aWorkerAlreadyInTheVolumeButNotRunningIsHandedTheRunWithoutInstallingAnything() {
        // 0.9.7 in the volume, 0.9.6 answering the request.
        final Handover.Decision decision =
                Handover.decide(plan(worker(Change.Status.UP_TO_DATE, "0.9.7", "0.9.7")), "0.9.6", null);

        final Handover.HandOver handOver = assertInstanceOf(Handover.HandOver.class, decision);
        assertEquals("0.9.7", handOver.version());
        assertEquals(false, handOver.install());
    }

    @Test
    void theWorkerThatIsCurrentRunsTheUpdateItself() {
        assertInstanceOf(
                Handover.Proceed.class,
                Handover.decide(plan(worker(Change.Status.UP_TO_DATE, "0.9.8", "0.9.8")), "0.9.8", null));
    }

    @Test
    void aRunScopedAwayFromTheWorkerIsNotHandedOver() {
        // "update smp" does not name the worker, so onlyServices has already dropped its row.
        assertInstanceOf(Handover.Proceed.class, Handover.decide(plan(), "0.9.7", null));
    }

    @Test
    void aWorkerThatDoesNotKnowItsOwnVersionNeverHandsOver() {
        // From an IDE or a test there is no version to check a handover against.
        assertInstanceOf(
                Handover.Proceed.class,
                Handover.decide(plan(worker(Change.Status.OUTDATED, "0.9.7", "0.9.8")), null, null));
    }

    @Test
    void theWorkerTheRunWasHandedToFinishesIt() {
        final String handedOver = handedOverTo("0.9.8");

        assertInstanceOf(
                Handover.Proceed.class,
                Handover.decide(plan(worker(Change.Status.UP_TO_DATE, "0.9.8", "0.9.8")), "0.9.8", handedOver));
    }

    @Test
    void aWorkerThatIsNotTheOneTheRunWasHandedToRefusesInsteadOfHandingItOverAgain() {
        // The entrypoint started something other than the jar that was placed.
        final String handedOver = handedOverTo("0.9.8");

        final Handover.Decision decision =
                Handover.decide(plan(worker(Change.Status.OUTDATED, "0.9.7", "0.9.8")), "0.9.7", handedOver);

        final Handover.Refuse refuse = assertInstanceOf(Handover.Refuse.class, decision);
        assertTrue(refuse.reason().contains("0.9.8"), refuse.reason());
        assertTrue(refuse.reason().contains("0.9.7"), refuse.reason());
    }

    @Test
    void aFailedWorkerRowIsLeftToTheRunWhichRefusesOverItAnyway() {
        // UNRESOLVED is not work; the plan's own failure handling decides what happens to it.
        assertInstanceOf(
                Handover.Proceed.class,
                Handover.decide(
                        plan(Change.unresolved(Topology.STEWARD_WORKER, Topology.STEWARD_WORKER, "GitHub is down")),
                        "0.9.7",
                        null));
    }

    private static String handedOverTo(final String version) {
        return UpdateReports.toJson(
                UpdateReport.at(UpdateReport.Stage.RESOLVING).withNote(Handover.note(version)));
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.EPOCH, "v0.9.8", false, List.of(changes), List.of(), List.of());
    }

    private static Change worker(final Change.Status status, final String installed, final @Nullable String wanted) {
        return new Change(
                Topology.STEWARD_WORKER,
                Topology.STEWARD_WORKER,
                status,
                "steward-worker-" + installed + ".jar",
                wanted == null
                        ? null
                        : new RemoteFile(
                                Topology.STEWARD_WORKER,
                                wanted,
                                "steward-worker-" + wanted + ".jar",
                                URI.create("https://example.invalid/steward-worker-" + wanted + ".jar"),
                                null),
                null);
    }
}
