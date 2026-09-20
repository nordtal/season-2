package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.plan.Topology;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a backup is worth when nobody could watch the servers stop.
 *
 * <h2>The shape this exists for</h2>
 * Docker's stop call succeeds whether the server shut down properly or was killed at the end of its
 * grace period, so the client inspects the container afterwards to find out which. That inspect can
 * fail on its own - a daemon that stopped answering between the two calls - and then the run holds a
 * stopped container and no idea whether the world had finished writing. Refusing there would take
 * the network down over an unreadable inspect, so the run carries on; what must not happen is the
 * thing that happened in run 23, where carrying on meant the archive came out of the sequence
 * indistinguishable from one taken over a clean shutdown.
 *
 * <h2>Why the ordering is asserted and not just the outcome</h2>
 * {@link FakeSnapshots} shares {@link FakeContainers#calls} deliberately, so stopping, saving and
 * marking are one list in one order. A mark written before the archive exists is a warning about a
 * file that is not there yet, and on a run where the save then fails it is a warning about a file
 * that never arrives - so the position of {@code mark:} in that list is the assertion, not an
 * afterthought to it.
 */
class UnverifiedStopBackupTest {

    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    @DisplayName("every archive written after a stop nobody could confirm carries a mark")
    void anUnverifiedStopMarksEveryArchiveTheRunWrites() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP)
                .stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp", "bot-config"));

        // Every volume, not the first one: the stop that could not be read was the same stop for
        // all of them, and an operator who restores the second volume needs telling just as much as
        // one who restores the first.
        assertEquals(List.of("stop:smp-container",
                        "backup:mc-smp", "mark:mc-smp-20260913T000000Z.tar.zst",
                        "backup:bot-config", "mark:bot-config-20260913T000000Z.tar.zst"),
                containers.calls,
                "the mark belongs after the archive it is about, once there is a file to put it"
                        + " beside");
        assertEquals(UpdateReport.State.SAVED, saved.line("mc-smp").state(),
                "the archive is kept: it is a real snapshot of a real volume, and very probably"
                        + " fine. What is unverified is the moment it was taken");
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state());
        assertTrue(saved.line("mc-smp").detail().contains("UNVERIFIED STOP"),
                "the report line is where somebody reads this at 04:45: "
                        + saved.line("mc-smp").detail());
        assertTrue(saved.line("mc-smp").detail()
                        .contains("mc-smp-20260913T000000Z.tar.zst.unverified"),
                "and it has to name the mark, or the sentence is a warning with no file behind it: "
                        + saved.line("mc-smp").detail());
    }

    @Test
    @DisplayName("the mark says which service's ending could not be read, not just that one could not")
    void theMarkNamesTheService() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(
                planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)),
                containers.runtime());
        run.save(stopped.report(), List.of("mc-limbo"));

        final String why = snapshots.marks().get("/backups/mc-limbo-20260913T000000Z.tar.zst");
        assertTrue(why != null && why.contains(Topology.LIMBO),
                "somebody deciding whether to restore this needs to know which server it was, so"
                        + " they can go and look at that server's own log: " + why);
        assertFalse(why.contains(Topology.SMP),
                "and naming a service whose stop WAS confirmed makes the warning worthless: " + why);
    }

    @Test
    @DisplayName("the line for an unverified stop carries the reason instead of looking ordinary")
    void theStoppedLineSaysWhatCouldNotBeRead() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP)
                .stopUnverified(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());

        assertEquals(List.of(Topology.SMP), stopped.services(),
                "the container IS stopped, so the run may install into it and must start it again."
                        + " Treating this as a refusal would leave a server off over an inspect");
        assertEquals(UpdateReport.State.STOPPED, stopped.report().line(Topology.SMP).state());
        assertTrue(stopped.report().line(Topology.SMP).detail().contains("could not be read back"),
                "an ordinary stop and one whose ending nobody saw must not read the same: "
                        + stopped.report().line(Topology.SMP).detail());
    }

    @Test
    @DisplayName("reported FAILED is not the same as not backed up")
    void aRunThatWillBeReportedFailedStillWroteAndMarkedEveryArchive() {
        // The whole risk of the owner's decision of 2026-09-13, in one test. Settling the run
        // FAILED is a safety note about evidence that is missing, and the archives it is a note
        // ABOUT have to be on disk when it is written. A change that made the run bail out on the
        // unverified stop instead - which reads like caution - would turn the note into the thing
        // it warns about, and a FAILED row is exactly where nobody would go looking for a file.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP)
                .stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp", "bot-config"));

        assertFalse(run.unverifiedStops().isEmpty(),
                "this is the input Runner turns into a FAILED run, so the rest of this test is"
                        + " about a run that really will be reported as a failure");
        assertEquals(UpdateReport.State.SAVED, saved.line("mc-smp").state());
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state(),
                "both volumes were saved, and the run is a failure anyway - the two facts are"
                        + " supposed to hold at the same time");
        // A set, not a list: FakeSnapshots#marks hands back a Map.copyOf, whose iteration order is
        // deliberately unspecified and really does vary between JVMs. The order these were written
        // in is asserted where it is actually a claim - against the shared call list, above.
        assertEquals(Set.of("/backups/mc-smp-20260913T000000Z.tar.zst",
                        "/backups/bot-config-20260913T000000Z.tar.zst"),
                snapshots.marks().keySet(),
                "and every one of those archives carries its mark, because the file on the backup"
                        + " disk is what somebody finds months later - long after the row that said"
                        + " FAILED has scrolled away");
    }

    @Test
    @DisplayName("the run names the stops it could not confirm, in the order they were made")
    void theRunNamesTheStopsItCouldNotConfirm() {
        // Runner reads exactly this to settle a backup FAILED (owner's decision, 2026-09-13). A
        // list that came back empty would leave the rule switched off with every other assertion
        // in this file still green, so the list itself is held here rather than only its effects.
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP, Topology.LIMBO).stopUnverified(Topology.LIMBO);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.stop(planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)),
                containers.runtime());

        assertEquals(List.of(Topology.LIMBO), run.unverifiedStops(),
                "only the stop that could not be read back - naming a service whose stop WAS"
                        + " confirmed would fail a run over nothing and teach everybody to ignore"
                        + " the note");
    }

    @Test
    @DisplayName("an ordinary run marks nothing and leaves its lines clean")
    void aStopThatWasConfirmedMarksNothing() {
        // The other half of the claim, and the half that is easy to lose: a mark on every archive
        // is a mark nobody reads. This is what makes the mark mean something when it is there.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"));

        assertEquals(List.of("stop:smp-container", "backup:mc-smp"), containers.calls);
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(List.of(), run.unverifiedStops(),
                "and nothing here may settle an ordinary nightly run FAILED, which would stop the"
                        + " run every night rather than on the night it matters");
        assertNull(saved.line("mc-smp").detail(),
                "a good backup's line says what it saved and nothing else");
        assertNull(stopped.report().line(Topology.SMP).detail());
    }

    @Test
    @DisplayName("a volume that saved nothing is not marked - there is no archive to mark")
    void aFailedVolumeKeepsItsOwnReason() {
        // A mark beside a file that was never written is a warning about nothing, and it would
        // replace the one sentence that matters here: what the tar actually said. Found by review
        // rather than by a run, because the two failures have to coincide to reach it.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP)
                .stopUnverified(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"));

        assertEquals(List.of("stop:smp-container", "backup:mc-smp"), containers.calls,
                "nothing landed on disk, so there is nothing to put a mark beside");
        assertEquals(Map.of(), snapshots.marks());
        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(saved.line("mc-smp").detail().contains("not a readable archive"),
                "the tar's own reason is what a person acts on: " + saved.line("mc-smp").detail());
    }

    // ---------------------------------------------------------------- helpers

    /** A plan where the first service has work and the rest do not. */
    private static UpdateReport planned(final String... services) {
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED);
        for (int i = 0; i < services.length; i++) {
            report = report.with(i == 0 ? work(services[i])
                    : new UpdateReport.ServiceLine(services[i], UpdateReport.State.UNCHANGED,
                            List.of(), null));
        }
        return report;
    }

    private static UpdateReport.ServiceLine work(final String service) {
        return new UpdateReport.ServiceLine(service, UpdateReport.State.PLANNED,
                List.of(new UpdateReport.Change(service, "0.6.0", "0.7.0")), null);
    }
}
