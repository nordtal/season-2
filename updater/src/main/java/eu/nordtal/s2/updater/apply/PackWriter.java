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
 * <h2>Why two lines and not the whole file</h2>
 * The obvious implementation is jcore: load {@code pack.yml} through {@code ConfigLoader}, set the
 * values, {@code save()} - which writes atomically and preserves comments. It was not chosen for
 * one reason: <b>that needs {@code PackSpec}, and {@code PackSpec} belongs to
 * {@code :network-control}.</b> The choices were a dependency from this module onto a Velocity
 * plugin, moving the spec into {@code :common} where nothing else wants it, or keeping a second
 * copy of a config schema. All three are worse than editing the two lines this module actually
 * owns.
 *
 * <p>It is also the smaller privilege. The updater has no opinion about {@code enabled},
 * {@code force} or {@code apply-timeout-seconds}, and rewriting the file through a spec it does
 * not share would put it in a position to change them by accident. This cannot.</p>
 *
 * <h2>It fails rather than appends - but it does create</h2>
 * A key that is not in a file that <em>exists</em> is an error, not something to add at the end.
 * jcore writes {@code pack.yml} with all five keys or none; a file carrying three is a file this
 * module does not understand, and appending to it would produce a config that either duplicates a
 * key or lands in the wrong place - both of which the proxy would then refuse, at the worst
 * moment, with a message about YAML.
 *
 * <p><b>A file that is not there at all is created</b>, with these two keys and nothing else, and
 * that is not the same decision. It resolves a genuine deadlock on a fresh volume: the proxy writes
 * {@code pack.yml} on its first start, but a proxy with no jar in {@code plugins/} does not start
 * at all - and filling {@code plugins/} is this module's job. Without this, a first deployment
 * would be apply, start, watch it refuse, apply again, start again.</p>
 *
 * <p>Writing two keys is safe because of how jcore loads a config: it <em>normalises</em>. Missing
 * settings are added with their defaults, comments and the header are rewritten, ordering is fixed,
 * and only unknown keys are rejected (read from {@code ConfigHandle#doLoad}, 2026-09-01). So the
 * proxy's first start turns this two-line file into the full commented one, keeping the two values
 * that were put there. If that ever stopped being true, the proxy would fail closed by name, which
 * is the same failure as having no pack at all.</p>
 *
 * <h2>The value is compared before it is written</h2>
 * A file already carrying the wanted values is left untouched, byte for byte. A rewrite that
 * changes nothing still changes a modification time, and on a config volume that is the difference
 * between "the updater has never touched this" and "something did, and we do not know what".
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
            // Some volume drivers do not support an atomic rename. A plain replace is still far
            // better than writing in place, and this is a five-line file.
            Files.move(temporary, packYml, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Writes a two-key {@code pack.yml} into a volume the proxy has never started against. The
     * comment says what the file is, for whoever opens it before the proxy has normalised it; the
     * proxy rewrites the header and adds the other three settings on its first load.
     */
    private static boolean create(final @NotNull Path packYml, final @NotNull String url,
                                  final @NotNull String sha1) throws IOException {
        Files.createDirectories(packYml.getParent());
        Files.write(packYml, List.of(
                "# Written by the updater against a volume network-control had never started",
                "# against. The proxy fills in enabled, force and apply-timeout-seconds with their",
                "# defaults on its first load, and rewrites this header. See docs/updater.md.",
                "url: " + url,
                "sha1: " + sha1), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * A top-level key line: no indentation, so a {@code url:} nested under something else is not
     * touched. {@code pack.yml} is flat, which is what makes this safe rather than clever.
     */
    private static boolean isKey(final @NotNull String line, final @NotNull String key) {
        return line.startsWith(key + ":")
                && (line.length() == key.length() + 1 || line.charAt(key.length() + 1) == ' ');
    }
}
