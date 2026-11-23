package eu.nordtal.season.steward.backup;

import eu.nordtal.season.common.SecretBox;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;

/**
 * Stores the Google accounts, restic repositories and active accounts of the backup series.
 * Refresh tokens and repository passwords are sealed under the backup key and bound to their row.
 */
public final class Destinations {

    /** The series that copy to Google Drive. */
    public static final Set<String> SERIES = Set.of(BackupDestinationWire.STACK, BackupDestinationWire.PLANE);

    /** The longest failure reason kept, so a tool's whole output never lands in a row. */
    static final int ERROR_CHARS = 500;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DestinationDao dao;
    private final SecretBox box;

    public Destinations(final DataSource dataSource, final SecretBox box) {
        this.dao = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource")).onDemand(DestinationDao.class);
        this.box = Objects.requireNonNull(box, "box");
    }

    /**
     * A connected or disconnected Google account.
     *
     * @param connected whether steward holds a refresh token for it
     */
    public record BackupAccount(
            UUID id,
            String email,
            @ColumnName("folder_id") String folderId,
            @ColumnName("folder_name") String folderName,
            boolean connected,
            @ColumnName("connected_at") Instant connectedAt) {}

    /** A restic repository, without its password. */
    public record BackupRepository(
            UUID id,
            @ColumnName("account_id") UUID accountId,
            String series,
            String subpath,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("recovery_downloaded_at") @Nullable Instant recoveryDownloadedAt) {}

    /** The active account of a series and how its last copy went. */
    public record BackupSeries(
            String series,
            @ColumnName("account_id") @Nullable UUID accountId,
            @Nullable String email,
            @ColumnName("last_copied_at") @Nullable Instant lastCopiedAt,
            @ColumnName("last_snapshot_id") @Nullable String lastSnapshotId,
            @ColumnName("last_bytes") @Nullable Long lastBytes,
            @ColumnName("last_error") @Nullable String lastError,
            @ColumnName("last_failed_at") @Nullable Instant lastFailedAt) {}

    /** The sealed secrets of one repository and its account. */
    record Sealed(
            @ColumnName("account_id") UUID accountId,
            String email,
            @ColumnName("folder_id") String folderId,
            @ColumnName("folder_name") String folderName,
            @ColumnName("refresh_token") byte @Nullable [] refreshToken,
            @ColumnName("repository_id") UUID repositoryId,
            String subpath,
            byte[] password) {}

    /** The opened secrets that a recovery file holds. */
    public record Recovery(
            String series, String email, String folderId, String folderName, String subpath, String password) {

        @Override
        public String toString() {
            return "Recovery[" + series + ", " + email + "]";
        }
    }

    /** Stores a consent, and returns the account's id. A reconnected account keeps its id and repositories. */
    public UUID connect(
            final GoogleDrive.Identity identity, final GoogleDrive.Folder folder, final String refreshToken) {
        final UUID fresh = dao.accountOf(identity.subject()).orElseGet(UUID::randomUUID);
        final UUID id = dao.connect(
                fresh,
                identity.subject(),
                identity.email(),
                folder.id(),
                folder.name(),
                sealToken(fresh, refreshToken));
        if (!id.equals(fresh)) {
            // Another connect of the same account won the insert, so the token is sealed for the wrong row.
            dao.reseal(id, sealToken(id, refreshToken));
        }
        return id;
    }

    public List<BackupAccount> accounts() {
        return dao.accounts();
    }

    public List<BackupRepository> repositories() {
        return dao.repositories();
    }

    public List<BackupSeries> series() {
        return dao.series();
    }

    /** Returns the refresh token of a connected account, which a disconnect revokes. */
    public Optional<String> refreshToken(final UUID accountId) {
        return dao.refreshToken(accountId).map(row -> openToken(accountId, row));
    }

    /** Forgets the account's token and stops every series that copies to it. Repositories stay. */
    public boolean disconnect(final UUID accountId) {
        return dao.disconnect(accountId);
    }

    /**
     * Makes {@code accountId} the destination of {@code series}, or no account for {@code null}.
     * The first choice of an account creates its repository with a random password.
     *
     * @return false when the account is unknown or disconnected
     */
    public boolean choose(final String series, final @Nullable UUID accountId) {
        requireSeries(series);
        if (accountId == null) {
            dao.choose(series, null);
            return true;
        }
        final boolean connected =
                dao.accounts().stream().anyMatch(account -> account.id().equals(accountId) && account.connected());
        if (!connected) {
            return false;
        }
        final UUID repository = UUID.randomUUID();
        final String subpath = series + "-" + HexFormat.of().formatHex(bytes(6));
        final String password = HexFormat.of().formatHex(bytes(32));
        dao.addRepository(repository, accountId, series, subpath, sealPassword(repository, password));
        dao.choose(series, accountId);
        return true;
    }

    /** Returns what the series needs for its copy, or nothing while it copies nowhere. */
    public Optional<BackupDestinationWire.Credentials> credentials(
            final String series, final String clientId, final String clientSecret) {
        requireSeries(series);
        return dao.active(series)
                .map(row -> new BackupDestinationWire.Credentials(
                        row.accountId().toString(),
                        row.email(),
                        row.folderId(),
                        row.folderName(),
                        row.subpath(),
                        clientId,
                        clientSecret,
                        openToken(row.accountId(), Objects.requireNonNull(row.refreshToken(), "refresh_token")),
                        openPassword(row.repositoryId(), row.password())));
    }

    /** Hands out the recovery file of a repository once. A second call returns nothing. */
    public Optional<Recovery> takeRecovery(final UUID accountId, final String series) {
        requireSeries(series);
        return dao.takeRecovery(accountId, series)
                .map(row -> new Recovery(
                        series,
                        row.email(),
                        row.folderId(),
                        row.folderName(),
                        row.subpath(),
                        openPassword(row.repositoryId(), row.password())));
    }

    /**
     * Records how a copy went. A report for an account that is no longer active changes nothing.
     *
     * @return false when the report names an account the series no longer copies to
     */
    public boolean report(
            final String series, final UUID accountId, final BackupDestinationWire.Report report, final Instant now) {
        requireSeries(series);
        if (report.ok()) {
            final Instant copiedAt = Objects.requireNonNullElse(report.copiedAt(), now);
            return dao.copied(series, accountId, copiedAt, report.snapshotId(), report.bytes()) == 1;
        }
        final String error = Objects.requireNonNullElse(report.error(), "no reason given");
        return dao.failed(series, accountId, error.length() > ERROR_CHARS ? error.substring(0, ERROR_CHARS) : error)
                == 1;
    }

    private static void requireSeries(final String series) {
        if (!SERIES.contains(series)) {
            throw new IllegalArgumentException("no backup series " + series);
        }
    }

    private byte[] sealToken(final UUID accountId, final String token) {
        return box.seal(token.getBytes(StandardCharsets.UTF_8), context("backup_google_accounts", accountId));
    }

    private String openToken(final UUID accountId, final byte[] closed) {
        return new String(box.open(closed, context("backup_google_accounts", accountId)), StandardCharsets.UTF_8);
    }

    private byte[] sealPassword(final UUID repositoryId, final String password) {
        return box.seal(password.getBytes(StandardCharsets.UTF_8), context("backup_repositories", repositoryId));
    }

    private String openPassword(final UUID repositoryId, final byte[] closed) {
        return new String(box.open(closed, context("backup_repositories", repositoryId)), StandardCharsets.UTF_8);
    }

    /** A sealed value copied to another row fails to open. */
    private static byte[] context(final String table, final UUID id) {
        return (table + ":" + id).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(final int count) {
        final byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
