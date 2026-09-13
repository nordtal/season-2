package eu.nordtal.s2.steward.ui.configfile;

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
 */
public record ConfigLocation(
        @NotNull String service,
        @NotNull String name,
        @NotNull Path file,
        boolean writable) {
}
