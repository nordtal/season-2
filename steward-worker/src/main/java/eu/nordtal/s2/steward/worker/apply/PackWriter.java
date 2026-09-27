package eu.nordtal.s2.steward.worker.apply;

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
 * A missing file is created with just those keys; a file missing a key is an error; an equal file stays untouched.
 */
public final class PackWriter {

    private PackWriter() {}

    /**
     * Writes the two values.
     *
     * @return {@code true} if the file was written, {@code false} if it already said this
     * @throws IOException if the file does not carry both keys exactly once
     */
    public static boolean write(final Path packYml, final String url, final String sha1) throws IOException {
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

        // Written beside the file and renamed over it, so a crash leaves one whole file.
        final Path temporary = packYml.resolveSibling(packYml.getFileName() + ".steward-worker-tmp");
        Files.write(temporary, written, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, packYml, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicUnsupported) {
            // Some volume drivers cannot do an atomic rename; a plain replace still beats writing in place.
            Files.move(temporary, packYml, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Writes a two-key {@code pack.yml} into a volume the proxy has never started against.
     *
     * The proxy adds the other settings on its first load.
     */
    private static boolean create(final Path packYml, final String url, final String sha1) throws IOException {
        Files.createDirectories(packYml.getParent());
        Files.write(
                packYml,
                List.of(
                        "# Written by steward-worker against a volume proxy had never started",
                        "# against. The proxy fills in enabled, force and apply-timeout-seconds with their",
                        "# defaults on its first load, and rewrites this header.",
                        "url: " + url,
                        "sha1: " + sha1),
                StandardCharsets.UTF_8);
        return true;
    }

    /** A top-level key line; nested keys are never touched, and {@code pack.yml} is flat. */
    private static boolean isKey(final String line, final String key) {
        return line.startsWith(key + ":")
                && (line.length() == key.length() + 1 || line.charAt(key.length() + 1) == ' ');
    }
}
