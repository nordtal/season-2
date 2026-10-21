package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.util.List;
import java.util.function.UnaryOperator;

/** Puts one archive back into its volume or the database while everything that uses it is down. */
final class Restores {

    private Restores() {}

    /**
     * Backs up what the archive replaces, then puts the archive back.
     *
     * A volume archive stops the services that mount the volume; a dump stops every service on the database.
     */
    static Kinds.Planned restore(final Runner runner, final UpdateRequest request, final Steps record) {
        final StewardRequest asked = runner.directory.requestOf(request.id()).orElse(null);
        if (!(asked instanceof StewardRequest.Restore restore)) {
            record.ended(RunStep.Kind.SCOPE, null, RunStep.Outcome.FAILED, RunStep.Reason.UNNAMED);
            return Kinds.failed(List.of(Note.failed(Step.SCOPE, TEXTS.report().restoreUnnamed())));
        }
        final String archive = restore.archive();
        final String series = runner.backups.seriesOf(archive).orElse(null);
        if (series == null) {
            record.ended(RunStep.Kind.SCOPE, archive, RunStep.Outcome.FAILED, RunStep.Reason.UNKNOWN);
            return Kinds.failed(List.of(Note.failed(Step.SCOPE, TEXTS.report().restoreUnknown(archive))));
        }
        return Snapshots.DATABASE.equals(series)
                ? restoreDatabase(runner, request, archive, record)
                : restoreVolume(runner, request, archive, series, record);
    }

    private static Kinds.Planned restoreVolume(
            final Runner runner,
            final UpdateRequest request,
            final String archive,
            final String volume,
            final Steps record) {
        final AgentWire.Topology topology = runner.containers.topology();
        if (!topology.backupVolumes().contains(volume)) {
            record.ended(RunStep.Kind.SCOPE, volume, RunStep.Outcome.FAILED, RunStep.Reason.NOT_A_VOLUME);
            return Kinds.failed(List.of(Note.failed(Step.SCOPE, TEXTS.report().restoreNotAVolume(archive))
                    .on(volume)));
        }
        final Kinds.Planned noRoom =
                Kinds.refusedWithoutRoom(runner, UpdateReport.at(UpdateReport.Stage.STOPPING), List.of(volume), record);
        if (noRoom != null) {
            return noRoom;
        }
        final List<String> users = Kinds.running(runner, topology.usersOf(volume));
        final Run.Payload put = (steps, stopped) -> {
            // A restore of the wrong archive is undone by a restore of this copy.
            final UpdateReport saved = steps.save(stopped.report(), List.of(volume));
            if (saved.line(volume).state() != UpdateReport.State.SAVED) {
                record.ended(RunStep.Kind.RESTORE, archive, RunStep.Outcome.SKIPPED, RunStep.Reason.NOT_SAVED);
                throw new Run.Abort(
                        saved.withNote(Note.failed(Step.BACKUP, TEXTS.report().restoreUnsaved())
                                .on(volume)));
            }
            final Steps.Step restoring = record.begin(RunStep.Kind.RESTORE, archive);
            final Snapshots.Restored restored = runner.backups.restore(archive);
            if (restored.result().ok() || !restored.touched()) {
                restoring.ended(restored.result());
                return putBack(saved, restored.result(), archive);
            }
            restoring.failed(RunStep.Reason.LEFT_DOWN);
            // Started on a half-emptied volume, a server writes defaults over the gaps or makes a new world.
            final List<String> mounting = topology.usersOf(volume);
            for (final String service : mounting) {
                final Steps.Step holding = record.begin(RunStep.Kind.HOLD, service);
                runner.directory.hold(service, request.actor(), request.id());
                holding.done();
            }
            final UpdateReport.Facts savedInto = saved.line(volume).facts();
            final String archived = savedInto == null ? null : savedInto.archive();
            final String backup = archived == null ? volume : archived;
            final Run.Done failed = putBack(
                    saved,
                    restored.result(),
                    archive,
                    Note.failed(Step.INSTALL, TEXTS.report().restoreLeftDown(mounting, backup))
                            .on(volume));
            return new Run.Done(failed.report(), true, UnaryOperator.identity(), mounting);
        };
        return Kinds.Planned.plan(Run.Plan.of(
                        stopping(users, archive),
                        put,
                        UpdateReport.Undertaking.RESTORE_VOLUME,
                        true,
                        Runner.Doubt.FAILS_THE_RUN)
                .announced(users.stream().anyMatch(runner::isMinecraft)));
    }

    private static Kinds.Planned restoreDatabase(
            final Runner runner, final UpdateRequest request, final String dump, final Steps record) {
        // The dump runs before anything stops, as in every backup.
        final SnapshotResult dumped = Kinds.dump(runner, record);
        if (!dumped.ok()) {
            return Kinds.failed(List.of(
                    Note.failed(Step.BACKUP, TEXTS.report().restoreDatabaseUnsaved(String.valueOf(dumped.message())))
                            .on(Snapshots.DATABASE)));
        }
        final List<String> users = Kinds.running(runner, runner.topology().renewed(AgentWire.Renewal.RUN));
        final UpdateReport planned = stopping(users, dump).with(UpdateRun.saved(Snapshots.DATABASE, dumped, null));
        final Run.Payload replace = (steps, stopped) -> {
            // The dump has this run's row as it was then, or not at all; it is carried across and put back.
            final String row = runner.directory.carry(request.id()).orElse(null);
            // Ended after the row is back, since the dump holds no step of this run.
            final SnapshotResult restored = runner.backups.restoreDatabase(dump);
            if (restored.ok() && row != null) {
                runner.directory.putBack(row, TEXTS.report().restoredOver(dump));
            }
            record.begin(RunStep.Kind.RESTORE, dump).ended(restored);
            return putBack(stopped.report(), restored, dump);
        };
        return Kinds.Planned.plan(Run.Plan.of(
                planned, replace, UpdateReport.Undertaking.RESTORE_DATABASE, true, Runner.Doubt.FAILS_THE_RUN));
    }

    private static Run.Done putBack(final UpdateReport report, final SnapshotResult result, final String archive) {
        return putBack(
                report,
                result,
                archive,
                Note.failed(Step.INSTALL, TEXTS.report().restoreFailed()).on(archive));
    }

    private static Run.Done putBack(
            final UpdateReport report, final SnapshotResult result, final String archive, final Note failure) {
        final UpdateReport.ServiceLine line = new UpdateReport.ServiceLine(
                archive,
                result.ok() ? UpdateReport.State.RESTORED : UpdateReport.State.FAILED,
                List.of(UpdateReport.Change.told(
                        "restore",
                        result.ok()
                                ? TEXTS.report()
                                        .restored(ByteSize.of(result.bytes()).message())
                                : TEXTS.report().notRestored())),
                result.ok()
                        ? null
                        : UpdateReport.Facts.refused(UpdateReport.Act.RESTORE, String.valueOf(result.message())));
        return new Run.Done(result.ok() ? report.with(line) : report.with(line).withNote(failure), !result.ok());
    }

    private static UpdateReport stopping(final List<String> services, final String archive) {
        UpdateReport planned = UpdateReport.at(UpdateReport.Stage.STOPPING);
        for (final String service : services) {
            planned = planned.with(new UpdateReport.ServiceLine(
                    service,
                    UpdateReport.State.PLANNED,
                    List.of(UpdateReport.Change.told("restore", TEXTS.report().stoppedForRestore(archive))),
                    null));
        }
        return planned;
    }
}
