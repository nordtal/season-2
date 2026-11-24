package eu.nordtal.season.stewardagent.apply;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.stewardagent.plan.Installation;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Makes each standby's {@code plugins/} and server jars copies of its service's, except plugin scratch and bStats.
 *
 * A standby that is not mounted is skipped, and so is a jar cache its service does not have.
 */
@Slf4j
public final class Standbys {

    /** The directory a plugin writes while its server runs, directly in its data folder; each server keeps its own. */
    private static final String SCRATCH = "tmp";

    /**
     * bStats' folder in {@code plugins/}, which each server keeps for itself.
     *
     * The entrypoint turns bStats off at each start, and the live server's copy can predate that start.
     */
    private static final String METRICS = "bStats";

    /** Upper bound on mirroring one standby directory, so a FIFO swapped in at the open cannot stall an update run. */
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(2);

    private Standbys() {}

    /**
     * Mirrors {@code plugins/} and the server jar cache onto the standby of every named service that has one.
     *
     * @param volumesRoot where the services' volumes are mounted in this container
     * @param services the services this run touched; anything without a standby is ignored
     * @param topology which service has which standby
     * @return one row per mounted standby and directory, under the standby's compose service name
     */
    public static List<ApplyResult.Outcome> fill(
            final Path volumesRoot, final Collection<String> services, final AgentWire.Topology topology) {
        return fill(volumesRoot, services, topology, directory -> {}, COPY_TIMEOUT);
    }

    static List<ApplyResult.Outcome> fill(
            final Path volumesRoot,
            final Collection<String> services,
            final AgentWire.Topology topology,
            final Consumer<Path> opening) {
        return fill(volumesRoot, services, topology, opening, COPY_TIMEOUT);
    }

