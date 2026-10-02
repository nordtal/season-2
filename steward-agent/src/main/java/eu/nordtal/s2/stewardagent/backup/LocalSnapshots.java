package eu.nordtal.s2.stewardagent.backup;

import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.internalapi.agent.Retention;
import eu.nordtal.s2.internalapi.agent.SnapshotResult;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.run.Snapshots;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What a run saves with: the volumes tarred from their mounts, the database dumped inside its own container.
 *
 * The database service and the patience are read from the runs group at every call, so a change counts at once.
 */
public final class LocalSnapshots implements Snapshots {

    /** Below this a tar of a world could not finish, so a shorter patience is a mistake, not a choice. */
    private static final Duration LEAST_PATIENCE = Duration.ofMinutes(1);

    private final TarSnapshots tars;
    private final DatabaseDump dump;
    private final Supplier<RunSpec> config;
    private final Runnable afterDatabaseRestore;

    /**
     * Saves into {@code backups}, which the database's container mounts at the same path.
     *
     * @param sourcesRoot where the volumes being saved are mounted, one directory per volume name
     * @param afterDatabaseRestore what a replaced database needs before anything uses it: fresh connections, the schema
     */
    public LocalSnapshots(
            final Docker docker,
            final String project,
            final Path sourcesRoot,
            final Path backups,
            final Clock clock,
            final Supplier<RunSpec> config,
            final Runnable afterDatabaseRestore) {
        this.tars = new TarSnapshots(sourcesRoot, backups, clock);
        this.dump = new DatabaseDump(docker, project, backups.toString(), clock);
        this.config = config;
        this.afterDatabaseRestore = afterDatabaseRestore;
    }

    @Override
    public SnapshotResult saveDatabase() {
        final String service = config.get().backup().databaseService();
        if (service.isBlank()) {
            return SnapshotResult.failed(
                    DATABASE,
                    Duration.ZERO,
                    "backup.database-service is empty, so no database dump was taken. Nothing is wrong with the"
                            + " database; nothing was saved of it either.");
        }
        return dump.save(service, DatabaseRole.BACKUP.roleName());
    }

    @Override
    public SnapshotResult save(final String volume) {
        return tars.save(volume, patience());
    }

    @Override
    public @Nullable String markUnverified(final String archive, final String why) {
        return tars.markUnverified(archive, why);
    }

    @Override
    public List<String> prune(final Retention policy) {
        return tars.prune(policy);
    }

    @Override
    public java.util.Optional<String> seriesOf(final String archive) {
        return tars.seriesOf(archive);
    }

    @Override
    public SnapshotResult restore(final String archive) {
        return tars.restore(archive, patience());
    }

    @Override
    public SnapshotResult restoreDatabase(final String dump) {
        final SnapshotResult restored = this.dump.restore(config.get().backup().databaseService(), dump);
        if (!restored.ok()) {
            return restored;
        }
        try {
            afterDatabaseRestore.run();
            return restored;
        } catch (final RuntimeException failure) {
            return SnapshotResult.failed(
                    DATABASE,
                    restored.took(),
                    "the dump was restored, and bringing its schema up to this release failed: "
                            + failure.getMessage());
        }
    }

    private Duration patience() {
        final Duration patience = Duration.ofMinutes(config.get().backup().patienceMinutes());
        return patience.compareTo(LEAST_PATIENCE) < 0 ? LEAST_PATIENCE : patience;
    }
}
