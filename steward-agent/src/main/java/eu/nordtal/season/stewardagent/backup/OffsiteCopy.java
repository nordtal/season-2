package eu.nordtal.season.stewardagent.backup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.stewardagent.run.Snapshots;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the newest finished archive of every series into a restic repository off this host, encrypted there.
 *
 * A copied archive is marked by an empty file of its name under {@link #MARKS}, which the archive list reads.
 */
public final class OffsiteCopy {

    private static final Logger log = LoggerFactory.getLogger(OffsiteCopy.class);

    /** The directory inside the backups that holds one mark per archive the repository has. */
    public static final String MARKS = "offsite";

    /** A first copy of every world at a slow uplink; a run holds the inbox for as long as this. */
    private static final Duration PATIENCE = Duration.ofHours(2);

    /** restic's exit status for a repository that does not exist yet. */
    private static final int NO_REPOSITORY = 10;

    /**
     * Where the copies go and how the agent gets in.
     *
     * @param repository restic's repository, for a Storage Box {@code sftp://user@host:23/directory}
     * @param password the repository's key, which encrypts every copy; only restic's environment carries it
     * @param sshKey the private key the box accepts
     * @param knownHosts the box's host keys, so a changed key is refused rather than trusted
     * @param tag the host and tag the snapshots carry, so one repository can hold more than one deployment
     */
    public record Target(String repository, String password, Path sshKey, Path knownHosts, String tag) {

        @Override
        public String toString() {
            return "Target[" + repository + "]";
        }
    }

    private final Path backups;
    private final Supplier<Optional<Target>> target;

    /** @param target read at every copy, empty while no repository is configured */
    public OffsiteCopy(final Path backups, final Supplier<Optional<Target>> target) {
        this.backups = backups;
        this.target = target;
    }

    /**
     * Copies the newest finished archive of every series, then forgets and prunes what the policy no longer keeps.
     *
     * @return empty when no repository is configured
     */
    public Optional<SnapshotResult> copy(final Retention policy) {
        final Optional<Target> configured = target.get();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        final long startedAt = System.nanoTime();
        final List<Path> newest = newestOfEverySeries();
        if (newest.isEmpty()) {
            return Optional.of(SnapshotResult.failed(Snapshots.OFFSITE, since(startedAt), "no finished archive"));
        }
        try {
            final Restic restic = new Restic(configured.get());
            final Optional<String> unopened = restic.open();
            if (unopened.isPresent()) {
                return failed(startedAt, unopened.get());
            }
            final List<String> backup = new ArrayList<>(List.of(
                    "backup",
                    "--host",
                    configured.get().tag(),
                    "--tag",
                    configured.get().tag(),
                    "--no-scan",
                    "--json"));
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
            return Optional.of(SnapshotResult.saved(
                    Snapshots.OFFSITE,
                    summary.get().added(),
                    since(startedAt),
                    summary.get().snapshot()));
        } catch (final IOException failure) {
            return failed(startedAt, failure.toString());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(startedAt, "interrupted");
        }
    }

    /** The snapshot a backup made and the bytes it added to the repository, packed where restic says so. */
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

    /** Whether the repository holds this archive, by its mark. */
    static boolean isCopied(final Path backups, final String archive) {
        return Files.isRegularFile(backups.resolve(MARKS).resolve(archive));
    }

    /** The newest finished archive of every series in the backups, oldest series first. */
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
            log.warn("could not list {}", backups, unreadable);
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

    private static Optional<SnapshotResult> failed(final long startedAt, final String why) {
        log.warn("offsite copy: {}", why);
        return Optional.of(SnapshotResult.failed(Snapshots.OFFSITE, since(startedAt), why));
    }

    private static Duration since(final long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    /** One restic command against the target, with the password in its environment and never on its command line. */
    private record Restic(Target target) {

        /** Creates the repository on the first copy; the reason when it neither answers nor could be created. */
        Optional<String> open() throws IOException, InterruptedException {
            final Pipeline.Result config = run("cat", "config");
            if (!config.exitCodes().equals(List.of(NO_REPOSITORY))) {
                return config.failed()
                        ? Optional.of("the repository did not answer: " + config.describe())
                        : Optional.empty();
            }
            log.info("creating the offsite repository {}", target.repository());
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
                    target.tag(),
                    "--tag",
                    target.tag(),
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

        /** The same, with what restic prints written to {@code stdout}. */
        Pipeline.Result run(final @Nullable Path stdout, final String... arguments)
                throws IOException, InterruptedException {
            final List<String> command = new ArrayList<>(List.of(
                    "restic",
                    "--retry-lock",
                    "10m",
                    "-o",
                    "sftp.args=-i " + target.sshKey() + " -o UserKnownHostsFile=" + target.knownHosts()
                            + " -o StrictHostKeyChecking=yes -o BatchMode=yes"));
            command.addAll(List.of(arguments));
            return Pipeline.run(
                    PATIENCE,
                    stdout,
                    Map.of("RESTIC_REPOSITORY", target.repository(), "RESTIC_PASSWORD", target.password()),
                    List.of(command));
        }
    }
}
