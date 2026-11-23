package eu.nordtal.season.steward.backup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/** Holds the SQL of {@link Destinations}. */
@RegisterConstructorMapper(Destinations.BackupAccount.class)
@RegisterConstructorMapper(Destinations.BackupRepository.class)
@RegisterConstructorMapper(Destinations.BackupSeries.class)
@RegisterConstructorMapper(Destinations.Sealed.class)
public interface DestinationDao {

    @SqlQuery("SELECT id FROM backup_google_accounts WHERE google_subject = :subject")
    Optional<UUID> accountOf(@Bind("subject") String subject);

    /** Returns the id of the row, which differs from {@code id} when the subject was connected before. */
    @SqlQuery("""
            INSERT INTO backup_google_accounts
                (id, google_subject, email, folder_id, folder_name, refresh_token, connected_at)
            VALUES (:id, :subject, :email, :folderId, :folderName, :token, now())
            ON CONFLICT (google_subject) DO UPDATE
                SET email = excluded.email,
                    folder_id = excluded.folder_id,
                    folder_name = excluded.folder_name,
                    refresh_token = excluded.refresh_token,
                    connected_at = excluded.connected_at,
                    disconnected_at = NULL
            RETURNING id
            """)
    UUID connect(
            @Bind("id") UUID id,
            @Bind("subject") String subject,
            @Bind("email") String email,
            @Bind("folderId") String folderId,
            @Bind("folderName") String folderName,
            @Bind("token") byte[] token);

    @SqlUpdate("UPDATE backup_google_accounts SET refresh_token = :token WHERE id = :id")
    int reseal(@Bind("id") UUID id, @Bind("token") byte[] token);

    @SqlQuery("""
            SELECT id, email, folder_id, folder_name, refresh_token IS NOT NULL AS connected, connected_at
            FROM backup_google_accounts
            ORDER BY connected_at, id
            """)
    List<Destinations.BackupAccount> accounts();

    @SqlQuery("SELECT refresh_token FROM backup_google_accounts WHERE id = :id AND refresh_token IS NOT NULL")
    Optional<byte[]> refreshToken(@Bind("id") UUID id);

    @SqlUpdate("""
            UPDATE backup_google_accounts SET refresh_token = NULL, disconnected_at = now()
            WHERE id = :id AND refresh_token IS NOT NULL
            """)
    int clearToken(@Bind("id") UUID id);

    @SqlUpdate("UPDATE backup_series_state SET account_id = NULL WHERE account_id = :id")
    int forgetAccount(@Bind("id") UUID id);

    /** Clears the token and every series that copies to the account, in one transaction. */
    @Transaction
    default boolean disconnect(final UUID id) {
        final int cleared = clearToken(id);
        forgetAccount(id);
        return cleared == 1;
    }

    @SqlUpdate("""
            INSERT INTO backup_repositories (id, account_id, series, subpath, password, created_at)
            VALUES (:id, :accountId, :series, :subpath, :password, now())
            ON CONFLICT (account_id, series) DO NOTHING
            """)
    int addRepository(
            @Bind("id") UUID id,
            @Bind("accountId") UUID accountId,
            @Bind("series") String series,
            @Bind("subpath") String subpath,
            @Bind("password") byte[] password);

    @SqlQuery("""
            SELECT id, account_id, series, subpath, created_at, recovery_downloaded_at
            FROM backup_repositories
            ORDER BY created_at, id
            """)
    List<Destinations.BackupRepository> repositories();

    /** Moves the series to {@code accountId}, or to nowhere. A move forgets the last copy, which went elsewhere. */
    @SqlUpdate("""
            UPDATE backup_series_state
            SET account_id = :accountId,
                last_copied_at = CASE WHEN account_id IS NOT DISTINCT FROM :accountId THEN last_copied_at END,
                last_snapshot_id = CASE WHEN account_id IS NOT DISTINCT FROM :accountId THEN last_snapshot_id END,
                last_bytes = CASE WHEN account_id IS NOT DISTINCT FROM :accountId THEN last_bytes END,
                last_error = CASE WHEN account_id IS NOT DISTINCT FROM :accountId THEN last_error END,
                last_failed_at = CASE WHEN account_id IS NOT DISTINCT FROM :accountId THEN last_failed_at END
            WHERE series = :series
            """)
    int choose(@Bind("series") String series, @Bind("accountId") @Nullable UUID accountId);

    @SqlQuery("""
            SELECT s.series, s.account_id, a.email, s.last_copied_at, s.last_snapshot_id, s.last_bytes,
                   s.last_error, s.last_failed_at
            FROM backup_series_state s
            LEFT JOIN backup_google_accounts a ON a.id = s.account_id
            ORDER BY s.series DESC
            """)
    List<Destinations.BackupSeries> series();

    /** Returns the sealed secrets of the series' active account, if it has one and it is connected. */
    @SqlQuery("""
            SELECT a.id AS account_id, a.email, a.folder_id, a.folder_name, a.refresh_token, r.id AS repository_id,
                   r.subpath, r.password
            FROM backup_series_state s
            JOIN backup_google_accounts a ON a.id = s.account_id
            JOIN backup_repositories r ON r.account_id = s.account_id AND r.series = s.series
            WHERE s.series = :series AND a.refresh_token IS NOT NULL
            """)
    Optional<Destinations.Sealed> active(@Bind("series") String series);

    /** Marks the recovery file handed out and returns its secrets, or nothing when it was handed out before. */
    @SqlQuery("""
            UPDATE backup_repositories r
            SET recovery_downloaded_at = now()
            FROM backup_google_accounts a
            WHERE a.id = r.account_id AND r.account_id = :accountId AND r.series = :series
              AND r.recovery_downloaded_at IS NULL
            RETURNING a.id AS account_id, a.email, a.folder_id, a.folder_name, NULL::bytea AS refresh_token,
                      r.id AS repository_id, r.subpath, r.password
            """)
    Optional<Destinations.Sealed> takeRecovery(@Bind("accountId") UUID accountId, @Bind("series") String series);

    @SqlUpdate("""
            UPDATE backup_series_state
            SET last_copied_at = :copiedAt, last_snapshot_id = :snapshotId, last_bytes = :bytes,
                last_error = NULL
            WHERE series = :series AND account_id = :accountId
            """)
    int copied(
            @Bind("series") String series,
            @Bind("accountId") UUID accountId,
            @Bind("copiedAt") Instant copiedAt,
            @Bind("snapshotId") @Nullable String snapshotId,
            @Bind("bytes") @Nullable Long bytes);

    @SqlUpdate("""
            UPDATE backup_series_state SET last_error = :error, last_failed_at = now()
            WHERE series = :series AND account_id = :accountId
            """)
    int failed(@Bind("series") String series, @Bind("accountId") UUID accountId, @Bind("error") String error);
}
