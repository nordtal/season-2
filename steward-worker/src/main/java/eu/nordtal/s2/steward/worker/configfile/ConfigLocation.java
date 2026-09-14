package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * One config file found under the mount, before anything has been read from it.
 *
 * @param service  the directory directly under the root, which is the compose service the file
 *                 belongs to - {@code steward-worker}, {@code smp}, {@code discord-bot}
 * @param name     the rest of the path under that directory, with {@code /} separators:
 *                 {@code steward.yml}, or {@code nordtal-smp/config.yml} for a plugin's file
 *                 inside a server's data directory
 * @param file     the absolute path on this container's filesystem
 * @param writable whether this process could actually save a change - the file <em>and</em> its
 *                 directory have to be writable, because the write is a create-and-rename. A
 *                 read-only mount is a normal thing for another service's volume to be, and the
 *                 page has to grey the form out rather than fail at save time
 * @param readable whether this process may open the file at all.
 *                 <p><b>Asked here rather than discovered at the tap.</b> The listing used to read
 *                 nothing, so a file this process could not open looked exactly like one it could
 *                 and only said so when somebody clicked it - as an error alert with a path in it.
 *                 A list that knows and does not say is worse than a short list. Costs one
 *                 {@code access(2)} per file, on about two dozen files, once per page.</p>
 */
public record ConfigLocation(
        @NotNull String service,
        @NotNull String name,
        @NotNull Path file,
        boolean readable,
        boolean writable) {
}
