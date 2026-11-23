package eu.nordtal.season.stewardagent.backup;

import com.google.gson.JsonObject;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the rclone config of one copy into a directory only this process may read, and deletes it after.
 * The config holds the refresh token, so it never lives longer than the copy.
 */
final class RcloneConfig {

    private static final Logger log = LoggerFactory.getLogger(RcloneConfig.class);

    static final String FILE = "rclone.conf";

    private RcloneConfig() {}

    /** Returns the config file in a new directory of mode 700, itself of mode 600. */
    static Path write(final BackupDestinationWire.Credentials credentials) throws IOException {
        final String text = text(credentials);
        final Path directory = Files.createTempDirectory(
                "rclone-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path file = directory.resolve(FILE);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException | RuntimeException failed) {
            delete(file);
            throw failed;
        }
        return file;
    }

    /** Deletes the config and its directory, which rclone may have written a renewed token into. */
    static void delete(final Path file) {
        final @Nullable Path directory = file.getParent();
        try {
            if (directory == null || !Files.isDirectory(directory)) {
                Files.deleteIfExists(file);
                return;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (final Path entry : entries) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(directory);
        } catch (final IOException left) {
            log.error("rclone config not deleted: dir={}", directory, left);
        }
    }

    /**
     * Returns the config of the remote {@link OffsiteCopy#REMOTE}, rooted at the backup folder.
     * Its placeholder access token has expired, so rclone trades the refresh token for a fresh one at the first call.
     */
    static String text(final BackupDestinationWire.Credentials credentials) {
        final JsonObject token = new JsonObject();
        // rclone 1.60 reads a token without an access token in an old format that drops the refresh token.
        token.addProperty("access_token", "expired");
        token.addProperty("token_type", "Bearer");
        token.addProperty("refresh_token", credentials.refreshToken());
        // Go's oauth2 package takes a zero expiry for one that never expires and would send the placeholder.
        token.addProperty("expiry", "2000-01-01T00:00:00Z");
        final List<String> lines = List.of(
                "[" + OffsiteCopy.REMOTE + "]",
                "type = drive",
                "scope = drive.file",
                "client_id = " + value(credentials.clientId()),
                "client_secret = " + value(credentials.clientSecret()),
                "root_folder_id = " + value(credentials.folderId()),
                // Pruned packs would otherwise count against the account's quota for 30 days in the Drive trash.
                "use_trash = false",
                "token = " + value(token.toString()));
        return String.join("\n", lines) + "\n";
    }

    /** Refuses a value that would end its line and add a setting of its own. */
    private static String value(final String value) {
        if (value.isBlank() || value.chars().anyMatch(c -> c == '\n' || c == '\r')) {
            throw new IllegalArgumentException("steward sent a blank or multi-line value for the rclone config");
        }
        return value;
    }
}
