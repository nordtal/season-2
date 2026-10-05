package eu.nordtal.s2.stewardagent.backup;

import eu.nordtal.s2.database.update.ByteSize;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The archives on the disk and one archive's download. */
final class ArchiveFiles {

    private static final Logger log = LoggerFactory.getLogger(ArchiveFiles.class);

    private ArchiveFiles() {}

    /** Every file in {@code backups}, newest first. */
    static List<AgentWire.Archive> list(final Path backups) {
        final List<AgentWire.Archive> all = new ArrayList<>();
        if (!Files.isDirectory(backups)) {
            return all;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(backups)) {
            for (final Path entry : entries) {
                row(entry).ifPresent(all::add);
            }
        } catch (final IOException e) {
            log.warn("could not list {}", backups, e);
        }
        all.sort(Comparator.comparing(AgentWire.Archive::modified).reversed());
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
        } catch (final IOException unreadable) {
            throw new NotFoundResponse("no such backup: " + name);
        }
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Length", String.valueOf(size));
        try {
            ctx.result(Files.newInputStream(resolved));
        } catch (final IOException gone) {
            throw new NotFoundResponse("no such backup: " + name);
        }
    }

    /**
     * One row of the archive list, or nothing if that entry is not a file any more.
     *
     * One stat per entry, so a file growing or renamed meanwhile cannot yield a row that disagrees with itself.
     */
    static Optional<AgentWire.Archive> row(final Path entry) {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class);
        } catch (final IOException gone) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            return Optional.empty();
        }
        final String name = entry.getFileName().toString();
        // A .partial is running now or died halfway, and showing it is the point.
        return Optional.of(new AgentWire.Archive(
                name,
                attributes.size(),
                ByteSize.of(attributes.size()).toString(),
                attributes.lastModifiedTime().toInstant(),
                name.endsWith(".partial"),
                TarSnapshots.restoresInto(name).orElse(null),
                entry.getParent() != null && OffsiteCopy.isCopied(entry.getParent(), name)));
    }
}
