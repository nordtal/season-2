package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The database dump, absorbed from the {@code postgres-backup} sidecar (§9a).
 *
 * <h2>Why pg_dump and not a snapshot of the data directory</h2>
 * Taken from the sidecar's own reasoning, which is sound and is not re-derived here: tarring a live
 * {@code PGDATA} produces a torn copy that raises no error at backup time and is a broken cluster at
 * restore time - months later, on the day it is needed. Stopping PostgreSQL for the length of a tar
 * instead is a nightly outage of every process in the stack. {@code pg_dump} has neither problem: it
 * takes an MVCC snapshot, so the dump is consistent as of the moment it started, and nothing stops.
 *
 * <h2>Why it runs inside the postgres container</h2>
 * <b>A pg_dump older than the server it dumps is refused outright.</b> The sidecar solved that by
 * being built FROM the same postgres image; this solves it by running the binary that is already
 * in that image, which cannot be the wrong version by construction. It also keeps a postgres client
 * - and a version to keep in step - out of steward-worker's own image.
 *
 * <p>The file is therefore written on the postgres container's side, into a directory both
 * containers mount. Nothing large travels through the socket: the exec carries the command and the
 * exit code, not the dump.</p>
 *
 * <h2>Partial, then verified, then named</h2>
 * The same three steps the sidecar used, for the same reason. A half-written file that looks like
 * every other dump in the directory is worse than no file at all: it is the one the retention sweep
 * keeps and the one a restore picks.
 */
public final class DatabaseDump {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDump.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The name a dump is saved under, so the report and the interface can speak of one thing. */
    public static final String NAME = "database";

    /**
     * {@code nordtal-<stamp>.dump}, in two halves so that the sweep can build a pattern from the
     * same strings this class writes.
     *
     * <p>{@link TarSnapshots#prune} matched neither of these until 2026-09-15, and nothing noticed
     * because no dump had ever been written. One per night on the disk that holds the only copy of
     * the world is not a file to leave uncounted.</p>
     */
    static final String PREFIX = "nordtal-";

    /** @see #PREFIX */
    static final String SUFFIX = ".dump";

    private final Docker docker;
    private final String project;
    private final String service;
    private final String directory;
    private final Clock clock;

    /**
     * @param service   the compose service running PostgreSQL, normally {@code postgres}
     * @param directory where the dump is written, as the POSTGRES container sees it
     */
    public DatabaseDump(
            final Docker docker,
            final String project,
            final String service,
            final String directory,
            final Clock clock) {
        this.docker = docker;
        this.project = project;
        this.service = service;
        this.directory = directory;
        this.clock = clock;
    }

    public SnapshotResult save() {
        final Instant started = clock.instant();
        final String containerId;
        try {
            containerId = containerOf().orElse(null);
        } catch (DockerException e) {
            return SnapshotResult.failed(
                    NAME,
                    took(started),
                    "could not ask docker which container runs " + service + ": " + e.getMessage());
        }
        if (containerId == null) {
            return SnapshotResult.failed(
                    NAME,
                    took(started),
                    "no running container for " + service + ", so there is nothing to dump. The "
                            + "database being down is the reason, not a detail of the backup.");
        }

        final String base = PREFIX + STAMP.format(started) + SUFFIX;
        final String finalPath = directory + "/" + base;
        final String partialPath = finalPath + ".partial";

        // THE DIRECTORY, AS ROOT, BEFORE THE DUMP.
        //
        // pg_dump runs as `postgres` (see `run` below) while the backup volume's root belongs to
        // root:root 0755 - so the dump could not be written at all. Measured on the dev stack
        // 2026-09-14: eight volume archives beside one database line reading "Permission denied",
        // and not a single .dump in the directory since this replaced the sidecar. Handing the
        // directory to `postgres` here is idempotent, survives a recreated volume, and leaves root
        // writing into it as before - the volume archives and the retention sweep are unaffected.
        final Docker.ExecResult prepared = docker.exec(
                containerId,
                List.of("sh", "-c", "mkdir -p " + quote(directory) + " && chown postgres " + quote(directory)),
                null);
        if (!prepared.ok()) {
            return SnapshotResult.failed(
                    NAME,
                    took(started),
                    "could not hand " + directory + " to the postgres user, so pg_dump would not"
                            + " have been able to write there: " + firstLine(prepared.output()));
        }

        // One `sh -c` per step rather than one long chain, so a failure names the step it failed
        // at. The values come out of the container's own environment: they are already there, and
        // repeating them here would be a second copy of a password.
        final Docker.ExecResult dumped = run(
                containerId,
                "pg_dump --format=custom --compress=9 --file=" + quote(partialPath)
                        + " -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"");
        if (!dumped.ok()) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    NAME, took(started), "pg_dump exited " + dumped.exitCode() + ": " + firstLine(dumped.output()));
        }

        // Cheap integrity check, and the sidecar's: read the archive's own table of contents back.
        // It does not prove the dump restores - only a real restore drill can - but it catches a
        // truncated file while there is still something to be done about it.
        final Docker.ExecResult listed = run(containerId, "pg_restore --list " + quote(partialPath) + " > /dev/null");
        if (!listed.ok()) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    NAME,
                    took(started),
                    "the dump is not a readable archive (pg_restore --list exited " + listed.exitCode()
                            + ") - discarded rather than kept");
        }

        final long bytes = sizeOf(containerId, partialPath);
        if (bytes <= 0) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    NAME, took(started), "pg_dump wrote an empty file and reported success. Nothing was saved.");
        }

        final Docker.ExecResult renamed = run(containerId, "mv " + quote(partialPath) + " " + quote(finalPath));
        if (!renamed.ok()) {
            return SnapshotResult.failed(
                    NAME,
                    took(started),
                    "the dump was written and verified but could not be named: " + firstLine(renamed.output()));
        }

        log.info("database dumped to {} ({})", finalPath, SnapshotResult.human(bytes));
        return SnapshotResult.saved(NAME, bytes, took(started), finalPath);
    }

    private Optional<String> containerOf() {
        return docker.containers(project).stream()
                .filter(container -> service.equals(container.service()) && container.isRunning())
                .map(Docker.Container::id)
                .findFirst();
    }

    private Docker.ExecResult run(final String containerId, final String script) {
        // As `postgres`, because the official image trusts the local socket for that user and for
        // nobody else - and because a dump written as root is a dump the database user cannot
        // overwrite next time.
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

    /** Single quotes, with the one escape that matters. These are our paths, not user input. */
    private static String quote(final String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static String firstLine(final String output) {
        final String stripped = output.strip();
        final int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline);
    }
}