    static List<ApplyResult.Outcome> fill(
            final Path volumesRoot,
            final Collection<String> services,
            final AgentWire.Topology topology,
            final Consumer<Path> opening,
            final Duration timeout) {
        final List<ApplyResult.Outcome> outcomes = new ArrayList<>();
        for (final AgentWire.Service each : topology.services()) {
            final String service = each.standbyOf();
            if (service == null || !services.contains(service)) {
                continue;
            }
            final String standby = each.name();
            final Path standbyVolume = volumesRoot.resolve(standby);
            if (!Files.isDirectory(standbyVolume)) {
                log.info("standby copy skipped, volume not mounted: service={}", standby);
                continue;
            }
            final Path serviceVolume = volumesRoot.resolve(service);
            final Path source = serviceVolume.resolve(Installation.PLUGINS);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
                outcomes.add(new ApplyResult.Outcome(
                        standby,
                        Installation.PLUGINS,
                        ApplyResult.Status.FAILED,
                        service + " has no plugins/ directory at " + source + ", so there was"
                                + " nothing to copy. The standby is empty and will refuse to"
                                + " start."));
                continue;
            }
            outcomes.add(mirror(serviceVolume, standbyVolume, standby, Installation.PLUGINS, opening, timeout));
            // The entrypoint runs the newest jar cached, so a stale cache is an older Minecraft than the service's.
            final Path cache = serviceVolume.resolve(Installation.SERVER_CACHE);
            // A link is mirrored, so that its row fails instead of the cache being silently left stale.
            if (Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cache)) {
                outcomes.add(
                        mirror(serviceVolume, standbyVolume, standby, Installation.SERVER_CACHE, opening, timeout));
            }
        }
        return List.copyOf(outcomes);
    }

    private static ApplyResult.Outcome mirror(
            final Path serviceVolume,
            final Path standbyVolume,
            final String standby,
            final String directory,
            final Consumer<Path> opening,
            final Duration timeout) {
        final Path source = serviceVolume.resolve(directory);
        final Path target = standbyVolume.resolve(directory);
        // Either side belongs to the game's account, which could point it at the agent's secrets or a world.
        final Path link = Files.isSymbolicLink(source) ? source : target;
        if (Files.isSymbolicLink(link)) {
            log.error("standby file copy refused, symbolic link: service={} dir={} path={}", standby, directory, link);
            return new ApplyResult.Outcome(
                    standby,
                    directory,
                    ApplyResult.Status.FAILED,
                    link + " is a symbolic link, so nothing was copied. Started for a swap, this"
                            + " service would come up on whatever is still lying in it.");
        }
        final Tally tally;
        try {
            tally = copyWithinBudget(serviceVolume, standbyVolume, standby, directory, target, opening, timeout);
        } catch (final IOException failed) {
            log.error(
                    "standby file copy failed: service={} dir={} from={} cause={}",
                    standby,
                    directory,
                    source,
                    failed.getMessage());
            return new ApplyResult.Outcome(
                    standby,
                    directory,
                    ApplyResult.Status.FAILED,
                    "could not be filled from " + source + ": " + failed.getMessage()
                            + ". Started for a swap, this service would come up on whatever is"
                            + " still lying in it.");
        }
        if (tally.copied == 0 && tally.removed == 0) {
            return new ApplyResult.Outcome(
                    standby, directory, ApplyResult.Status.UNCHANGED, "already the same " + tally.same + " file(s)");
        }
        return new ApplyResult.Outcome(
                standby,
                directory,
                ApplyResult.Status.DONE,
                tally.copied + " file(s) copied"
                        + (tally.removed == 0 ? "" : ", " + tally.removed + " removed")
                        + ", " + tally.same + " already the same");
    }

    /** Runs one directory mirror on a daemon thread so a blocking open cannot stall the update run. */
    private static Tally copyWithinBudget(
            final Path serviceVolume,
            final Path standbyVolume,
            final String standby,
            final String directory,
            final Path target,
            final Consumer<Path> opening,
            final Duration timeout)
            throws IOException {
        final FutureTask<Tally> task =
                new FutureTask<>(() -> copyTree(serviceVolume, standbyVolume, directory, target, opening));
        Thread.ofPlatform()
                .daemon(true)
                .name("standby-mirror-" + standby + "-" + directory)
                .start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException timedOut) {
            task.cancel(true);
            throw new IOException("timed out after " + formatDuration(timeout), timedOut);
        } catch (final InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while copying", interrupted);
        } catch (final ExecutionException failed) {
            final Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    private static Tally copyTree(
            final Path serviceVolume,
            final Path standbyVolume,
            final String directory,
            final Path target,
            final Consumer<Path> opening)
            throws IOException {
        final Tally tally = new Tally();
        // A volume root is a mount point, which no service can swap, so only it is opened by path.
        try (Folder sourceRoot = Folder.open(serviceVolume);
                Folder standbyRoot = Folder.open(standbyVolume)) {
            if (standbyRoot.find(directory) == null) {
                Files.createDirectory(target);
            }
            try (Folder from = sourceRoot.child(directory, opening);
                    Folder into = standbyRoot.child(directory, opening)) {
                // plugins/ is its own bind mount on the volume's file system, and a rename out of it fails EXDEV.
                final Folder workbench = Installation.PLUGINS.equals(directory) ? into : standbyRoot;
                new Copy(workbench, opening, tally).into(from, into, 0);
            }
        }
        return tally;
    }

    private static String formatDuration(final Duration duration) {
        final long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        final long seconds = duration.toSeconds();
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static void abortIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("standby copy cancelled");
        }
    }

    private static boolean isScratch(final String name, final PosixFileAttributes attributes, final int depth) {
        return depth == 1 && SCRATCH.equals(name) && attributes.isDirectory();
    }

    private static boolean isMetrics(final String name, final PosixFileAttributes attributes, final int depth) {
        return depth == 0 && METRICS.equals(name) && attributes.isDirectory();
    }

    /** A name no entry has yet, for a file or directory made under it and then moved into place. */
    private static String unused() {
        return Applier.STAGING + "-" + UUID.randomUUID();
    }

    /**
     * A directory the agent holds open, so its entries are found relative to it and never through a path.
     *
     * @param path the directory's path, for messages and the test that swaps it
     */
    private record Folder(SecureDirectoryStream<Path> entries, Path path) implements Closeable {

        /** Opens a directory by path, which only a path no service can change is safe for. */
        static Folder open(final Path path) throws IOException {
            // Through the provider, as Error Prone wants Files.newDirectoryStream closed where it opens.
            final DirectoryStream<Path> entries =
                    path.getFileSystem().provider().newDirectoryStream(path, entry -> true);
            if (!(entries instanceof SecureDirectoryStream<Path> secure)) {
                entries.close();
                throw new IOException("this file system cannot open a directory relative to another, so " + path
                        + " cannot be copied without following a link someone puts in its place");
            }
            return new Folder(secure, path);
        }

        /** Opens an entry as a directory and refuses it if it is a symbolic link by then. */
        Folder child(final String name, final Consumer<Path> opening) throws IOException {
            abortIfInterrupted();
            final Path path = this.path.resolve(name);
            opening.accept(path);
            final SecureDirectoryStream<Path> opened =
                    entries.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS);
            if (Thread.currentThread().isInterrupted()) {
                opened.close();
                throw new InterruptedIOException("standby copy cancelled");
            }
            return new Folder(opened, path);
        }

        /** The entry itself, a link not followed, or {@code null} if there is none. */
        @Nullable
        PosixFileAttributes find(final String name) throws IOException {
            try {
                return entries.getFileAttributeView(
                                Path.of(name), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
            } catch (final NoSuchFileException gone) {
                return null;
            }
        }

        InputStream read(final String name, final Consumer<Path> opening) throws IOException {
            abortIfInterrupted();
            opening.accept(this.path.resolve(name));
            final SeekableByteChannel channel =
                    entries.newByteChannel(Path.of(name), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            // openat on a FIFO ignores Thread.interrupt until a writer unblocks it, so re-check before reading.
            if (Thread.currentThread().isInterrupted()) {
                channel.close();
                throw new InterruptedIOException("standby copy cancelled");
            }
            return Channels.newInputStream(channel);
        }

        void move(final String name, final Folder into, final String as) throws IOException {
            entries.move(Path.of(name), into.entries, Path.of(as));
        }

        @Override
        public void close() throws IOException {
            entries.close();
        }
    }

    /**
     * One mirror run: a directory made equal to another, recursively, except plugin scratch and bStats' folder.
     *
     * @param workbench where new directories are made, a mount point holding the whole target tree
     */
    private record Copy(Folder workbench, Consumer<Path> opening, Tally tally) {

        void into(final Folder source, final Folder target, final int depth) throws IOException {
            final Set<String> wanted = new LinkedHashSet<>();
            for (final Path entry : source.entries()) {
                abortIfInterrupted();
                final String name = entry.getFileName().toString();
                final PosixFileAttributes attributes = source.find(name);
                // A link, and anything else that is neither a file nor a directory, is skipped.
                if (attributes == null || !(attributes.isDirectory() || attributes.isRegularFile())) {
                    continue;
                }
                // Applier's half-written downloads, left by a run that died mid-phase.
                if (Applier.STAGING.equals(name)
                        || isScratch(name, attributes, depth)
                        || isMetrics(name, attributes, depth)) {
                    continue;
                }
                wanted.add(name);
                final PosixFileAttributes existing = target.find(name);
                if (existing != null && existing.isSymbolicLink()) {
                    continue;
                }
                if (attributes.isDirectory()) {
                    try (Folder from = source.child(name, opening);
                            Folder to = existing == null ? makeDirectory(target, name) : target.child(name, opening)) {
                        into(from, to, depth + 1);
                    }
                } else if (isDifferent(source, target, name, existing)) {
                    copy(source, target, name, attributes);
                    tally.copied++;
                } else {
                    tally.same++;
                }
            }
            for (final Path entry : target.entries()) {
                abortIfInterrupted();
                final String name = entry.getFileName().toString();
                final PosixFileAttributes attributes = target.find(name);
                if (attributes == null
                        || attributes.isSymbolicLink()
                        || wanted.contains(name)
                        || isScratch(name, attributes, depth)
                        || isMetrics(name, attributes, depth)) {
                    continue;
                }
                tally.removed += delete(target, name, attributes);
            }
        }

        /**
         * Makes a directory on the workbench, opens it and moves it into place, as no open directory can make one.
         *
         * @return the new directory, held open so a link swapped in after the move is never followed
         */
        private Folder makeDirectory(final Folder parent, final String name) throws IOException {
            final String made = unused();
            // A fresh name under a mount point: createDirectory refuses an existing entry and follows no link there.
            Files.createDirectory(workbench.path().resolve(made));
            try {
                final Folder opened = workbench.child(made, opening);
                try {
                    workbench.move(made, parent, name);
                } catch (final IOException failed) {
                    opened.close();
                    throw failed;
                }
                return new Folder(opened.entries(), parent.path().resolve(name));
            } finally {
                deleteIfLeft(workbench, made, true);
            }
        }

        /** Writes the file beside its destination and moves it over, so a running server never reads half a jar. */
        private void copy(
                final Folder source, final Folder target, final String name, final PosixFileAttributes attributes)
                throws IOException {
            final String made = unused();
            try {
                try (InputStream in = source.read(name, opening);
                        OutputStream out = Channels.newOutputStream(target.entries()
                                .newByteChannel(
                                        Path.of(made),
                                        Set.of(
                                                StandardOpenOption.WRITE,
                                                StandardOpenOption.CREATE_NEW,
                                                LinkOption.NOFOLLOW_LINKS),
                                        PosixFilePermissions.asFileAttribute(attributes.permissions())))) {
                    in.transferTo(out);
                }
                abortIfInterrupted();
                target.move(made, target, name);
            } finally {
                deleteIfLeft(target, made, false);
            }
        }

        private boolean isDifferent(
                final Folder source,
                final Folder target,
                final String name,
                final @Nullable PosixFileAttributes existing)
                throws IOException {
            if (existing == null || !existing.isRegularFile()) {
                return true;
            }
            // By content, as timestamps collide and a changed file can keep its size.
            try (InputStream left = source.read(name, opening);
                    InputStream right = target.read(name, opening)) {
                final byte[] one = new byte[8192];
                final byte[] other = new byte[8192];
                while (true) {
                    final int read = left.readNBytes(one, 0, one.length);
                    if (read != right.readNBytes(other, 0, other.length)
                            || !Arrays.equals(one, 0, read, other, 0, read)) {
                        return true;
                    }
                    if (read < one.length) {
                        return false;
                    }
                }
            }
        }

        private int delete(final Folder parent, final String name, final PosixFileAttributes attributes)
                throws IOException {
            abortIfInterrupted();
            if (!attributes.isDirectory()) {
                parent.entries().deleteFile(Path.of(name));
                return 1;
            }
            int removed = 0;
            try (Folder folder = parent.child(name, opening)) {
                for (final Path entry : folder.entries()) {
                    final String child = entry.getFileName().toString();
                    final PosixFileAttributes inside = folder.find(child);
                    if (inside != null) {
                        removed += delete(folder, child, inside);
                    }
                }
            }
            parent.entries().deleteDirectory(Path.of(name));
            return removed;
        }

        private static void deleteIfLeft(final Folder parent, final String name, final boolean directory)
                throws IOException {
            try {
                if (directory) {
                    parent.entries().deleteDirectory(Path.of(name));
                } else {
                    parent.entries().deleteFile(Path.of(name));
                }
            } catch (final NoSuchFileException moved) {
                // Moved into place, which is the usual end.
            }
        }
    }

    private static final class Tally {
        private int copied;
        private int removed;
        private int same;
    }
}
