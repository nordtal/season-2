package eu.nordtal.season.internalapi.backup;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The routes on which a backup series asks steward where its remote copy goes and tells it how the copy went.
 * Each series sends its own Bearer token: the stack the agent token, Plane the Plane backup token.
 */
public final class BackupDestinationWire {

    /** The series of the stack's own backups, copied by steward-agent. */
    public static final String STACK = "stack";

    /** The series of the Plane backups, copied by the host's Plane backup job. */
    public static final String PLANE = "plane";

    /** GET {@link Credentials}, or 204 while the series copies nowhere. */
    public static final String CREDENTIALS = "/api/internal/backup-destinations/{series}/credentials";

    /** POST a {@link Report}, answered with 204. */
    public static final String REPORT = "/api/internal/backup-destinations/{series}/report";

    private BackupDestinationWire() {}

    /** Returns {@code path} with its {@code {series}} filled in. */
    public static String of(final String path, final String series) {
        return path.replace("{series}", series);
    }

    /**
     * What a series needs to write its restic repository into the chosen Google Drive folder.
     *
     * @param subpath the folder below {@code folderId} that holds the repository
     * @param refreshToken the OAuth refresh token that rclone trades for access tokens
     */
    public record Credentials(
            String accountId,
            String email,
            String folderId,
            String folderName,
            String subpath,
            String clientId,
            String clientSecret,
            String refreshToken,
            String resticPassword) {

        /** Keeps the secrets out of a log line that prints the record. */
        @Override
        public String toString() {
            return "Credentials[" + email + ", " + folderName + "/" + subpath + "]";
        }
    }

    /**
     * How one remote copy went.
     *
     * @param accountId the account of the {@link Credentials} the copy used, so a copy that ran while an admin chose
     *     another account is not credited to the new one
     * @param copiedAt when the copy finished, or {@code null} if it failed
     * @param snapshotId the restic snapshot that the copy made, or {@code null} if it failed
     * @param bytes what the copy added to the repository, or {@code null} if it failed
     * @param error the reason of the failure, or {@code null} if it copied
     */
    public record Report(
            String accountId,
            boolean ok,
            @Nullable Instant copiedAt,
            @Nullable String snapshotId,
            @Nullable Long bytes,
            @Nullable String error) {}
}
