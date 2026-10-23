package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Stop, swap, start, and prove it came back.
 *
 * Back means healthy and not only {@code running}.
 */
class UpdateRunTest {

    /** The run that a mark beside an archive names. */
    private static final long RUN = 312L;

    private final List<RunStage> progress = new ArrayList<>();
    private final CollectedSteps collected = new CollectedSteps();

    @Test
    void aRunWhoseContainerRuntimeIsUnreachableNeverGetsAsFarAsAPlan() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).unreachable();

        assertFalse(
                run(containers).check().reached(),
                "the runtime read is the first thing a run does, before a version is resolved or a"
                        + " byte is downloaded - because a run that cannot STOP a server must not"
                        + " move a jar underneath it");
        assertEquals(List.of(), containers.calls, "and nothing was asked of the daemon");
    }

    @Test
    void stewardIsStoppedLikeAnyOtherServiceSinceTheRunNoLongerRunsInsideIt() {
        final FakeContainers containers = new FakeContainers().running(Topology.STEWARD, Topology.SMP);

        final UpdateRun.Stopped stopped =
                run(containers).stop(List.of(Topology.STEWARD, Topology.SMP), containers.runtime());

        assertTrue(
                containers.calls.contains("stop:steward-container"),
                "steward-agent carries out the run, so steward's new jar is placed while it is stopped");
        assertTrue(stopped.services().contains(Topology.STEWARD));
    }

    @Test
    void aServiceThatCouldNotBeStoppedIsNotInstalledToAndNotStarted() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).stopFails();
        final UpdateRun run = run(containers);

        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());

        assertEquals(
                List.of(),
                stopped.services(),
                "starting something that was never stopped is how one failure becomes two");
        assertStep(RunStep.Kind.STOP, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.REFUSED);
        run.start(stopped);
        assertFalse(containers.calls.contains("start:smp-container"));
    }

    @Test
    void aServiceTheProjectHasNoContainerForFailsByNameRatherThanSilently() {
        final FakeContainers containers = new FakeContainers().running(Topology.LIMBO);

        run(containers).stop(List.of(Topology.SMP), containers.runtime());

        assertStep(RunStep.Kind.STOP, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.NO_CONTAINER);
    }

    @Test
    void stopComesBeforeStartAndBothAreRecordedInOrder() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()));

        assertEquals(
                List.of("stop:smp-container", "start:smp-container"),
                containers.calls,
                "the gap between these two is where jars are safe to move");
        assertEquals(
                List.of(RunStep.Kind.STOP, RunStep.Kind.START),
                collected.rows().stream().map(RunStep.Row::step).toList());
        assertStep(RunStep.Kind.START, Topology.SMP, RunStep.Outcome.DONE, null);
    }

    @Test
    void aServiceThatIsRunningButNotHealthyHasNotComeBack() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped);
        containers.sick(Topology.SMP);

        run.verify(stopped.services(), impatient());

        // A container whose plugin threw in onEnable is 'running' with an open port and no season on it.
        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY);
    }

    @Test
    void aRefusedStartEndsTheWaitAtOnceWithTheReason() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped);
        containers.refuses(Topology.SMP, "smp is not starting: the milestones name an unknown track");

        run.verify(stopped.services(), patient());

        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.REFUSED);
    }

    @Test
    void aRestartDuringTheWaitEndsItBeforeThePatienceRunsOut() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped);
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

        run.verify(stopped.services(), clock);

        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY);
    }

    @Test
    void aServiceThatReportsHealthyInsideTheWindowIsTheSuccessCase() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped);

        run.verify(stopped.services(), comesBackOnTheSecondLook(containers, Topology.SMP));

        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.DONE, null);
    }

    @Test
    void anImageOutOfDateIsPulledAndTheServiceComesBackWithTheVersionsItWasGiven() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped, containers.images(), List.of(), false);
        containers.back(Topology.SMP);

        run.verify(stopped.services(), impatient(), installed());

        assertEquals(
                List.of(new RunStep.Change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED)),
                step(RunStep.Kind.INSTALL, Topology.SMP).changes());
        assertStep(RunStep.Kind.INSTALL, Topology.SMP, RunStep.Outcome.DONE, null);
        assertEquals(
                List.of(new RunStep.Change(Topology.SMP, null, "0.7.0", null)),
                step(RunStep.Kind.VERIFY, Topology.SMP).changes());
    }

    @Test
    void anOutdatedImageIsNotRenewedWhenItsServiceIsStartedBackOnTheOldOne() {
        // An abort starts the old container again, so its image was not renewed.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped);
        containers.back(Topology.SMP);

        run.verify(stopped.services(), impatient());

        assertEquals(
                List.of(RunStep.Kind.STOP, RunStep.Kind.START, RunStep.Kind.VERIFY),
                collected.rows().stream().map(RunStep.Row::step).toList());
        assertEquals(List.of(), step(RunStep.Kind.VERIFY, Topology.SMP).changes());
    }

    @Test
    void oneServiceFailingDoesNotLoseTheOnesThatCameBack() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP, Topology.LIMBO);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP, Topology.LIMBO), containers.runtime());
        run.start(stopped);
        containers.back(Topology.LIMBO);
        containers.sick(Topology.SMP);

        run.verify(stopped.services(), impatient());

        assertStep(RunStep.Kind.VERIFY, Topology.LIMBO, RunStep.Outcome.DONE, null);
        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY);
    }

    @Test
    void aServiceThatRefusedToStopIsNotInTheSetTheRunMayInstallInto() {
        // Runner aborts when a service it moves is not in stopped.services().
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).stopFails();

        final UpdateRun.Stopped stopped =
                run(containers).stop(List.of(Topology.SMP, Topology.LIMBO), containers.runtime());

        assertEquals(List.of(), stopped.services(), "neither stopped, so neither may be installed into");
    }

    @Test
    void aVolumeIsSavedWithTheServersAlreadyStoppedAndStartedAfter() {
        // A snapshot that still writes fails at RESTORE, unseen, so the order is the test.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(
                RUN, containers, new FakeSnapshots(containers.calls), progress::add, new Steps(collected, RUN));

        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of("mc-smp"));
        run.start(stopped);

        assertEquals(
                List.of("stop:smp-container", "backup:mc-smp", "start:smp-container"),
                containers.calls,
                "stopped, then saved, then started - a snapshot outside that gap is a torn one");
        assertStep(RunStep.Kind.SAVE_VOLUME, "mc-smp", RunStep.Outcome.DONE, null);
    }

    @Test
    void theVolumesAreSavedOneAfterAnotherInTheOrderTheyAreConfigured() {
        // Local tars share this host's one disk, so they run one after another.
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(
                RUN, containers, new FakeSnapshots(containers.calls), progress::add, new Steps(collected, RUN));

        run.save(List.of("mc-smp", "bot-config"));

        assertEquals(List.of("backup:mc-smp", "backup:bot-config"), containers.calls);
    }

    @Test
    void oneVolumeThatFailsDoesNotCostTheRunTheVolumesThatWouldHaveWorked() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(
                RUN,
                containers,
                new FakeSnapshots(containers.calls).fails("mc-smp"),
                progress::add,
                new Steps(collected, RUN));

        run.save(List.of("mc-smp", "bot-config"));

        assertStep(RunStep.Kind.SAVE_VOLUME, "mc-smp", RunStep.Outcome.FAILED, RunStep.Reason.TOOL_FAILED);
        assertStep(RunStep.Kind.SAVE_VOLUME, "bot-config", RunStep.Outcome.DONE, null);
    }

    @Test
    void everyPhaseReportsItsStageBecauseTheRowIsTheProgressBar() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = run(containers);

        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.save(List.of());
        run.start(stopped);
        containers.back(Topology.SMP);
        run.verify(stopped.services(), impatient());

        assertEquals(List.of(RunStage.STOPPING, RunStage.BACKING_UP, RunStage.STARTING, RunStage.VERIFYING), progress);
    }

    @Test
    void aServiceWhoseImageHasMovedIsRecreatedNotStarted() {
        // A start hands the container back on the image it was created from.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = run(containers);

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()), containers.images(), List.of(), false);

        assertEquals(
                List.of("stop:smp-container", "recreate:smp"),
                containers.calls,
                "the stop is unchanged - the gap is still where jars move - and only the way back up differs");
    }

    @Test
    void aCurrentImageIsStartedExactlyAsBeforeAndSoIsAnUncheckedOne() {
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP, Topology.LIMBO).imageCurrent(Topology.SMP);
        final UpdateRun run = run(containers);

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()), containers.images(), List.of(), false);

        // limbo is not in the images map, and UNKNOWN is never a reason to pull.
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
    }

    @Test
    void puttingTheNetworkBackNeverPullsAnImageWhateverTheImagesSay() {
        // Every path undoing something calls the one-argument start(); pulling an image there would change a version.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final UpdateRun run = run(containers);

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()));

        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
    }

    @Test
    void aRefusedRecreatePutsTheOldContainerBackAndTheInstallStaysFailed() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = run(containers);

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()), containers.images(), List.of(), false);

        assertEquals(List.of("stop:smp-container", "recreate:smp", "start:smp-container"), containers.calls);
        assertStep(RunStep.Kind.INSTALL, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.REFUSED);
        assertStep(RunStep.Kind.START, Topology.SMP, RunStep.Outcome.DONE, null);
    }

    @Test
    void aFallbackThatCameBackIsBackWithoutTheVersionsItWasNotGiven() {
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped, containers.images(), List.of(), false);

        run.verify(stopped.services(), comesBackOnTheSecondLook(containers, Topology.SMP), installed());

        // The failed install already fails the run; the verify step says the old version is up.
        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.DONE, null);
        assertEquals(List.of(), step(RunStep.Kind.VERIFY, Topology.SMP).changes());
    }

    @Test
    void aFallbackThatNeverCameBackFailsItsVerify() {
        // A container that exits on the first tick is not a service back on the old version.
        final FakeContainers containers = new FakeContainers()
                .running(Topology.SMP)
                .imageOutdated(Topology.SMP)
                .recreateRefused(Topology.SMP);
        final UpdateRun run = run(containers);
        final UpdateRun.Stopped stopped = run.stop(List.of(Topology.SMP), containers.runtime());
        run.start(stopped, containers.images(), List.of(), false);

        run.verify(stopped.services(), impatient(), installed());

        assertStep(RunStep.Kind.VERIFY, Topology.SMP, RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY);
    }

    @Test
    void theRecreateIsWrittenAsAStepBeforeItIsAskedForSoARunThatDiesInItSaysWhere() {
        // A recreate that considered steward a dependency could take down the process making the call.
        final FakeContainers containers =
                new FakeContainers().running(Topology.SMP).imageOutdated(Topology.SMP);
        final RunRecorder announcing = new RunRecorder() {
            @Override
            public long begin(final long run, final RunStep.Kind step, final @Nullable String service) {
                containers.calls.add("begin:" + step + ":" + service);
                return 1;
            }
        };
        final UpdateRun run =
                new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add, new Steps(announcing, RUN));

        run.start(run.stop(List.of(Topology.SMP), containers.runtime()), containers.images(), List.of(), false);

        assertEquals(
                List.of("begin:STOP:smp", "stop:smp-container", "begin:INSTALL:smp", "recreate:smp"), containers.calls);
    }

    private UpdateRun run(final FakeContainers containers) {
        return new UpdateRun(RUN, containers, new FakeSnapshots(), progress::add, new Steps(collected, RUN));
    }

    /** What the run gave smp, as the plan names it: a new jar and a pulled image. */
    private static Map<String, List<RunStep.Change>> installed() {
        return Map.of(
                Topology.SMP,
                List.of(
                        new RunStep.Change(Topology.SMP, "0.6.0", "0.7.0", null),
                        new RunStep.Change(UpdateRun.IMAGE, null, null, RunStep.Image.PULLED)));
    }

    private RunStep.Row step(final RunStep.Kind kind, final String service) {
        return collected.rows().stream()
                .filter(row -> row.step() == kind && service.equals(row.service()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " step for " + service + ": " + collected.rows()));
    }

    private void assertStep(
            final RunStep.Kind kind,
            final String service,
            final RunStep.Outcome outcome,
            final RunStep.@Nullable Reason reason) {
        final RunStep.Row row = step(kind, service);
        assertEquals(outcome, row.outcome(), row.toString());
        assertEquals(reason, row.reason(), row.toString());
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
}
