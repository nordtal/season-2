package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests the backup of a run whose stops nobody could verify.
 *
 * Each archive gets its mark after it exists, so the order in {@link FakeContainers#calls} is the assertion.
 */
class UnverifiedStopBackupTest {

    /** The run that a mark beside an archive names. */
    private static final long RUN = 312L;

    private final CollectedSteps collected = new CollectedSteps();

    @Test
    void everyArchiveWrittenAfterAStopNobodyCouldConfirmCarriesAMark() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of("mc-smp", "bot-config"));

        // The unreadable stop applies to every volume.
        assertEquals(
                List.of(
                        "stop:smp-container",
                        "backup:mc-smp",
                        "mark:mc-smp-20260913T000000Z.tar.zst",
                        "backup:bot-config",
                        "mark:bot-config-20260913T000000Z.tar.zst"),
                containers.calls,
                "the mark belongs after the archive it is about, once there is a file to put it" + " beside");
        assertEquals(
                RunStep.Outcome.DONE,
                step("mc-smp").outcome(),
                "the archive is kept: it is a real snapshot of a real volume, and very probably"
                        + " fine. What is unverified is the moment it was taken");
        assertEquals(RunStep.Outcome.DONE, step("bot-config").outcome());
        assertFalse(
                step("mc-smp").saved().getFirst().stopVerified(),
                "the archive's row is where somebody reads this at 04:45: " + step("mc-smp"));
    }

    @Test
    void theMarkSaysWhichServicesEndingCouldNotBeReadNotJustThatOneCouldNot() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP, Topology.LIMBO), containers.runtime());
        run.save(List.of("mc-limbo"));

        assertEquals(
                new Snapshots.Unverified(RUN, List.of(Topology.LIMBO)),
                snapshots.marks().get("/backups/mc-limbo-20260913T000000Z.tar.zst"),
                "somebody deciding whether to restore this needs the run and the server, so they can go and"
                        + " look at that server's own log; a service whose stop WAS confirmed would make it worthless");
    }

    @Test
    void theStepOfAnUnverifiedStopCarriesTheReasonInsteadOfLookingOrdinary() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final UpdateRun run =
                new UpdateRun(RUN, containers, new FakeSnapshots(), stage -> {}, new Steps(collected, RUN));

        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());

        assertEquals(
                List.of(Topology.SMP),
                stopped.services(),
                "the container IS stopped, so the run may install into it and must start it again."
                        + " Treating this as a refusal would leave a server off over an inspect");
        assertEquals(RunStep.Outcome.WARNING, step(Topology.SMP).outcome());
        assertEquals(
                RunStep.Reason.UNVERIFIED_STOP,
                step(Topology.SMP).reason(),
                "an ordinary stop and one whose ending nobody saw must not read the same");
    }

    @Test
    void reportedFailedIsNotTheSameAsNotBackedUp() {
        // FAILED is a note about evidence, so the evidence must exist.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of("mc-smp", "bot-config"));

        assertFalse(
                run.unverifiedStops().isEmpty(),
                "this is the input Runner turns into a FAILED run, so the rest of this test is"
                        + " about a run that really will be reported as a failure");
        assertEquals(RunStep.Outcome.DONE, step("mc-smp").outcome());
        assertEquals(
                RunStep.Outcome.DONE,
                step("bot-config").outcome(),
                "both volumes were saved, and the run is a failure anyway - the two facts are"
                        + " supposed to hold at the same time");
        // A set, because the order is asserted on the shared call list.
        assertEquals(
                Set.of("/backups/mc-smp-20260913T000000Z.tar.zst", "/backups/bot-config-20260913T000000Z.tar.zst"),
                snapshots.marks().keySet(),
                "and every one of those archives carries its mark, because the file on the backup"
                        + " disk is what somebody finds months later - long after the row that said"
                        + " FAILED has scrolled away");
    }

    @Test
    void theRunNamesTheStopsItCouldNotConfirmInTheOrderTheyWereMade() {
        // Runner reads this list to settle a backup FAILED, so the test checks the list.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final UpdateRun run =
                new UpdateRun(RUN, containers, new FakeSnapshots(), stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP, Topology.LIMBO), containers.runtime());

        assertEquals(
                List.of(Topology.LIMBO),
                run.unverifiedStops(),
                "only the stop that could not be read back - naming a service whose stop WAS"
                        + " confirmed would fail a run over nothing and teach everybody to ignore"
                        + " the note");
    }

    @Test
    void anOrdinaryRunMarksNothingAndLeavesItsStepsClean() {
        // A mark on every archive is a mark nobody reads.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of("mc-smp"));

        assertEquals(List.of("stop:smp-container", "backup:mc-smp"), containers.calls);
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(
                List.of(),
                run.unverifiedStops(),
                "and nothing here may settle an ordinary nightly run FAILED, which would stop the"
                        + " run every night rather than on the night it matters");
        assertTrue(step("mc-smp").saved().getFirst().stopVerified(), "a good backup's archive is verified");
        assertEquals(RunStep.Outcome.DONE, step(Topology.SMP).outcome());
        assertNull(step(Topology.SMP).reason());
    }

    @Test
    void aVolumeThatSavedNothingIsNotMarkedThereIsNoArchiveToMark() {
        // A mark beside a file that was never written replaces what the tar said.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, stage -> {}, new Steps(collected, RUN));

        run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of("mc-smp"));

        assertEquals(
                List.of("stop:smp-container", "backup:mc-smp"),
                containers.calls,
                "nothing landed on disk, so there is nothing to put a mark beside");
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(RunStep.Outcome.FAILED, step("mc-smp").outcome());
        assertEquals(RunStep.Reason.TOOL_FAILED, step("mc-smp").reason(), "the tar's exit is what a person acts on");
    }

    /** Returns the one step about a service or a volume. */
    private RunStep.Row step(final String service) {
        return collected.rows().stream()
                .filter(row -> service.equals(row.service()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step about " + service + " in " + collected.rows()));
    }
}
