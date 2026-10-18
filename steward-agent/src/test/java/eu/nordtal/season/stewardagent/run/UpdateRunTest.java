package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.Told;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Stop, swap, start, and prove it came back.
 *
 * Back means healthy and not only {@code running}.
 */
class UpdateRunTest {

    /** The run that a mark beside an archive names. */
    private static final long RUN = 312L;

    private final List<UpdateReport> progress = new ArrayList<>();

    @Test
    void aRunWhoseContainerRuntimeIsUnreachableNeverGetsAsFarAsAPlan() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).unreachable();

        assertFalse(
                new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add)
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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        // The only line of smp has no build for this version: something to say, nothing to do.
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
    void stewardIsStoppedLikeAnyOtherServiceSinceTheRunNoLongerRunsInsideIt() {
        final FakeContainers containers = new FakeContainers().running(Topology.STEWARD, Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped =
                run.stop(planned(Topology.STEWARD, Topology.SMP).with(work(Topology.STEWARD)), containers.runtime());

        assertTrue(
                containers.calls.contains("stop:steward-container"),
                "steward-agent carries out the run, so steward's new jar is placed while it is stopped");
        assertTrue(stopped.services().contains(Topology.STEWARD));
    }

    @Test
    void theDatabaseDumpIsAReportLineAndNotAContainerSoTheStopLeavesItAlone() {
        // A stop that finds no matching container must not overwrite a SAVED line.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateReport planned = planned(Topology.SMP)
                .with(new UpdateReport.ServiceLine(
                        Snapshots.DATABASE,
                        UpdateReport.State.SAVED,
                        List.of(new UpdateReport.Change("backup", null, "saved 790.0 KiB in 0s")),
                        null));

        final UpdateRun.Stopped stopped = run.stop(planned, containers.runtime());

        assertEquals(
                UpdateReport.State.SAVED,
                stopped.report().line(Snapshots.DATABASE).state(),
                "the dump is finished before this loop begins and its line is already written."
                        + " Turning it FAILED here reports a backup that exists as a backup that"
                        + " does not, which is the one direction that must never happen");
        assertFalse(
                stopped.services().contains(Snapshots.DATABASE),
                "nothing was stopped for it and nothing may be started for it either");
    }

    @Test
    void aServiceThatCouldNotBeStoppedIsNotInstalledToAndNotStarted() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopFails();
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());

        assertEquals(
                UpdateReport.State.FAILED, stopped.report().line(Topology.SMP).state());
        assertTrue(
                Told.said(stopped.report().line(Topology.SMP)).contains("no container, so it was not stopped"),
                Told.said(stopped.report().line(Topology.SMP)));
    }

    @Test
    void stopComesBeforeStartAndBothAreRecordedInOrder() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
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
                Told.said(verified.line(Topology.SMP)).contains("not healthy within"),
                Told.said(verified.line(Topology.SMP)));
    }

    @Test
    void aRefusedStartEndsTheWaitAtOnceWithTheReason() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped);
        containers.refuses(Topology.SMP, "smp is not starting: the milestones name an unknown track");

        final UpdateReport verified = run.verify(started, stopped.services(), patient());

        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
        assertEquals(
                "refused to start: smp is not starting: the milestones name an unknown track",
                Told.said(verified.line(Topology.SMP)));
    }

    @Test
    void aRestartDuringTheWaitEndsItWithWhyTheLastRunEnded() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped);
        final Waiting clock = new Waiting() {
            private Instant now = Instant.parse("2026-10-18T17:00:00Z");

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                containers.restarted(Topology.SMP, "[nordtal] server exited with status 1");
                return true;
            }
        };

        final UpdateReport verified = run.verify(started, stopped.services(), clock);

        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
        assertEquals(
                "restarted before it was healthy: [nordtal] server exited with status 1",
                Told.said(verified.line(Topology.SMP)));
    }

    /** A clock that never runs out; a wait that does not end early is interrupted after three looks. */
    private static Waiting patient() {
        return new Waiting() {
            private int looks;

            @Override
            public Instant now() {
                return Instant.parse("2026-10-18T17:00:00Z");
            }

            @Override
            public boolean sleep(final Duration duration) {
                return ++looks < 3;
            }
        };
    }

    @Test
    void aServiceThatReportsHealthyInsideTheWindowIsTheSuccessCase() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped);

        // The service comes back on the second look: started, then healthy.
        final Waiting clock = new Waiting() {
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
    void anImageOutOfDateReadsRenewedOnceItsServiceIsBackOnTheNewOne() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(outdated(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped, containers.images());
        containers.back(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(UpdateReport.State.HEALTHY, verified.line(Topology.SMP).state());
        assertEquals("smp 0.7.0, image updated", changes(verified.line(Topology.SMP)));
    }

    @Test
    void anImageStaysOutOfDateWhenItsServiceIsStartedBackOnTheOldOne() {
        // An abort starts the old container again, so its image was not renewed.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(outdated(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped);
        containers.back(Topology.SMP);

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        assertEquals(UpdateReport.State.HEALTHY, verified.line(Topology.SMP).state());
        assertEquals("smp 0.7.0, image out of date", changes(verified.line(Topology.SMP)));
    }

    /** A plan for one service with work, whose image the registry has a newer one of. */
    private static UpdateReport outdated(final String service) {
        final UpdateReport planned = planned(service);
        return planned.with(planned.line(service)
                .with(UpdateReport.Change.told("image", TEXTS.report().imageOutdated())));
    }

    /** A line's changes in English, each its artefact and its version or its words. */
    private static String changes(final UpdateReport.ServiceLine line) {
        return line.changes().stream()
                .map(change -> change.artefact() + " "
                        + (change.told() == null ? change.to() : DatabaseText.english(change.told())))
                .collect(Collectors.joining(", "));
    }

    @Test
    void oneServiceFailingDoesNotLoseTheOnesThatCameBack() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
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
        // Runner aborts on a service with work that is not in stopped.services().
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopFails();
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        // A snapshot that still writes fails at RESTORE, unseen, so the order is the test.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, progress::add);

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
        // Local tars share this host's one disk, so they run one after another.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, progress::add);

        run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp", "bot-config"));

        assertEquals(List.of("backup:mc-smp", "backup:bot-config"), containers.calls);
    }

    @Test
    void oneVolumeThatFailsDoesNotCostTheRunTheVolumesThatWouldHaveWorked() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, progress::add);

        final UpdateReport saved =
                run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp", "bot-config"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertEquals(UpdateReport.State.SAVED, saved.line("bot-config").state());
    }

    @Test
    void aFailureCarriesItsOwnReasonIntoTheReportNotAGenericSentence() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).fails("mc-smp");
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(
                Told.said(saved.line("mc-smp")).contains("not a readable archive"),
                "what the tar said is what a person reads at 04:45: " + Told.said(saved.line("mc-smp")));
    }

    @Test
    void aVolumeThatSavedNothingIsFailedNotASuccessfulLineWithNoBytes() {
        // A run that snapshotted zero volumes must not report success.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final FakeSnapshots snapshots = new FakeSnapshots(containers.calls).savesNothing("mc-smp");
        final UpdateRun run = new UpdateRun(RUN, containers, snapshots, progress::add);

        final UpdateReport saved = run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp"));

        assertEquals(UpdateReport.State.FAILED, saved.line("mc-smp").state());
        assertTrue(Told.said(saved.line("mc-smp")).contains("nothing was saved"), Told.said(saved.line("mc-smp")));
    }

    @Test
    void everyStepReportsItsProgressBecauseTheRowIsTheProgressBar() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()));

        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STOPPING));
        assertTrue(progress.stream().anyMatch(r -> r.stage() == UpdateReport.Stage.STARTING));
        assertTrue(
                progress.size() >= 4,
                "a run that only writes its answer at the end leaves an admin looking at an"
                        + " unchanging message for minutes, which is what the live card exists to"
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

    /** A clock inside the window that lets the service come back on the second look. */
    private static Waiting comesBackOnTheSecondLook(final FakeContainers containers, final String service) {
        return new Waiting() {
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

    /** A clock past the deadline, so the timeout branch runs on the first look. */
    private static Waiting impatient() {
        return new Waiting() {
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
        // A start hands the container back on the image it was created from.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP, Topology.LIMBO), containers.runtime()), containers.images());

        // limbo is not in the images map, and UNKNOWN is never a reason to pull.
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
    }

    @Test
    void puttingTheNetworkBackNeverPullsAnImageWhateverTheImagesSay() {
        // Every path undoing something calls the one-argument start(); pulling an image there would change a version.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

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
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        final UpdateReport report =
                run.start(run.stop(planned(Topology.SMP), containers.runtime()), containers.images());

        // The old container starts again after the refused recreate and the line fails.
        assertEquals(List.of("stop:smp-container", "recreate:smp", "start:smp-container"), containers.calls);
        assertEquals(
                UpdateReport.State.FAILED,
                report.line(Topology.SMP).state(),
                "the update did not happen, and a run is settled FAILED the moment a line is");
        assertTrue(
                Told.said(report.line(Topology.SMP)).contains("image out of date"),
                "the reason has to say which half of the run stopped: " + Told.said(report.line(Topology.SMP)));
        assertTrue(
                Told.said(report.line(Topology.SMP)).contains("started on the old image"),
                "and it has to say the old container was put back, or an operator starts by hand"
                        + " one that is already running: "
                        + Told.said(report.line(Topology.SMP)));
        assertFalse(
                Told.said(report.line(Topology.SMP)).contains("the service is back"),
                "this line ended 'so the service is back' until 2026-09-13, written on the strength"
                        + " of Docker having accepted a start. Docker accepts one just as readily"
                        + " for a container that exits on the first tick. Whether it came back is"
                        + " verify()'s to find out and to finish the sentence with: "
                        + Told.said(report.line(Topology.SMP)));
    }

    @Test
    void aFallbackThatReallyCameBackIsSaidToHaveComeBackAndOnlyThen() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped, containers.images());

        final UpdateReport verified =
                run.verify(started, stopped.services(), comesBackOnTheSecondLook(containers, Topology.SMP));

        final String detail = Told.said(verified.line(Topology.SMP));
        assertEquals(
                UpdateReport.State.FAILED,
                verified.line(Topology.SMP).state(),
                "the update genuinely did not happen - the jars moved and the image did not - so"
                        + " the line stays FAILED however well the old version is running");
        assertTrue(
                detail.contains("back on the old image"),
                "which version is running is the question an admin has next, and a bare FAILED"
                        + " sends somebody to look at a server that is fine: " + detail);
        assertFalse(
                detail.contains("not running within"),
                "a service that came back must not also be reported as down: " + detail);
    }

    @Test
    void aFallbackThatNeverCameBackSaysTheServiceIsDownNotThatItIsBack() {
        // A container that exits on the first tick is not a service back on the old version.
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);
        final UpdateRun.Stopped stopped = run.stop(planned(Topology.SMP), containers.runtime());
        final UpdateReport started = run.start(stopped, containers.images());

        final UpdateReport verified = run.verify(started, stopped.services(), impatient());

        final String detail = Told.said(verified.line(Topology.SMP));
        assertEquals(UpdateReport.State.FAILED, verified.line(Topology.SMP).state());
        assertTrue(
                detail.contains("not running within " + UpdateRun.HEALTH_PATIENCE.toMinutes() + " minutes"),
                "how long was waited is half of what makes this actionable: " + detail);
        assertTrue(
                detail.contains("running, starting"),
                "the runtime was actually re-read - this used to be a line verify() never looked"
                        + " at, because only STARTING lines were waited on: " + detail);
        assertFalse(
                detail.contains("back on the old image"),
                "a service that is down must not also be reported as back: " + detail);
    }

    @Test
    void theRecreateIsAnnouncedBeforeItIsAskedForSoARunThatDiesInItSaysWhere() {
        // A recreate that considered steward a dependency could take down the process making the call.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add);

        run.start(run.stop(planned(Topology.SMP), containers.runtime()), containers.images());

        final int announced = progress.stream()
                .filter(report -> report.line(Topology.SMP).said() != null
                        && Told.said(report.line(Topology.SMP)).contains("recreating"))
                .findFirst()
                .map(progress::indexOf)
                .orElse(-1);
        assertTrue(announced >= 0, "no report said the recreate was about to happen");
        assertTrue(containers.calls.indexOf("recreate:" + Topology.SMP) >= 0);
    }
}
