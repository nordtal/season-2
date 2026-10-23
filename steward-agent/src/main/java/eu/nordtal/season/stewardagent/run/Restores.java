package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Puts one archive back into its volume or the database while everything that uses it is down. */
@Slf4j
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
            return Kinds.failed();
        }
        final String archive = restore.archive();
        final String series = runner.backups.seriesOf(archive).orElse(null);
        if (series == null) {
            record.ended(RunStep.Kind.SCOPE, archive, RunStep.Outcome.FAILED, RunStep.Reason.UNKNOWN);
            return Kinds.failed();
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
            return Kinds.failed();
        }
        final Kinds.Planned noRoom = Kinds.refusedWithoutRoom(runner, List.of(volume), record);
        if (noRoom != null) {
            return noRoom;
        }
        final List<String> users = Kinds.running(runner, topology.usersOf(volume));
        final Run.Payload put = (steps, stopped) -> {
            // A restore of the wrong archive is undone by a restore of this copy.
            final SnapshotResult saved = steps.save(List.of(volume)).getFirst();
            if (!saved.ok()) {
                record.ended(RunStep.Kind.RESTORE, archive, RunStep.Outcome.SKIPPED, RunStep.Reason.NOT_SAVED);
                throw new Run.Abort();
            }
            final Steps.Step restoring = record.begin(RunStep.Kind.RESTORE, archive);
            final Snapshots.Restored restored = runner.backups.restore(archive);
            if (restored.result().ok() || !restored.touched()) {
                restoring.ended(restored.result());
                return new Run.Done(!restored.result().ok());
            }
            restoring.failed(RunStep.Reason.LEFT_DOWN);
            // Started on a half-emptied volume, a server writes defaults over the gaps or makes a new world.
            final List<String> mounting = topology.usersOf(volume);
            log.warn(
                    "The restore of {} left {} half emptied; {} stay down, and {} holds what it replaced",
                    archive,
                    volume,
                    mounting,
                    saved.file());
            for (final String service : mounting) {
                final Steps.Step holding = record.begin(RunStep.Kind.HOLD, service);
                runner.directory.hold(service, request.actor(), request.id());
                holding.done();
            }
            return new Run.Done(true, () -> {}, mounting);
        };
        return Kinds.Planned.plan(Run.Plan.of(users, put, true, Runner.Doubt.FAILS_THE_RUN)
                .announced(users.stream().anyMatch(runner::isMinecraft)));
    }

    private static Kinds.Planned restoreDatabase(
            final Runner runner, final UpdateRequest request, final String dump, final Steps record) {
        // The dump runs before anything stops, as in every backup.
        final SnapshotResult dumped = Kinds.dump(runner, record);
        if (!dumped.ok()) {
            return Kinds.failed();
        }
        final List<String> users = Kinds.running(runner, runner.topology().renewed(AgentWire.Renewal.RUN));
        final Run.Payload replace = (steps, stopped) -> {
            // The dump has this run's row as it was then, or not at all; it is carried across and put back.
            final String row = runner.directory.carry(request.id()).orElse(null);
            // Ended after the row is back, since the dump holds no step of this run.
            final SnapshotResult restored = runner.backups.restoreDatabase(dump);
            if (restored.ok() && row != null) {
                runner.directory.putBack(row);
            }
            record.begin(RunStep.Kind.RESTORE, dump).ended(restored);
            return new Run.Done(!restored.ok());
        };
        return Kinds.Planned.plan(Run.Plan.of(users, replace, true, Runner.Doubt.FAILS_THE_RUN));
    }
}
