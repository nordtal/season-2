package eu.nordtal.season.stewardagent.plan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Reads the data folder name of a jar from its descriptor: Paper's {@code name:}, else Velocity's {@code "id"}.
 *
 * A regular expression reads the one top-level key, because a parser fails on a stray tab.
 */
public final class PluginFolder {

    /** The descriptors in the order Paper prefers them. */
    private static final List<String> DESCRIPTORS = List.of("paper-plugin.yml", "plugin.yml");

    private static final Pattern NAME =
            Pattern.compile("^name:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*(?:#.*)?$", Pattern.MULTILINE);

    private static final String VELOCITY_DESCRIPTOR = "velocity-plugin.json";

    /** Velocity allows only these characters in an id, and names the data folder after it. */
    private static final Pattern VELOCITY_ID = Pattern.compile("\"id\"\\s*:\\s*\"([a-z][a-z0-9_-]{0,63})\"");

    private PluginFolder() {}

    /**
     * Reads the name the server gives the jar's data folder.
     *
     * @return the name, or {@code null} without a readable descriptor, so nothing else is deleted
     */
    public static @Nullable String nameIn(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (final String descriptor : DESCRIPTORS) {
                final ZipEntry entry = zip.getEntry(descriptor);
                if (entry == null) {
                    continue;
                }
                final String name = first(zip, entry, NAME);
                if (name != null) {
                    return name;
                }
            }
            final ZipEntry velocity = zip.getEntry(VELOCITY_DESCRIPTOR);
            return velocity == null ? null : first(zip, velocity, VELOCITY_ID);
        } catch (final IOException | RuntimeException unreadable) {
            // Swallowed: one bad jar must not empty the caller's list.
            return null;
        }
    }

    private static @Nullable String first(final ZipFile zip, final ZipEntry entry, final Pattern pattern)
            throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            final Matcher matcher = pattern.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
