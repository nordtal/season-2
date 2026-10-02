package eu.nordtal.s2.stewardagent.backup;

import eu.nordtal.s2.internalapi.agent.SnapshotResult;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerException;
import eu.nordtal.s2.stewardagent.run.Snapshots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The database dump, by {@code pg_dump} inside the postgres container, into a directory both mount.
 *
 * Written as a partial, verified, then renamed, so a torn file never looks like a dump.
 */
public final class DatabaseDump {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDump.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The database compose.yml creates, which a dump is named after and a restore is confirmed by. */
    static final String DATABASE_NAME = "nordtal";

    /** {@code nordtal-<stamp>.dump}, in two halves, so {@link TarSnapshots#prune} can match what this writes. */
    static final String PREFIX = DATABASE_NAME + "-";

    /** The second half of {@link #PREFIX}. */
    static final String SUFFIX = ".dump";

    private final Docker docker;
    private final String project;
    private final String directory;
    private final Clock clock;

    /**
     * Dumps into one directory.
     *
     * @param directory where the dump is written, as the postgres container sees it
     */
    public DatabaseDump(final Docker docker, final String project, final String directory, final Clock clock) {
        this.docker = docker;
        this.project = project;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * Dumps the database one service runs, logged in as {@code role}.
     *
     * @param service the compose service running PostgreSQL, normally {@code postgres}
     * @param role the backup role, which reads everything and changes nothing
     */
    public SnapshotResult save(final String service, final String role) {
        final Instant started = clock.instant();
        final String containerId;
        try {
            containerId = docker.running(project, service).orElse(null);
        } catch (DockerException e) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "could not ask docker which container runs " + service + ": " + e.getMessage());
        }
        if (containerId == null) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "no running container for " + service + ", so there is nothing to dump. The "
                            + "database being down is the reason, not a detail of the backup.");
        }

        final String base = PREFIX + STAMP.format(started) + SUFFIX;
        final String finalPath = directory + "/" + base;
        final String partialPath = finalPath + ".partial";

        final SnapshotResult failure = prepareDirectory(containerId, started);
        if (failure != null) {
            return failure;
        }
        return dumpAndVerify(containerId, role, finalPath, partialPath, started);
    }

    /**
     * Replaces the database with a dump in the same directory, in one transaction, so a failure changes nothing.
     *
     * The schema is dropped first, since a table a later migration added is in no dump and would stay behind.
     */
    public SnapshotResult restore(final String service, final String dump) {
        final Instant started = clock.instant();
        final String containerId;
        try {
            containerId = docker.running(project, service).orElse(null);
        } catch (DockerException e) {
            return SnapshotResult.failed(Snapshots.DATABASE, took(started), "could not ask docker: " + e.getMessage());
        }
        if (containerId == null) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE, took(started), "no running container for " + service + " to restore into");
        }
        final String path = directory + "/" + dump;
        final Docker.ExecResult listed = run(containerId, "pg_restore --list " + quote(path) + " > /dev/null");
        if (!listed.ok()) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    dump + " is not a readable dump, so the database was not touched: " + firstLine(listed.output()));
        }
        // The owner restores, over the socket the image trusts; a lock held elsewhere fails it instead of hanging.
        final Docker.ExecResult restored = run(
                containerId,
                "set -o pipefail; { printf '%s\\n' 'DROP SCHEMA public CASCADE;'"
                        + " 'CREATE SCHEMA public AUTHORIZATION pg_database_owner;'; pg_restore --file=- "
                        + quote(path)
                        + "; } | PGOPTIONS='-c lock_timeout=60s' psql -X -q -o /dev/null -v ON_ERROR_STOP=1"
                        + " --single-transaction -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" 2>&1");
        if (!restored.ok()) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "the restore was rolled back, so the database is as it was: " + firstLine(restored.output()));
        }
        log.info("database restored from {}", dump);
        return SnapshotResult.saved(Snapshots.DATABASE, Math.max(0, sizeOf(containerId, path)), took(started), path);
    }

    // As root, before the dump: pg_dump runs as `postgres` and the volume's root belongs to root.
    private @Nullable SnapshotResult prepareDirectory(final String containerId, final Instant started) {
        final Docker.ExecResult prepared = docker.exec(
                containerId,
                List.of("sh", "-c", "mkdir -p " + quote(directory) + " && chown postgres " + quote(directory)),
                null);
        if (!prepared.ok()) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "could not hand " + directory + " to the postgres user, so pg_dump would not"
                            + " have been able to write there: " + firstLine(prepared.output()));
        }
        return null;
    }

    private SnapshotResult dumpAndVerify(
            final String containerId,
            final String role,
            final String finalPath,
            final String partialPath,
            final Instant started) {
        // One `sh -c` per step, so a failure names the step; the values come from the container's own environment.
        final Docker.ExecResult dumped = run(
                containerId,
                "pg_dump --format=custom --compress=9 --file=" + quote(partialPath)
                        // The backup role reads everything and changes nothing; the socket needs no password.
                        + " -U " + quote(role) + " -d \"$POSTGRES_DB\"");
        if (!dumped.ok()) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "pg_dump exited " + dumped.exitCode() + ": " + firstLine(dumped.output()));
        }

        // Reading the archive's table of contents back catches a truncated file early.
        final Docker.ExecResult listed = run(containerId, "pg_restore --list " + quote(partialPath) + " > /dev/null");
        if (!listed.ok()) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "the dump is not a readable archive (pg_restore --list exited " + listed.exitCode()
                            + ") - discarded rather than kept");
        }

        final long bytes = sizeOf(containerId, partialPath);
        if (bytes <= 0) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "pg_dump wrote an empty file and reported success. Nothing was saved.");
        }

        final Docker.ExecResult renamed = run(containerId, "mv " + quote(partialPath) + " " + quote(finalPath));
        if (!renamed.ok()) {
            return SnapshotResult.failed(
                    Snapshots.DATABASE,
                    took(started),
                    "the dump was written and verified but could not be named: " + firstLine(renamed.output()));
        }

        log.info("database dumped to {} ({})", finalPath, SnapshotResult.human(bytes));
        return SnapshotResult.saved(Snapshots.DATABASE, bytes, took(started), finalPath);
    }

    // As `postgres`: the official image trusts the local socket only for that user.
    private Docker.ExecResult run(final String containerId, final String script) {
        return docker.exec(containerId, List.of("sh", "-c", script), "postgres");
    }

    private long sizeOf(final String containerId, final String path) {
        final Docker.ExecResult stat = run(containerId, "stat -c %s " + quote(path));
        if (!stat.ok()) {
            return -1;
        }
        try {
            return Long.parseLong(stat.output().strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void remove(final String containerId, final String path) {
        final Docker.ExecResult removed = run(containerId, "rm -f " + quote(path));
        if (!removed.ok()) {
            log.warn("could not remove the partial dump {}: {}", path, firstLine(removed.output()));
        }
    }

    private Duration took(final Instant started) {
        return Duration.between(started, clock.instant());
    }

    /** Single quotes, with the one escape that matters; these are our paths, not user input. */
    private static String quote(final String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static String firstLine(final String output) {
        final String stripped = output.strip();
        final int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline);
    }
}
