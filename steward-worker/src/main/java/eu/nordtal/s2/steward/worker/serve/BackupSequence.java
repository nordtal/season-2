package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.steward.worker.backup.DatabaseDump;
import eu.nordtal.s2.steward.worker.backup.Retention;
import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.config.BackupSpec;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** The choreography a backup runs once it holds the lock: the same shape as an update, with nothing to install. */
final class BackupSequence {

    private BackupSequence() {}

    /** The database dump line, plus a PLANNED line for each service {@code backup.stop-services} names. */
    static UpdateReport prepareReport(final Runner runner, final Consumer<UpdateReport> progress) {
        // THE DATABASE FIRST, WITH EVERYTHING RUNNING: pg_dump's MVCC snapshot needs nothing stopped.
        final SnapshotResult dumped = runner.backups.saveDatabase();
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING)
                .with(new UpdateReport.ServiceLine(
                        DatabaseDump.NAME,
                        dumped.ok() ? UpdateReport.State.SAVED : UpdateReport.State.FAILED,
                        List.of(new UpdateReport.Change("backup", null, dumped.message())),
                        dumped.ok() ? null : dumped.message()));
        progress.accept(planned);

        for (final String service : runner.config.backup().stopServices()) {
            if (service == null || service.isBlank()) {
                continue;
            }
            planned = planned.with(new UpdateReport.ServiceLine(
                    service.trim(),
                    UpdateReport.State.PLANNED,
                    List.of(new UpdateReport.Change("backup", null, "stopped while saving")),
                    null));
        }
        return planned;
    }

    /** What was kept and what was removed, put into the report - a retention nobody sees may be wrong for months. */
    static UpdateReport pruneAfterBackup(final Runner runner, final UpdateReport saved) {
        final BackupSpec.RetentionSpec keep = runner.config.backup().retention();
        final Retention policy = new Retention(keep.daily(), keep.weekly(), keep.monthly(), keep.collapseAfterDays());
        final List<String> pruned = runner.backups.volumes().prune(policy);
        return pruned.isEmpty()
                ? saved
                : saved.withNote("kept " + policy.daily() + " daily, " + policy.weekly() + " weekly and "
                        + policy.monthly() + " monthly of each series, and removed " + pruned.size() + ": "
                        + String.join(", ", pruned));
    }

    /** Refuses a backup when a service that was asked to stop refused, or {@code null} when every one of them did. */
    static @Nullable Outcome refuseIfNotStopped(
            final UpdateReport planned,
            final UpdateRun.Stopped stopped,
            final UpdateRun run,
            final RuntimeResult runtime) {
        // A refused stop leaves a service writing to a volume; a torn snapshot fails at RESTORE, not here.
        final List<String> notStopped = Runner.servicesThatRefused(planned, stopped.services());
        if (notStopped.isEmpty()) {
            return null;
        }
        final UpdateReport back = run.start(new UpdateRun.Stopped(
                stopped.report()
                        .withNote("NOTHING WAS SAVED. " + String.join(", ", notStopped)
                                + " could not be stopped, and a snapshot of a running server is one"
                                + " that fails when somebody tries to restore it. Every service that"
                                + " did stop has been started again."),
                stopped.services(),
                runtime));
        return Outcome.failed(UpdateReports.toJson(
                run.verify(back, stopped.services(), UpdateRun.Waiting.real()).withStage(UpdateReport.Stage.FAILED)));
    }

    /**
     * Stops the network, saves the volumes and starts it again.
     *
     * The same choreography an update runs, with nothing installed in between.
     */
    static Outcome runUnderLock(
            final Runner runner,
            final UpdateRequest request,
            final UpdateRun run,
            final RuntimeResult runtime,
            final List<String> volumes,
            final Consumer<UpdateReport> progress) {
        UpdateReport planned = prepareReport(runner, progress);

        // A BACKUP RUNS THE SAME CHOREOGRAPHY AS AN UPDATE: stopping for a snapshot throws people out just as hard.
        final Choreography choreography = new Choreography(runner.containers, runner.occupancy(), runner.waiting);
        final Choreography.Window window = choreography.open(Runner.movingServices(planned));
        if (!window.opened()) {
            return Outcome.failed(UpdateReports.toJson(planned.withStage(UpdateReport.Stage.FAILED)
                    .withNote("NOTHING WAS STOPPED AND NOTHING WAS SAVED. " + window.refusal()
                            + ". The database dump above was taken with everything running and is"
                            + " real; the volumes were not touched.")));
        }
        if (!window.isEmpty()) {
            planned = planned.withNote(String.join(", ", window.standbys())
                    + " started and healthy, so this backup has somewhere to put the players.");
            progress.accept(planned);
        }
        try {
            if (!runner.countDown(request.id(), planned, progress)) {
                return Runner.cancelled();
            }

            // Wait for them to be gone, then stop anyway after the cap - see Choreography.
            final String stillOn = choreography.waitUntilEmpty(Runner.movingServices(planned));
            if (stillOn != null) {
                planned = planned.withNote(stillOn);
                progress.accept(planned);
            }

            final UpdateRun.Stopped stopped = run.stop(planned, runtime);

            final Outcome refused = refuseIfNotStopped(planned, stopped, run, runtime);
            if (refused != null) {
                return refused;
            }

            final UpdateReport saved = run.save(stopped.report(), volumes);
            // Deliberately while the servers are still down: quick, and it frees disk before the next run.
            final UpdateReport swept = pruneAfterBackup(runner, saved);

            final UpdateReport started = run.start(new UpdateRun.Stopped(swept, stopped.services(), runtime));
            final UpdateReport verified = run.verify(started, stopped.services(), UpdateRun.Waiting.real());

            final UpdateReport told = Runner.noteStandbys(verified, choreography.close());
            final UpdateReport finished = Runner.settle(
                    told,
                    run.unverifiedStops(),
                    "the archives were taken - they were kept, and each one has a .unverified file"
                            + " beside it saying so, which `deploy/restore.sh --list` prints",
                    false,
                    Runner.Doubt.FAILS_THE_RUN);
            return finished.stage() == UpdateReport.Stage.FAILED
                    ? Outcome.failed(UpdateReports.toJson(finished))
                    : Outcome.done(UpdateReports.toJson(finished));
        } finally {
            choreography.close();
        }
    }
}
