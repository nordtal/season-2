package eu.nordtal.s2.steward.configfile;

import java.nio.file.Path;

/**
 * One config file found under the mount, before anything has been read from it.
 *
 * @param service the compose service the file belongs to, the directory directly under the root
 * @param name the rest of the path under that directory, with {@code /} separators
 * @param file the absolute path on this container's filesystem
 * @param readable whether this process may open the file at all
 * @param writable whether this process can save a change, which needs the file and its directory writable
 */
public record ConfigLocation(String service, String name, Path file, boolean readable, boolean writable) {}
