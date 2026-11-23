package eu.nordtal.season.stewardagent.backup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import eu.nordtal.season.stewardagent.run.Snapshots;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the newest finished archive of every series into an encrypted restic repository on Google Drive.
 * Steward names the account and the repository at every copy, and hears how the copy went. An empty file
 * of the archive's name under {@link #MARKS} marks a copied archive, and the archive list reads it.
 */
public final class OffsiteCopy {

    private static final Logger log = LoggerFactory.getLogger(OffsiteCopy.class);

    /** The directory inside the backups that holds one mark per archive the repository has. */
    public static final String MARKS = "offsite";

    /** The rclone remote that the repository lives on. */
    static final String REMOTE = "gdrive";

    /**
     * The time for a first copy of every world over a slow uplink. A run holds the inbox that long.
     */
    private static final Duration PATIENCE = Duration.ofHours(2);

    /** restic's exit status for a repository that does not exist yet. */
    private static final int NO_REPOSITORY = 10;

    /** Where the copies go, as steward names it. */
    public interface Destination {

        /** No destination, so nothing is copied. */
        Destination NONE = new Destination() {
            @Override
            public Optional<BackupDestinationWire.Credentials> credentials() {
                return Optional.empty();
            }

            @Override
            public void report(final BackupDestinationWire.Report outcome) {}
        };

        /** Returns the account and repository of the next copy, or nothing while the stack copies nowhere. */
        Optional<BackupDestinationWire.Credentials> credentials() throws IOException;

        /** Tells steward how a copy went. A failed report is logged and never fails the copy. */
        void report(BackupDestinationWire.Report outcome);
    }

    private final Path backups;
    private final Destination destination;
    private final String tag;
    private final Clock clock;

    /**
     * @param tag the host and tag of the snapshots, so one repository can hold several deployments
     */
    public OffsiteCopy(final Path backups, final Destination destination, final String tag, final Clock clock) {
        this.backups = backups;
        this.destination = destination;
        this.tag = tag;
        this.clock = clock;
    }

    /**
     * Copies the newest finished archive of every series, then forgets and prunes what the policy no longer keeps.
     *
     * @return empty when steward names no destination
     */
    public Optional<SnapshotResult> copy(final Retention policy) {
        final long startedAt = System.nanoTime();
        final Optional<BackupDestinationWire.Credentials> credentials;
        try {
            credentials = destination.credentials();
        } catch (final IOException unread) {
            return Optional.of(failed(startedAt, "steward did not name the destination: " + unread.getMessage()));
        }
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        final SnapshotResult result = copyTo(credentials.get(), policy, startedAt);
        destination.report(new BackupDestinationWire.Report(
                credentials.get().accountId(),
                result.ok(),
                result.ok() ? clock.instant() : null,
                result.file(),
                result.ok() ? result.bytes() : null,
                result.message()));
        return Optional.of(result);
    }

    private SnapshotResult copyTo(
            final BackupDestinationWire.Credentials credentials, final Retention policy, final long startedAt) {
        final List<Path> newest = newestOfEverySeries();
        if (newest.isEmpty()) {
            return failed(startedAt, "no finished archive");
        }
        final Path config;
        try {
            config = RcloneConfig.write(credentials);
        } catch (final IOException | IllegalArgumentException unwritten) {
            return failed(startedAt, "the rclone config was not written: " + unwritten.getMessage());
        }
        try {
            final Restic restic = new Restic(credentials, config, tag);
            final Optional<String> unopened = restic.open();
            if (unopened.isPresent()) {
                return failed(startedAt, unopened.get());
            }
            final List<String> backup =
                    new ArrayList<>(List.of("backup", "--host", tag, "--tag", tag, "--no-scan", "--json"));
            newest.forEach(file -> backup.add(file.toString()));
            final Path said = Files.createTempFile("restic-backup-", ".json");
            final Optional<Summary> summary;
            try {
                final Pipeline.Result copied = restic.run(said, backup.toArray(String[]::new));
                if (copied.failed()) {
                    return failed(startedAt, "the copy failed: " + copied.describe());
                }
                summary = summary(Files.readAllLines(said));
            } finally {
                Files.deleteIfExists(said);
            }
            if (summary.isEmpty()) {
                return failed(startedAt, "the copy made no snapshot");
            }
            mark(newest);
            final Pipeline.Result forgot = restic.forget(policy);
            if (forgot.failed()) {
                return failed(startedAt, "the copy is there, and applying the retention failed: " + forgot.describe());
            }
            // The snapshot is where the copy landed, and its bytes what the repository grew by.
            return SnapshotResult.saved(
                    Snapshots.OFFSITE,
                    summary.get().added(),
                    since(startedAt),
                    summary.get().snapshot());
        } catch (final IOException failure) {
            return failed(startedAt, failure.toString());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(startedAt, "interrupted");
        } finally {
            RcloneConfig.delete(config);
        }
    }

