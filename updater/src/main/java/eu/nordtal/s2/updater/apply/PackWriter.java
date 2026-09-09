package eu.nordtal.s2.updater.apply;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Changes exactly two lines of the proxy's {@code pack.yml}: {@code url} and {@code sha1}.
 *
 * <p>Editing two lines rather than loading the file through jcore avoids depending on
 * {@code PackSpec}, which belongs to {@code :network-control}, and keeps the updater unable to
 * touch {@code enabled}, {@code force} or {@code apply-timeout-seconds} by accident.</p>
 *
 * <p>A key missing from a file that exists is an error rather than something to append: appending
 * would leave YAML the proxy refuses at the worst moment. A file that is not there at all <em>is</em>
 * created with just these two keys, because a fresh volume is otherwise deadlocked - the proxy
 * writes {@code pack.yml} on first start, and a proxy with no jar in {@code plugins/} never starts.
 * jcore normalises on load, so the proxy's first start fills in the rest.</p>
 *
 * <p>A file already carrying the wanted values is left untouched byte for byte, so a modification
 * time still means the updater changed something.</p>
 */
public final class PackWriter {

    private PackWriter() {
    }

    /**
     * @return {@code true} if the file was written, {@code false} if it already said this.
     * @throws IOException if the file is missing, or does not carry both keys exactly once.
     */
    public static boolean write(final @NotNull Path packYml, final @NotNull String url,
                                final @NotNull String sha1) throws IOException {
        if (!Files.isRegularFile(packYml)) {
            return create(packYml, url, sha1);
        }

        final List<String> lines = Files.readAllLines(packYml, StandardCharsets.UTF_8);
        final List<String> written = new ArrayList<>(lines.size());
        int urls = 0;
        int sha1s = 0;

        for (final String line : lines) {
            if (isKey(line, "url")) {
                urls++;
                written.add("url: " + url);
            } else if (isKey(line, "sha1")) {
                sha1s++;
                written.add("sha1: " + sha1);
            } else {
                written.add(line);
            }
        }

        if (urls != 1 || sha1s != 1) {
            throw new IOException(packYml + " has " + urls + " top-level 'url' and " + sha1s
                    + " top-level 'sha1' line(s); exactly one of each was expected. Refusing to"
                    + " guess where they should go.");
        }

        if (written.equals(lines)) {
            return false;
        }

        // Atomic within the volume: written beside the file and renamed over it, so a crash leaves
        // either the old config or the new one and never half of either.
        final Path temporary = packYml.resolveSibling(packYml.getFileName() + ".updater-tmp");
        Files.write(temporary, written, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, packYml, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicUnsupported) {
            // Some volume drivers cannot do an atomic rename; a plain replace of a five-line file
            // is still better than writing in place.
            Files.move(temporary, packYml, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Writes a two-key {@code pack.yml} into a volume the proxy has never started against; the proxy
     * rewrites the header and adds the other three settings on its first load.
     */
    private static boolean create(final @NotNull Path packYml, final @NotNull String url,
                                  final @NotNull String sha1) throws IOException {
        Files.createDirectories(packYml.getParent());
        Files.write(packYml, List.of(
                "# Written by the updater against a volume network-control had never started",
                "# against. The proxy fills in enabled, force and apply-timeout-seconds with their",
                "# defaults on its first load, and rewrites this header.",
                "url: " + url,
                "sha1: " + sha1), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * A top-level key line: no indentation, so a {@code url:} nested under something else is not
     * touched. {@code pack.yml} is flat, which is what makes this safe.
     */
    private static boolean isKey(final @NotNull String line, final @NotNull String key) {
        return line.startsWith(key + ":")
                && (line.length() == key.length() + 1 || line.charAt(key.length() + 1) == ' ');
    }
}
