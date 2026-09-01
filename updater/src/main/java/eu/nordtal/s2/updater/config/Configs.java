package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where {@code updater}'s config file lives, and every rule about what a valid value is.
 * <p>
 * Same shape as {@code network-control}'s and {@code discord-bot}'s {@code Configs}: one
 * environment namespace per file, and every check runs once at startup rather than being
 * discovered half way through a resolve.
 * </p>
 */
public final class Configs {

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private Configs() {
    }

    public static @NotNull ConfigHandle<UpdaterSpec> updater(final Path directory, final Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("updater.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<UpdaterSpec> handle = ConfigLoader.builder(file, UpdaterSpec.class)
                .envPrefix("NORDTAL_UPDATER")
                .validator(config -> {
                    requireRepo("season-repo", config.seasonRepo());
                    requireRepo("display-tags-repo", config.displayTagsRepo());
                    requireText("season-release", config.seasonRelease());
                    requireText("display-tags-release", config.displayTagsRelease());
                    requireModrinthId("packetevents-project", config.packetEventsProject());
                    requireModrinthId("chunky-project", config.chunkyProject());
                    requireText("minecraft-version", config.minecraftVersion());
                    requireText("velocity-version", config.velocityVersion());
                    requireText("volumes-root", config.volumesRoot());
                    requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
                })
                .load();

        // Unlike every other config in this repository, a fresh updater.yml IS what you want:
        // the defaults are the real nordtal.eu values. It is still said out loud, because a file
        // appearing where none was is worth one line in a log.
        if (fresh) {
            logger.info("No config existed at {} - it was written with this project's own defaults",
                    file.toAbsolutePath());
        }
        return handle;
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }

    private static void requireRepo(final String key, final String value) {
        requireText(key, value);
        if (!REPO.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " must be a GitHub repository as owner/name"
                    + " - not a URL and not just the name - was '" + value + "'");
        }
    }

    private static void requireModrinthId(final String key, final String value) {
        requireText(key, value);
        if (!MODRINTH_ID.matcher(value).matches()) {
            // A slug passes as text and fails as an id only when the author renames it, which is
            // months later and looks like an outage. Caught here instead, with the reason.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
