package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.steward.backup.SnapshotResult;
import eu.nordtal.s2.steward.backup.TarSnapshots;
import eu.nordtal.s2.steward.docker.Docker;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The backup list, one archive's download, and the console's log capacity. */
final class Archives {

    private static final Logger log = LoggerFactory.getLogger(Archives.class);

    private Archives() {}

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
     * Streams one archive or dump out of {@code backups}, never buffered.
     *
     * The name must match a finished archive and resolve inside {@code backups}; the regex alone lets {@code ../} in.
     */
    static void download(final Context ctx, final Path backups, final String name) {
        if (!TarSnapshots.isFinishedArchive(name)) {
            throw new BadRequestResponse("not the name of a finished backup: " + name);
        }
        final Path resolved = backups.resolve(name).normalize();
        final Path parent = resolved.getParent();
        if (!resolved.startsWith(backups.normalize()) || parent == null || !parent.equals(backups.normalize())) {
            // Never reached by a plain filename: the second, independent check.
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
            final Docker docker,
            final String project,
            final LogArchive archive,
            final String service,
            final int max,
            final Instant now) {
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
                + archive.before(service, LogFollows.oldest(lines, now), max - lines.size())
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
     * One stat per entry, so a file growing or renamed meanwhile cannot yield a row that disagrees with itself.
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
        // A .partial is running now or died halfway, and showing it is the point.
        row.put("partial", entry.getFileName().toString().endsWith(".partial"));
        return Optional.of(row);
    }
}
