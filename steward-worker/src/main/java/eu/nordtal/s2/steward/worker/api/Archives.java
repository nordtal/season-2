package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.backup.TarSnapshots;
import eu.nordtal.s2.steward.worker.docker.Docker;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The backup list, one archive's download, and the console's log capacity - split out of {@link WorkerApi}. */
final class Archives {

    private static final Logger log = LoggerFactory.getLogger(Archives.class);

    private Archives() {}

    /** Every row of the backup list, newest first. */
    static List<Map<String, Object>> list(final Path backups) {
        final List<Map<String, Object>> all = new ArrayList<>();
        if (!Files.isDirectory(backups)) {
            return all;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (final Path entry : entries) {
                row(entry).ifPresent(all::add);
            }
        } catch (IOException e) {
            log.warn("could not list {}", backups, e);
        }
        all.sort(
                (left, right) -> String.valueOf(right.get("modified")).compareTo(String.valueOf(left.get("modified"))));
        return all;
    }

    /**
     * Streams one archive or dump out of {@code backups}, for the backup detail page.
     *
     * Two checks, not one: {@link TarSnapshots#isFinishedArchive} refuses anything that is not a finished archive
     * name - but that regex's {@code .} matches a {@code /} exactly as readily as any other character, so
     * {@code ../../etc/passwd-20260913T044507Z.tar.zst} matches it too (proven in {@code TarSnapshotsTest}). The
     * second check is the one that actually stops that: resolve the name against {@code backups} and refuse
     * anything whose normalised path has left that directory. Neither check alone is the defence; both together are.
     *
     * Streamed, never buffered: These files are hundreds of megabytes, so the body is an open
     * {@link java.io.InputStream} handed to {@code ctx.result} rather than a byte array read in full first.
     * Javalin's own documentation says {@code ctx.result(InputStream)} writes and closes the stream for the
     * caller; that was not independently re-verified against the Javalin 7.2.3 jar in this session and is worth
     * a second look before this route sees real traffic.
     */
    static void download(final Context ctx, final Path backups, final String name) {
        if (!TarSnapshots.isFinishedArchive(name)) {
            throw new BadRequestResponse("not the name of a finished backup: " + name);
        }
        final Path resolved = backups.resolve(name).normalize();
        final Path parent = resolved.getParent();
        if (!resolved.startsWith(backups.normalize()) || parent == null || !parent.equals(backups.normalize())) {
            // Never reached by a plain filename - the second, independent check the javadoc above promises.
            throw new BadRequestResponse("not the name of a finished backup: " + name);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new NotFoundResponse("no such backup: " + name);
        }
        final long size;
        try {
            size = Files.size(resolved);
        } catch (IOException unreadable) {
            throw new NotFoundResponse("no such backup: " + name);
        }
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + resolved.getFileName() + "\"");
        ctx.header("Content-Length", String.valueOf(size));
        try {
            ctx.result(Files.newInputStream(resolved));
        } catch (IOException gone) {
            throw new NotFoundResponse("no such backup: " + name);
        }
    }

    /** Lines the console can offer: Docker's, then the archive's, up to the highest step. */
    static int capacity(
            final Docker docker, final String project, final LogArchive archive, final String service, final int max) {
        final String containerId = containerOf(docker, project, service).orElse(null);
        if (containerId == null) {
            return 0;
        }
        final boolean multiplexed = !docker.inspect(containerId).tty();
        final List<String> lines = docker.recentLines(containerId, max, multiplexed);
        if (lines.size() >= max) {
            return max;
        }
        return lines.size()
                + archive.before(service, LogFollows.oldest(lines), max - lines.size())
                        .lineCount();
    }

    static Optional<String> containerOf(final Docker docker, final String project, final String service) {
        return docker.containers(project).stream()
                .filter(container -> service.equals(container.service()) && container.isRunning())
                .map(Docker.Container::id)
                .findFirst();
    }

    /**
     * One row of the archive list, or nothing if that entry is not a file any more.
     *
     * One stat, not four: This used to ask the filesystem five separate questions about one path -
     * {@code isRegularFile}, {@code size} twice, {@code getLastModifiedTime} - and a backup directory is the one
     * place where the answers genuinely change between them. The writer grows the {@code .partial} while this
     * runs and renames it over the finished name when done, so the old code could report {@code bytes} from one
     * moment and {@code human} from another: a row reading "8 294 001 bytes (7.6 MB)" where the two halves
     * disagree. Read once, report that one moment.
     *
     * An entry that vanished loses its row, not the listing: The same rename makes a path from the directory
     * stream disappear before it can be read, and {@code Files.size} on it throws. Thrown out of the loop, that
     * turned "one archive finished while you were looking" into an empty backup page - read by an admin as "the
     * backups are gone". It is the most ordinary moment there is in that directory, so it ends the entry and
     * nothing more.
     */
    static Optional<Map<String, Object>> row(final Path entry) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class);
        } catch (IOException gone) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", entry.getFileName().toString());
        row.put("bytes", attributes.size());
        row.put("human", SnapshotResult.human(attributes.size()));
        row.put("modified", attributes.lastModifiedTime().toInstant().toString());
        // A .partial is running now or died halfway - showing it is the point; hiding it would be a lie.
        row.put("partial", entry.getFileName().toString().endsWith(".partial"));
        return Optional.of(row);
    }
}
