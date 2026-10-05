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
 * Which directory under {@code plugins/} a jar makes for itself, from the {@code name:} in its descriptor.
 *
 * Read with a regular expression: one top-level key is wanted, and a parser would fail on a stray tab.
 */
public final class PluginFolder {

    /** The two descriptors, in the order Paper prefers them. */
    private static final List<String> DESCRIPTORS = List.of("paper-plugin.yml", "plugin.yml");

    /** A top-level {@code name:} with an optional quote around its value, anchored to column one. */
    private static final Pattern NAME =
            Pattern.compile("^name:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*(?:#.*)?$", Pattern.MULTILINE);

    private PluginFolder() {}

    /**
     * Reads the name the server gives the jar's data folder.
     *
     * @param jar a plugin jar in a service's {@code plugins/} folder
     * @return the folder name, or {@code null} without a readable descriptor, so nothing else is ever deleted
     */
    public static @Nullable String nameIn(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (final String descriptor : DESCRIPTORS) {
                final ZipEntry entry = zip.getEntry(descriptor);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    final Matcher matcher = NAME.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            return null;
        } catch (final IOException | RuntimeException unreadable) {
            // Swallowed: one bad jar must not empty the caller's list.
            return null;
        }
    }
}
