package eu.nordtal.s2.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root for the tests in this module that read files outside their own
 * source set.
 *
 * <p><b>It anchors on the directory holding {@code settings.gradle.kts}, never on the nearest file
 * by name</b>, and that is the whole reason this is a helper rather than three copies of a
 * {@code while} loop. {@code discord-bot/} shipped an {@code .env.example} of its own until
 * 2026-09-02 - a leftover of the bot's standalone compose deployment - and a walk-up by name found
 * that one first, so the check that existed to compare the real file against the bot's config was
 * silently reading the wrong file. An anchor that cannot be shadowed is what closes that.</p>
 *
 * <p>Every file reached through here also has to be declared in the module's build file, through
 * {@code repositoryRootTestInputs}: it is in no source set, so without the declaration Gradle
 * cannot see the dependency and an edit leaves {@code :common:test} UP-TO-DATE.</p>
 */
public final class RepositoryRoot {

    private RepositoryRoot() {
    }

    /** @return the directory holding {@code settings.gradle.kts} */
    public static Path path() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException(
                    "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return at;
    }

    /** @return {@code relative}, resolved against the repository root */
    public static Path resolve(final String relative) {
        return path().resolve(relative);
    }

    /** @return the UTF-8 contents of {@code relative}, resolved against the repository root */
    public static String read(final String relative) {
        try {
            return Files.readString(resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }
}
