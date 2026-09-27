package eu.nordtal.s2.steward.worker.plan;

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
 * Which directory under {@code plugins/} a jar will make for itself.
 *
 * Why this cannot be guessed from the filename: Paper names a plugin's data folder after the {@code name:} in its
 * descriptor, not after its jar. {@code voicechat-bukkit-2.6.18.jar} makes {@code plugins/voicechat/};
 * {@code CoreProtect-CE-24.0.jar} makes {@code plugins/CoreProtect/}. Removing a plugin means deleting that folder,
 * and the confirmation names it - a dialog that says "and its data folder" while the
 * operator is looking at the one directory in this whole installation they have edited by hand is not a
 * confirmation, it is a formality.
 *
 * Read with a regular expression, not a YAML parser: One key is wanted, at the top level, out of a file written by a
 * stranger. A parser would be a dependency in a module that has none for this, and would fail the whole read on a
 * descriptor with a tab in it somewhere else. The expression asks for a {@code name:} in column one, which is the
 * only place a top-level key can be, and answers {@code null} for anything it does not recognise - and {@code null}
 * is handled: the caller says it could not name the folder rather than guessing one and deleting it.
 */
public final class PluginFolder {

    /**
     * The two descriptors, in the order Paper prefers them.
     *
     * A jar may carry both - a plugin supporting old and new servers does - and Paper reads {@code paper-plugin.yml}
     * when it is there. The {@code name:} in the two is the same in every jar anybody has shipped, but reading them
     * in a different order than the server does would be a guess, and the whole point here is not to guess.
     */
    private static final List<String> DESCRIPTORS = List.of("paper-plugin.yml", "plugin.yml");

    /**
     * A top-level {@code name:} with an optional quote around its value.
     *
     * Anchored to the start of a line in {@code MULTILINE}, so a {@code name:} nested under {@code libraries:} or
     * {@code commands:} - where every plugin has several - cannot match.
     */
    private static final Pattern NAME =
            Pattern.compile("^name:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*(?:#.*)?$", Pattern.MULTILINE);

    private PluginFolder() {}

    /**
     * @param jar a plugin jar in a service's {@code plugins/} folder
     * @return the folder name the server will give it, or {@code null} when the jar carries no
     *         readable descriptor - which a caller must treat as "cannot say", never as a reason to
     *         delete something else
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
            // Swallowed rather than thrown: the caller lists many plugins and one bad jar must not empty the list.
            return null;
        }
    }
}
