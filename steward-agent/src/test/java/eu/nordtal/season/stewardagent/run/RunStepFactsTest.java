package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The facts a run writes on its steps: archives, exit codes, the versions verified and the cache freed. */
class RunStepFactsTest {

    private static final long RUN = 312L;

    private final CollectedSteps collected = new CollectedSteps();
    private final Steps record = new Steps(collected, RUN);

    @Test
    void aSavedVolumeIsAnArchiveAndAFailedOneKeepsTheToolsExitCode() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run =
                new UpdateRun(RUN, containers, new FakeSnapshots().fails("bot-config"), report -> {}, record);

        run.save(UpdateReport.at(UpdateReport.Stage.STOPPING), List.of("mc-smp", "bot-config"));

        final List<RunStep.Row> steps = collected.rows();
        assertEquals(
                List.of(new RunStep.Archive(
                        "mc-smp", "mc-smp-20260913T000000Z.tar.zst", 1_234_567, 12_000, true, null)),
                steps.get(0).saved());
        assertEquals(RunStep.Outcome.DONE, steps.get(0).outcome());
        assertEquals(List.of(), steps.get(1).saved());
        assertEquals(RunStep.Reason.TOOL_FAILED, steps.get(1).reason());
        assertEquals(2, steps.get(1).exitCode());
    }

    @Test
    void aServiceThatCameBackRecordsTheVersionsItRunsOnItsVerifyStep() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun run = new UpdateRun(RUN, containers, new FakeSnapshots(), report -> {}, record);
        final UpdateReport planned = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        Topology.SMP,
                        UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change(Topology.SMP, "0.6.0", "0.7.0")),
                        null));
        final UpdateRun.Stopped stopped = run.stop(planned, containers.runtime());
        final UpdateReport started = run.start(stopped);

        run.verify(started, stopped.services(), backOnTheSecondLook(containers));

        final RunStep.Row verified = collected.rows().stream()
                .filter(step -> step.step() == RunStep.Kind.VERIFY)
                .findFirst()
                .orElseThrow();
        assertEquals(RunStep.Outcome.DONE, verified.outcome());
        assertEquals(List.of(new RunStep.Change(Topology.SMP, null, "0.7.0", null)), verified.changes());
    }

    @Test
    void theCleanupRecordsTheBuildCacheItFreed() {
        final FakeContainers containers = new FakeContainers().running(Topology.SMP);
        final UpdateRun steps = new UpdateRun(RUN, containers, new FakeSnapshots(), report -> {});
        final Run.Done done = Kinds.pruningImages(containers, Run.Payload.NONE, record)
                .carryOut(
                        steps,
                        new UpdateRun.Stopped(
                                UpdateReport.at(UpdateReport.Stage.STOPPING), List.of(), containers.runtime()));

        assertEquals(
                UpdateReport.Stage.STOPPING,
                done.afterwards().apply(done.report()).stage());

        final RunStep.Row cleanup = collected.rows().getFirst();
        assertEquals(RunStep.Kind.CLEANUP, cleanup.step());
        assertEquals(300_000_000L, cleanup.cacheFreed());
        assertEquals(List.of(new RunStep.ImageRemoved("sha256:old", 2_000_000_000L)), cleanup.imagesRemoved());
    }

    private static Waiting backOnTheSecondLook(final FakeContainers containers) {
        return new Waiting() {
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
    }
}
