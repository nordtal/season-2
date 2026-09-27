package eu.nordtal.s2.steward.worker.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stop, swap, start, and prove it came back.
 *
 * Why this is tested against a fake and not rehearsed: Rehearsing this needs a live compose project and a
 * willingness to take the network down, and the cases worth testing are the ones a healthy stack will not produce on
 * demand: a daemon that has stopped answering, a stop that is refused, a container that comes back {@code running}
 * and sick. Every decision in the sequence is therefore held here: what it refuses to start, what it will not stop,
 * what it does when a stop fails, and above all what it calls "back".
 *
 * The failures it has to survive are the interesting cases: A container that is {@code running} with a dead plugin
 * inside it is the exact shape the first deployment produced - green healthcheck, open port, no season on it - and
 * it is why every process writes a readiness marker at all. A sequence that accepted {@code running} would report a
 * successful update over a network that is down.
 */
class UpdateRunTest {

    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    void aRunWhoseContainerRuntimeIsUnreachableNeverGetsAsFarAsAPlan() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).unreachable();

        assertFalse(
                new UpdateRun(containers, new FakeSnapshots(), progress::add)
                        .check()
                        .reached(),
                "the runtime read is the first thing a run does, before a version is resolved or a"
                        + " byte is downloaded - because a run that cannot STOP a server must not"
                        + " move a jar underneath it. Continuing anyway is the defect performed as a"
                        + " fallback");
        assertEquals(List.of(), containers.calls, "and nothing was asked of the daemon");
    }

    @Test
    void onlyServicesWithWorkAreStopped() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP, Topology.LIMBO), containers.runtime());

        assertEquals(
                List.of("stop:smp-container"),
                containers.calls,
                "limbo has no changes, and stopping it would be an outage with nothing to show" + " for it");
        assertEquals(List.of(Topology.SMP), stopped.services());
        assertEquals(
                UpdateReport.State.STOPPED, stopped.report().line(Topology.SMP).state());
        assertEquals(
                UpdateReport.State.UNCHANGED,
                stopped.report().line(Topology.LIMBO).state());
    }

    @Test
    void aServiceIsNotStoppedForAnArtefactThatHasNoBuildToInstall() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        // smp's only line has no build for this version: something to SAY, nothing to do - and it is not empty.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        Topology.SMP,
                        UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")),
                        null));

        final UpdateRun.Stopped stopped = run.stop(report, containers.runtime());

        assertEquals(
                List.of(),
                containers.calls,
                "the SMP was taken down because somebody else has not published a jar yet");
        assertEquals(List.of(), stopped.services());
    }

    @Test
    void stewardWorkerNeverStopsItself() {
        final FakeContainers containers = new FakeContainers().running(Topology.STEWARD_WORKER, Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(
                planned(Topology.STEWARD_WORKER, Topology.SMP).with(work(Topology.STEWARD_WORKER)),
                containers.runtime());

        assertFalse(
                containers.calls.contains("stop:steward-worker-container"),
                "this sequence is running inside steward-worker; stopping it kills the process that"
                        + " would otherwise start everything else again - which is exactly why the"
                        + " old project-wide redeploy could never report whether anything returned");
        assertFalse(stopped.services().contains(Topology.STEWARD_WORKER));
        assertEquals(
                UpdateReport.State.INSTALLED,
                stopped.report().line(Topology.STEWARD_WORKER).state(),
                "its jar is still placed; it is picked up at its next start, as it always was");
    }

    @Test
    void theDatabaseDumpIsAReportLineAndNotAContainerSoTheStopLeavesItAlone() {
        // The database dump succeeds while a stop finding no matching container must not overwrite a SAVED line.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateReport planned = planned(Topology.SMP)
                .with(new UpdateReport.ServiceLine(
                        DatabaseDump.NAME,
                        UpdateReport.State.SAVED,
                        List.of(new UpdateReport.Change("backup", null, "saved 790.0 KiB in 0s")),
                        null));

        final UpdateRun.Stopped stopped = run.stop(planned, containers.runtime());

        assertEquals(
                UpdateReport.State.SAVED,
                stopped.report().line(DatabaseDump.NAME).state(),
                "the dump is finished before this loop begins and its line is already written."
                        + " Turning it FAILED here reports a backup that exists as a backup that"
                        + " does not, which is the one direction that must never happen");
        assertFalse(
                stopped.services().contains(DatabaseDump.NAME),
                "nothing was stopped for it and nothing may be started for it either");
    }

    @Test
    void aServiceThatCouldNotBeStoppedIsNotInstalledToAndNotStarted() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopFails();
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());

        assertEquals(
                List.of(),
                stopped.services(),
                "starting something that was never stopped is how one failure becomes two");
        assertEquals(
                UpdateReport.State.FAILED, stopped.report().line(Topology.SMP).state());
        run.start(stopped);
        assertFalse(containers.calls.contains("start:smp-container"));
    }

    @Test
    void aServiceTheProjectHasNoContainerForFailsByNameRatherThanSilently() {
        final FakeContainers containers = new FakeContainers().running(Topology.LIMBO);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());

        assertEquals(
                UpdateReport.State.FAILED, stopped.report().line(Topology.SMP).state());
        assertTrue(
                stopped.report().line(Topology.SMP).detail().contains("no container for this"),
                stopped.report().line(Topology.SMP).detail());
    }

    @Test
    void stopComesBeforeStartAndBothAreRecordedInOrder() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()));

        assertEquals(
                List.of("stop:smp-container", "start:smp-container"),
                containers.calls,
                "the gap between these two is where jars are safe to move, and it is the entire"
                        + " reason this is container-level rather than one project-wide call");
    }

    @Test
    void aServiceThatIsRunningButNotHealthyHasNotComeBack() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped);
        containers.sick(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(
                UpdateReport.State.FAILED,
                verified.line(Topology.SMP).state(),
                "a container whose plugin threw in onEnable is 'running' with an open port and no"
                        + " season on it - which is what the first deployment actually did, and is"
                        + " why every process writes a readiness marker");
        assertTrue(
                verified.line(Topology.SMP).detail().contains("did not come back"),
                verified.line(Topology.SMP).detail());
    }

    @Test
    void aServiceThatReportsHealthyInsideTheWindowIsTheSuccessCase() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
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
                containers.back(Topology.SMP);
                return true;
            }
        };

        final UpdateReport verified = run.verify(started, stopped.services(), clock);

        assertEquals(UpdateReport.State.HEALTHY, verified.line(Topology.SMP).state());
    }

    @Test
    void oneServiceFailingDoesNotLoseTheOnesThatCameBack() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped =
                run.stop(planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO)), containers.runtime());
        final UpdateReport started = run.start(stopped);
        containers.back(Topology.LIMBO);
        containers.sick(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(
                UpdateReport.State.HEALTHY,
                verified.line(Topology.LIMBO).state(),
                "the run is a failure, and 'which servers are up' is still the question somebody"
                        + " has at three in the morning");
        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
    }

    @Test
    void aServiceThatRefusedToStopIsNotInTheSetTheRunMayInstallInto() {
        // Runner reads exactly this to abort: a service with work not in stopped.services() is still RUNNING.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopFails();
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateReport planned = planned(Topology.SMP, Topology.LIMBO).with(work(Topology.LIMBO));
        final UpdateRun.Stopped stopped = run.stop(planned, containers.runtime());

        assertEquals(List.of(), stopped.services(), "neither stopped, so neither may be installed into");
        assertEquals(
                List.of(Topology.SMP, Topology.LIMBO),
                planned.services().stream()
                        .filter(line -> !line.changes().isEmpty())
                        .map(UpdateReport.ServiceLine::service)
                        .toList(),
                "and both had work, which is what makes the difference detectable at all");
    }

    @Test
    void aVolumeIsSavedWithTheServersAlreadyStoppedAndStartedAfter() {
        // The whole correctness of a backup run is this ordering: a still-writing snapshot fails at RESTORE, unseen.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport saved = run.save(stopped.report(), List.of("mc-smp"));
        run.start(new UpdateRun.Stopped(saved, stopped.services(), containers.runtime()));

        assertEquals(
                List.of("stop:smp-container", "backup:mc-smp", "start:smp-container"),
                containers.calls,
                "stopped, then saved, then started - a snapshot outside that gap is a torn one");
        assertEquals(UpdateReport.State.SAVED, saved.line("mc-smp").state());
    }

    @Test
    void theVolumesAreSavedOneAfterAnotherInTheOrderTheyAreConfigured() {
        // Reverses the HTTP version: a local tar is this host's one disk, and running them all at once fights over it.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp", "bot-config"));

        assertEquals(List.of("backup:mc-smp", "backup:bot-config"), containers.calls);
    }

    @Test
    void oneVolumeThatFailsDoesNotCostTheRunTheVolumesThatWouldHaveWorked() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateReport saved =
                run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp", "bot-config"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state());
    }

    @Test
    void aFailureCarriesItsOwnReasonIntoTheReportNotAGenericSentence() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(
                saved.line("mc-smp").detail().contains("not a readable archive"),
                "what the tar said is what a person reads at 04:45: "
                        + saved.line("mc-smp").detail());
    }

    @Test
    void aVolumeThatSavedNothingIsFailedNotASuccessfulLineWithNoBytes() {
        // A past run once reported success having snapshotted zero volumes; this stops that happening again.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).savesNothing("mc-smp");
        final UpdateRun run = new UpdateRun(containers, snapshots, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(
                saved.line("mc-smp").detail().contains("nothing was saved"),
                saved.line("mc-smp").detail());
    }

    @Test
    void everyStepReportsItsProgressBecauseTheRowIsTheProgressBar() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()));

        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STOPPING));
        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STARTING));
        assertTrue(
                progress.size() >= 4,
                "a run that only writes its answer at the end leaves an admin looking at an"
                        + " unchanging message for minutes, which is what the live embed exists to"
                        + " stop being");
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

    /**
     * A clock well inside the window that lets the service come back while it is being waited for.
     *
     * The wait is driven rather than slept through: the sleep advances the instant and makes the container healthy,
     * which is what a real start does one poll later.
     */
    private static UpdateRun.Waiting comesBackOnTheSecondLook(final FakeContainers containers, final String service) {
        return new UpdateRun.Waiting() {
            private Instant now = Instant.parse("2026-09-07T12:00:00Z");

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                containers.back(service);
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

    @Test
    void aServiceWhoseImageHasMovedIsRecreatedNotStarted() {
        // The whole point of the image path: a start hands the container back on exactly the image it was created from.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()), containers.images());

        assertEquals(
                List.of("stop:smp-container", "recreate:smp"),
                containers.calls,
                "the stop is unchanged - the gap is still where jars move - and only the way back" + " up differs");
    }

    @Test
    void aCurrentImageIsStartedExactlyAsBeforeAndSoIsAnUncheckedOne() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).imageCurrent(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP, Topology.LIMBO), containers.runtime()), containers.images());

        // limbo is not in the images map at all, UNKNOWN never being a reason to pull; only smp had work.
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
    }

    @Test
    void puttingTheNetworkBackNeverPullsAnImageWhateverTheImagesSay() {
        // Every path undoing something calls the one-argument start(); pulling an image there would change a version.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()));

        assertEquals(
                List.of("stop:smp-container", "start:smp-container"),
                containers.calls,
                "an abort puts the network back the way it was and does not take the opportunity"
                        + " to move an image nobody asked it to move");
    }

    @Test
    void aRefusedRecreatePutsTheOldContainerBackAndStillReportsTheFailure() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        final UpdateReport report =
                run.start(run.stop(planned(Topology.SMP), containers.runtime()), containers.images());

        // The server is BACK. This used to stop it and fail the line, leaving it off until somebody looked.
        assertEquals(List.of("stop:smp-container", "recreate:smp", "start:smp-container"), containers.calls);
        assertEquals(
                UpdateReport.State.FAILED,
                report.line(Topology.SMP).state(),
                "the update did not happen, and a run is settled FAILED the moment a line is");
        assertTrue(
                report.line(Topology.SMP).detail().contains("image is out of date"),
                "the reason has to say which half of the run stopped: "
                        + report.line(Topology.SMP).detail());
        assertTrue(
                report.line(Topology.SMP).detail().contains("started again on the image it already had"),
                "and it has to say the old container was put back, or an operator starts by hand"
                        + " one that is already running: "
                        + report.line(Topology.SMP).detail());
        assertFalse(
                report.line(Topology.SMP).detail().contains("the service is back"),
                "this line ended 'so the service is back' until 2026-09-13, written on the strength"
                        + " of Docker having accepted a start. Docker accepts one just as readily"
                        + " for a container that exits on the first tick. Whether it came back is"
                        + " verify()'s to find out and to finish the sentence with: "
                        + report.line(Topology.SMP).detail());
    }

    @Test
    void aFallbackThatReallyCameBackIsSaidToHaveComeBackAndOnlyThen() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped, containers.images());

        final UpdateReport verified =
                run.verify(started, stopped.services(), comesBackOnTheSecondLook(containers, Topology.SMP));

        final String detail = verified.line(Topology.SMP).detail();
        assertEquals(
                UpdateReport.State.FAILED,
                verified.line(Topology.SMP).state(),
                "the update genuinely did not happen - the jars moved and the image did not - so"
                        + " the line stays FAILED however well the old version is running");
        assertTrue(
                detail.contains("it is back on that old version"),
                "which version is running is the question an admin has next, and a bare FAILED"
                        + " sends somebody to look at a server that is fine: " + detail);
        assertFalse(
                detail.contains("did NOT come back"),
                "a service that came back must not also be reported as down: " + detail);
    }

    @Test
    void aFallbackThatNeverCameBackSaysTheServiceIsDownNotThatItIsBack() {
        // A container exiting on the first tick used to be reported as a service back on the old version.
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped, containers.images());

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        final String detail = verified.line(Topology.SMP).detail();
        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
        assertTrue(
                detail.contains("did NOT come back within " + UpdateRun.HEALTH_PATIENCE.toMinutes() + " minutes"),
                "how long was waited is half of what makes this actionable: " + detail);
        assertTrue(
                detail.contains("The service is down."),
                "and somebody has to be told to go and look, in those words: " + detail);
        assertTrue(
                detail.contains("running, starting"),
                "the runtime was actually re-read - this used to be a line verify() never looked"
                        + " at, because only STARTING lines were waited on: " + detail);
        assertFalse(
                detail.contains("is back on that old version"),
                "a service that is down must not also be reported as back: " + detail);
    }

    @Test
    void theRecreateIsAnnouncedBeforeItIsAskedForSoARunThatDiesInItSaysWhere() {
        // A recreate that considered the worker a dependency could take down the process making the call.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()), containers.images());

        final int announced = progress.stream()
                .filter(report -> report.line(Topology.SMP).detail() != null
                        && report.line(Topology.SMP).detail().contains("recreating"))
                .findFirst()
                .map(progress::indexOf)
                .orElse(-1);
        assertTrue(announced >= 0, "no report said the recreate was about to happen");
        assertTrue(containers.calls.indexOf("recreate:" + Topology.SMP) >= 0);
    }
}
