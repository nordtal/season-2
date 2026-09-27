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

    /** The name a dump is saved under, so the report and the interface can speak of one thing. */
    public static final String NAME = "database";

    /** {@code nordtal-<stamp>.dump}, in two halves, so {@link TarSnapshots#prune} can match what this writes. */
    static final String PREFIX = "nordtal-";

    /** The second half of {@link #PREFIX}. */
    static final String SUFFIX = ".dump";

    private final Docker docker;
    private final String project;
    private final String service;
    private final String directory;
    private final Clock clock;

    /**
     * Dumps through one service.
     *
     * @param service the compose service running PostgreSQL, normally {@code postgres}
     * @param directory where the dump is written, as the postgres container sees it
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

        final SnapshotResult failure = prepareDirectory(containerId, started);
        if (failure != null) {
            return failure;
        }
        return dumpAndVerify(containerId, finalPath, partialPath, started);
    }

    // As root, before the dump: pg_dump runs as `postgres` and the volume's root belongs to root.
    private @Nullable SnapshotResult prepareDirectory(final String containerId, final Instant started) {
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
        return null;
    }

    private SnapshotResult dumpAndVerify(
            final String containerId, final String finalPath, final String partialPath, final Instant started) {
        // One `sh -c` per step, so a failure names the step; the values come from the container's own environment.
        final Docker.ExecResult dumped = run(
                containerId,
                "pg_dump --format=custom --compress=9 --file=" + quote(partialPath)
                        + " -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"");
        if (!dumped.ok()) {
            remove(containerId, partialPath);
            return SnapshotResult.failed(
                    NAME, took(started), "pg_dump exited " + dumped.exitCode() + ": " + firstLine(dumped.output()));
        }

        // Reading the archive's table of contents back catches a truncated file early.
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