    /** The snapshot of a backup and the bytes it added to the repository. */
    record Summary(String snapshot, long added) {}

    /** The summary among the lines of {@code restic backup --json}, empty when it made no snapshot. */
    static Optional<Summary> summary(final List<String> lines) {
        for (final String line : lines.reversed()) {
            if (!line.startsWith("{")) {
                continue;
            }
            final JsonObject message = Json.decode(line, JsonObject.class);
            if ("summary".equals(text(message, "message_type")) && message.has("snapshot_id")) {
                final JsonElement packed = message.get("data_added_packed");
                final long added = (packed == null ? message.get("data_added") : packed).getAsLong();
                return Optional.of(new Summary(message.get("snapshot_id").getAsString(), added));
            }
        }
        return Optional.empty();
    }

    private static @Nullable String text(final JsonObject message, final String field) {
        final JsonElement value = message.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    static boolean isCopied(final Path backups, final String archive) {
        return Files.isRegularFile(backups.resolve(MARKS).resolve(archive));
    }

    /** Returns the newest finished archive of every series, oldest series first. */
    List<Path> newestOfEverySeries() {
        final Map<String, Path> newest = new LinkedHashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry) || !TarSnapshots.isFinishedArchive(name)) {
                    continue;
                }
                TarSnapshots.restoresInto(name)
                        .ifPresent(series -> newest.merge(
                                series,
                                entry,
                                (one, two) -> one.getFileName()
                                                        .toString()
                                                        .compareTo(two.getFileName()
                                                                .toString())
                                                > 0
                                        ? one
                                        : two));
            }
        } catch (final IOException unreadable) {
            log.warn("backups not listed: dir={}", backups, unreadable);
            return List.of();
        }
        return newest.values().stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
    }

    /** Marks what was copied and drops every mark whose archive retention has removed. */
    private void mark(final List<Path> copied) throws IOException {
        final Path marks = Files.createDirectories(backups.resolve(MARKS));
        for (final Path file : copied) {
            final Path mark = marks.resolve(file.getFileName().toString());
            if (!Files.exists(mark)) {
                Files.createFile(mark);
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(marks)) {
            for (final Path mark : entries) {
                if (!Files.exists(backups.resolve(mark.getFileName().toString()))) {
                    Files.deleteIfExists(mark);
                }
            }
        }
    }

    private static SnapshotResult failed(final long startedAt, final String why) {
        log.warn("offsite copy failed: cause={}", why);
        return SnapshotResult.failed(Snapshots.OFFSITE, since(startedAt), why);
    }

    private static Duration since(final long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    /**
     * One restic command against the repository.
     * The password is in the environment, never on the command line. rclone reads the account from the config
     * file that {@code RCLONE_CONFIG} names.
     */
    private record Restic(BackupDestinationWire.Credentials credentials, Path config, String tag) {

        /** Creates the repository on the first copy; the reason when it neither answers nor could be created. */
        Optional<String> open() throws IOException, InterruptedException {
            final Pipeline.Result found = run("cat", "config");
            if (!found.exitCodes().equals(List.of(NO_REPOSITORY))) {
                return found.failed()
                        ? Optional.of("the repository did not answer: " + found.describe())
                        : Optional.empty();
            }
            log.info(
                    "offsite repository creation started: account=\"{}\" folder={}",
                    credentials.email(),
                    credentials.subpath());
            final Pipeline.Result created = run("init");
            return created.failed()
                    ? Optional.of("creating the repository failed: " + created.describe())
                    : Optional.empty();
        }

        /** Forgets and prunes what the policy no longer keeps of this deployment's snapshots. */
        Pipeline.Result forget(final Retention policy) throws IOException, InterruptedException {
            // Every snapshot names other files, so grouping by paths would keep each one forever.
            return run(
                    "forget",
                    "--host",
                    tag,
                    "--tag",
                    tag,
                    "--group-by",
                    "host,tags",
                    "--keep-daily",
                    String.valueOf(policy.daily()),
                    "--keep-weekly",
                    String.valueOf(policy.weekly()),
                    "--keep-monthly",
                    String.valueOf(policy.monthly()),
                    "--prune");
        }

        Pipeline.Result run(final String... arguments) throws IOException, InterruptedException {
            return run(null, arguments);
        }

        Pipeline.Result run(final @Nullable Path stdout, final String... arguments)
                throws IOException, InterruptedException {
            final List<String> command = new ArrayList<>(List.of("restic", "--retry-lock", "10m"));
            command.addAll(List.of(arguments));
            return Pipeline.run(
                    PATIENCE,
                    stdout,
                    Map.of(
                            "RESTIC_REPOSITORY",
                            "rclone:" + REMOTE + ":" + credentials.subpath(),
                            "RESTIC_PASSWORD",
                            credentials.resticPassword(),
                            "RCLONE_CONFIG",
                            config.toString()),
                    List.of(command));
        }
    }
}
