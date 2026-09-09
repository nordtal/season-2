package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.updater.plan.Topology;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stop, swap, start, and prove it came back.
 *
 * <h2>Why this is tested against a fake and not rehearsed</h2>
 * Rehearsing this needs a real Arcane, a real project and a willingness to take the network down -
 * and as of 2026-09-07 <b>nobody has seen a single 2xx from Arcane at all</b>. Every decision in
 * the sequence is therefore held here: what it refuses to start, what it will not stop, what it
 * does when a stop fails, and above all what it calls "back".
 *
 * <h2>The failures it has to survive are the interesting cases</h2>
 * A container that is {@code running} with a dead plugin inside it is the exact shape the first
 * deployment produced - green healthcheck, open port, no season on it - and it is why every process
 * writes a readiness marker at all. A sequence that accepted {@code running} would report a
 * successful update over a network that is down.
 */
class UpdateRunTest {

    private final List<UpdateReport> progress = new ArrayList<>();

    // ---------------------------------------------------------------- refusing to begin

    @Test
    @DisplayName("a run whose Arcane is unreachable never gets as far as a plan")
    void anUnreachableArcaneStopsEverything() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP).unreachable();

        assertFalse(new UpdateRun(arcane, progress::add).check().reached(),
                "the runtime read is the first thing a run does, before a version is resolved or a"
                        + " byte is downloaded - because a run that cannot STOP a server must not"
                        + " move a jar. Continuing anyway is finding 147 performed as a fallback");
        assertEquals(List.of(), arcane.calls, "and nothing was asked of Arcane");
    }

    // ---------------------------------------------------------------- stopping

    @Test
    @DisplayName("only services with work are stopped")
    void aServiceWithNothingToInstallKeepsRunning() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP, Topology.LIMBO),
                arcane.runtime());

        assertEquals(List.of("stop:smp-container"), arcane.calls,
                "limbo has no changes, and stopping it would be an outage with nothing to show"
                        + " for it");
        assertEquals(List.of(Topology.SMP), stopped.services());
        assertEquals(UpdateReport.State.STOPPED, stopped.report().line(Topology.SMP).state());
        assertEquals(UpdateReport.State.UNCHANGED, stopped.report().line(Topology.LIMBO).state());
    }

    @Test
    @DisplayName("a service is not stopped for an artefact that has no build to install")
    void anUnsupportedArtefactIsNotAnOutage() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        // smp's only line is CoreProtect, which has no build for this Minecraft version. It has
        // something to SAY and nothing to do - and the difference is measured in whether people
        // playing get thrown off. `changes().isEmpty()` was the old test and would fail this one:
        // the line is not empty, it is simply not moving.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(Topology.SMP, UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")), null));

        final UpdateRun.Stopped stopped = run.stop(report, arcane.runtime());

        assertEquals(List.of(), arcane.calls,
                "the SMP was taken down because somebody else has not published a jar yet");
        assertEquals(List.of(), stopped.services());
    }

    @Test
    @DisplayName("the updater never stops itself")
    void theUpdaterIsNotInItsOwnSequence() {
        final FakeArcane arcane = new FakeArcane().running(Topology.UPDATER, Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.UPDATER, Topology.SMP)
                .with(work(Topology.UPDATER)), arcane.runtime());

        assertFalse(arcane.calls.contains("stop:updater-container"),
                "this sequence is running inside the updater; stopping it kills the process that"
                        + " would otherwise start everything else again - which is exactly why the"
                        + " old project-wide redeploy could never report whether anything returned");
        assertFalse(stopped.services().contains(Topology.UPDATER));
        assertEquals(UpdateReport.State.INSTALLED, stopped.report().line(Topology.UPDATER).state(),
                "its jar is still placed; it is picked up at its next start, as it always was");
    }

    @Test
    @DisplayName("a service that could not be stopped is not installed to and not started")
    void aRefusedStopTakesItsServiceOutOfTheRun() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP).stopFails();
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), arcane.runtime());

        assertEquals(List.of(), stopped.services(),
                "starting something that was never stopped is how one failure becomes two");
        assertEquals(UpdateReport.State.FAILED, stopped.report().line(Topology.SMP).state());
        run.start(stopped);
        assertFalse(arcane.calls.contains("start:smp-container"));
    }

    @Test
    @DisplayName("a service Arcane does not list fails by name rather than silently")
    void anUnknownServiceIsNamed() {
        final FakeArcane arcane = new FakeArcane().running(Topology.LIMBO);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), arcane.runtime());

        assertEquals(UpdateReport.State.FAILED, stopped.report().line(Topology.SMP).state());
        assertTrue(stopped.report().line(Topology.SMP).detail().contains("does not list a container"),
                stopped.report().line(Topology.SMP).detail());
    }

    // ---------------------------------------------------------------- coming back

    @Test
    @DisplayName("stop comes before start, and both are recorded in order")
    void theOrderIsTheWholePoint() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        run.start(run.stop(planned(Topology.SMP), arcane.runtime()));

        assertEquals(List.of("stop:smp-container", "start:smp-container"), arcane.calls,
                "the gap between these two is where jars are safe to move, and it is the entire"
                        + " reason this is container-level rather than one project-wide call");
    }

    @Test
    @DisplayName("a service that is running but not healthy has not come back")
    void runningIsNotBack() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), arcane.runtime());
        final UpdateReport started = run.start(stopped);
        arcane.sick(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state(),
                "a container whose plugin threw in onEnable is 'running' with an open port and no"
                        + " season on it - which is what the first deployment actually did, and is"
                        + " why every process writes a readiness marker");
        assertTrue(verified.line(Topology.SMP).detail().contains("did not come back"),
                verified.line(Topology.SMP).detail());
    }

    @Test
    @DisplayName("a service that reports healthy inside the window is the success case")
    void healthyEndsTheWait() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), arcane.runtime());
        final UpdateReport started = run.start(stopped);

        // Comes back on the second look, which is what a real start does: started, then healthy.
        final UpdateRun.Waiting clock = new UpdateRun.Waiting() {
            private Instant now = Instant.parse("2026-09-07T12:00:00Z");

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                arcane.back(Topology.SMP);
                return true;
            }
        };

        final UpdateReport verified = run.verify(started, stopped.services(), clock);

        assertEquals(UpdateReport.State.HEALTHY, verified.line(Topology.SMP).state());
    }

    @Test
    @DisplayName("one service failing does not lose the ones that came back")
    void aPartialFailureStillReportsTheRest() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = new UpdateRun(arcane, progress::add);
        final UpdateRun.Stopped stopped = run.stop(
                planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)), arcane.runtime());
        final UpdateReport started = run.start(stopped);
        arcane.back(Topology.LIMBO);
        arcane.sick(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(UpdateReport.State.HEALTHY, verified.line(Topology.LIMBO).state(),
                "the run is a failure, and 'which servers are up' is still the question somebody"
                        + " has at three in the morning");
        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
    }

    @Test
    @DisplayName("a service that refused to stop is not in the set the run may install into")
    void aRefusedStopIsVisibleToTheCaller() {
        // Runner reads exactly this to decide whether to abort: a service with work that is not in
        // stopped.services() is still RUNNING, and installing into it is finding 147 reached
        // through the sequence that exists to prevent it. Found by review, 2026-09-08.
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP, Topology.LIMBO).stopFails();
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateReport planned = planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO));
        final UpdateRun.Stopped stopped = run.stop(planned, arcane.runtime());

        assertEquals(List.of(), stopped.services(),
                "neither stopped, so neither may be installed into");
        assertEquals(List.of(Topology.SMP, Topology.LIMBO), planned.services().stream()
                        .filter(line -> !line.changes().isEmpty())
                        .map(UpdateReport.ServiceLine::service)
                        .toList(),
                "and both had work, which is what makes the difference detectable at all");
    }

    // ---------------------------------------------------------------- the backup

    @Test
    @DisplayName("a volume is snapshotted with the servers already stopped, and started after")
    void theSnapshotSitsInTheGap() {
        // The whole correctness of a backup run is this ordering, and it is the one thing no
        // amount of watching a successful run can confirm: a snapshot taken of a server that is
        // still writing to the volume produces an archive that fails at RESTORE, months later,
        // on the day somebody needs it. Nothing at backup time complains.
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), arcane.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"),
                Duration.ofMinutes(60), patient());
        run.start(new UpdateRun.Stopped(saved, stopped.services(), arcane.runtime()));

        assertEquals(List.of("stop:smp-container", "backup:mc-smp", "poll:mc-smp",
                        "start:smp-container"),
                arcane.calls,
                "stopped, then saved, then started - a snapshot outside that gap is a torn one");
        assertEquals(UpdateReport.State.SAVED, saved.line("mc-smp").state());
    }

    @Test
    @DisplayName("every volume is asked for before any of them is waited on")
    void theSnapshotsRunTogether() {
        // Start-and-wait per volume would hold the network down for the SUM of the uploads rather
        // than the longest of them, and Nordtal's first S3 upload is measured in gigabytes.
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        run.save(UpdateReport.at(UpdateReport.Stage.STOPPING),
                List.of("mc-smp", "bot-config"), Duration.ofMinutes(60), patient());

        assertEquals(List.of("backup:mc-smp", "backup:bot-config"), arcane.calls.subList(0, 2),
                "both POSTs go out before the first poll");
        assertTrue(arcane.calls.stream().anyMatch(call -> call.startsWith("poll:")),
                "nothing was polled at all, so the assertion above proves nothing: " + arcane.calls);
    }

    @Test
    @DisplayName("a refused snapshot fails its own line and leaves the others alone")
    void oneRefusedVolumeIsNotAllOfThem() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP).backupRefused("mc-smp");
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING),
                List.of("mc-smp", "bot-config"), Duration.ofMinutes(60), patient());

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state(),
                "one volume Arcane will not touch must not cost the run the volumes it will");
    }

    @Test
    @DisplayName("a snapshot Arcane reports as failed is a failed line, not a saved one")
    void arcaneSayingNoIsBelieved() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP).backupFails("mc-smp");
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING),
                List.of("mc-smp"), Duration.ofMinutes(60), patient());

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(saved.line("mc-smp").detail().contains("archive"),
                "and the reason Arcane gave is what a person reads, not a generic sentence");
    }

    @Test
    @DisplayName("a snapshot that never finishes ends the wait rather than the network")
    void thePatienceIsWhatEndsAWait() {
        // The alternative is worse than a failed backup: the servers are already stopped, so a
        // snapshot that hangs would hold the whole network down until somebody noticed.
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP)
                .backupNeverFinishes("mc-smp");
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING),
                List.of("mc-smp"), Duration.ofMinutes(60), impatient());

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(saved.line("mc-smp").detail().contains("gave up waiting"),
                "the snapshot may still be being written, and the sentence has to say that rather"
                        + " than claim Arcane failed");
    }

    // ---------------------------------------------------------------- the live report

    @Test
    @DisplayName("every step reports its progress, because the row is the progress bar")
    void theRunSaysWhereItIs() {
        final FakeArcane arcane = new FakeArcane().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(arcane, progress::add);

        run.start(run.stop(planned(Topology.SMP), arcane.runtime()));

        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STOPPING));
        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STARTING));
        assertTrue(progress.size() >= 4,
                "a run that only writes its answer at the end leaves an admin looking at an"
                        + " unchanging message for minutes, which is what the live embed exists to"
                        + " stop being");
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

    /** A clock that never runs out, so a wait ends only because the work finished. */
    private static UpdateRun.Waiting patient() {
        return new UpdateRun.Waiting() {
            @Override
            public Instant now() {
                return Instant.parse("2026-09-08T04:45:00Z");
            }

            @Override
            public boolean sleep(final Duration duration) {
                return true;
            }
        };
    }

    /** A clock already past the deadline, so the timeout branch is reached on the first look. */
    private static UpdateRun.Waiting impatient() {
        return new UpdateRun.Waiting() {
            private Instant now = Instant.parse("2026-09-07T12:00:00Z");

            @Override
            public Instant now() {
                final Instant answer = now;
                now = now.plus(UpdateRun.HEALTH_PATIENCE).plusSeconds(1);
                return answer;
            }

            @Override
            public boolean sleep(final Duration duration) {
                return true;
            }
        };
    }
}
