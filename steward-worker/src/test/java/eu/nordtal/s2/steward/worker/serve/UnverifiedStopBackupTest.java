package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a backup is worth when nobody could watch the servers stop.
 *
 * Each archive gets its mark after it exists, so the order in {@link FakeContainers#calls} is the assertion.
 */
class UnverifiedStopBackupTest {

    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    void everyArchiveWrittenAfterAStopNobodyCouldConfirmCarriesAMark() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp", "bot-config"));

        // Every volume, not the first: the same unreadable stop applies to all of them equally.
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
                UpdateReport.State.SAVED,
                saved.line("mc-smp").state(),
                "the archive is kept: it is a real snapshot of a real volume, and very probably"
                        + " fine. What is unverified is the moment it was taken");
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state());
        assertTrue(
                saved.line("mc-smp").detail().contains("UNVERIFIED STOP"),
                "the report line is where somebody reads this at 04:45: "
                        + saved.line("mc-smp").detail());
        assertTrue(
                saved.line("mc-smp").detail().contains("mc-smp-20260913T000000Z.tar.zst.unverified"),
                "and it has to name the mark, or the sentence is a warning with no file behind it: "
                        + saved.line("mc-smp").detail());
    }

    @Test
    void theMarkSaysWhichServicesEndingCouldNotBeReadNotJustThatOneCouldNot() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped =
                run.stop(planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)), containers.runtime());
        run.save(stopped.report(), List.of("mc-limbo"));

        final String why = snapshots.marks().get("/backups/mc-limbo-20260913T000000Z.tar.zst");
        assertTrue(
                why != null && why.contains(Topology.LIMBO),
                "somebody deciding whether to restore this needs to know which server it was, so"
                        + " they can go and look at that server's own log: " + why);
        assertFalse(
                why.contains(Topology.SMP),
                "and naming a service whose stop WAS confirmed makes the warning worthless: " + why);
    }

    @Test
    void theLineForAnUnverifiedStopCarriesTheReasonInsteadOfLookingOrdinary() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());

        assertEquals(
                List.of(Topology.SMP),
                stopped.services(),
                "the container IS stopped, so the run may install into it and must start it again."
                        + " Treating this as a refusal would leave a server off over an inspect");
        assertEquals(
                UpdateReport.State.STOPPED, stopped.report().line(Topology.SMP).state());
        assertTrue(
                stopped.report().line(Topology.SMP).detail().contains("could not be read back"),
                "an ordinary stop and one whose ending nobody saw must not read the same: "
                        + stopped.report().line(Topology.SMP).detail());
    }

    @Test
    void reportedFailedIsNotTheSameAsNotBackedUp() {
        // The whole risk of settling FAILED, in one test: FAILED is a note about evidence that must exist.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp", "bot-config"));

        assertFalse(
                run.unverifiedStops().isEmpty(),
                "this is the input Runner turns into a FAILED run, so the rest of this test is"
                        + " about a run that really will be reported as a failure");
        assertEquals(UpdateReport.State.SAVED, saved.line("mc-smp").state());
        assertEquals(
                UpdateReport.State.SAVED,
                saved.line("bot-config").state(),
                "both volumes were saved, and the run is a failure anyway - the two facts are"
                        + " supposed to hold at the same time");
        // A set, not a list: order is asserted on the shared call list, not on the marks.
        assertEquals(
                Set.of("/backups/mc-smp-20260913T000000Z.tar.zst", "/backups/bot-config-20260913T000000Z.tar.zst"),
                snapshots.marks().keySet(),
                "and every one of those archives carries its mark, because the file on the backup"
                        + " disk is what somebody finds months later - long after the row that said"
                        + " FAILED has scrolled away");
    }

    @Test
    void theRunNamesTheStopsItCouldNotConfirmInTheOrderTheyWereMade() {
        // Runner reads exactly this to settle a backup FAILED; the list itself is checked, not only its effects.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.stop(planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)), containers.runtime());

        assertEquals(
                List.of(Topology.LIMBO),
                run.unverifiedStops(),
                "only the stop that could not be read back - naming a service whose stop WAS"
                        + " confirmed would fail a run over nothing and teach everybody to ignore"
                        + " the note");
    }

    @Test
    void anOrdinaryRunMarksNothingAndLeavesItsLinesClean() {
        // The other half of the claim, easy to lose: a mark on every archive is a mark nobody reads.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"));

        assertEquals(List.of("stop:smp-container", "backup:mc-smp"), containers.calls);
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(
                List.of(),
                run.unverifiedStops(),
                "and nothing here may settle an ordinary nightly run FAILED, which would stop the"
                        + " run every night rather than on the night it matters");
        assertNull(saved.line("mc-smp").detail(), "a good backup's line says what it saved and nothing else");
        assertNull(stopped.report().line(Topology.SMP).detail());
    }

    @Test
    void aVolumeThatSavedNothingIsNotMarkedThereIsNoArchiveToMark() {
        // A mark beside a file never written is a warning about nothing, replacing what the tar actually said.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"));

        assertEquals(
                List.of("stop:smp-container", "backup:mc-smp"),
                containers.calls,
                "nothing landed on disk, so there is nothing to put a mark beside");
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(
                saved.line("mc-smp").detail().contains("not a readable archive"),
                "the tar's own reason is what a person acts on: "
                        + saved.line("mc-smp").detail());
    }

    /** A plan where the first service has work and the rest do not. */
    private static UpdateReport planned(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (int i = 0; i < services.length; i++) {
            report = report.with(
                    i == 0
                            ? work(services[i])
                            : new UpdateReport.ServiceLine(services[i], UpdateReport.State.UNCHANGED, List.of(), null));
        }
        return report;
    }

    private static UpdateReport.ServiceLine work(final String service) {
        return new UpdateReport.ServiceLine(
                service, UpdateReport.State.PLANNED, List.of(new UpdateReport.Change(service, "0.6.0", "0.7.0")), null);
    }
}
