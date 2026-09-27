package eu.nordtal.s2.steward.ui.auth;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/**
 * The SQL behind {@link Credentials}. Package-private: {@code Credentials} is the API.
 *
 * Nothing in here is secret: a credential's public key is public by construction, and the
 * credential id is handed to any browser that asks how to sign in.
 *
 * @see Sessions the sibling table, whose ids ARE credentials and are not so relaxed
 */
@RegisterConstructorMapper(Credentials.Key.class)
interface CredentialDao {

    @SqlUpdate("""
            INSERT INTO steward_credential
                (credential_id, discord_id, public_key, signature_count, label, transports,
                 backup_eligible, backed_up, created_at)
            VALUES (:credentialId, :discordId, :publicKey, :signatureCount, :label, :transports,
                    :backupEligible, :backedUp, now())
            """)
    void add(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") String discordId,
            @Bind("publicKey") byte[] publicKey,
            @Bind("signatureCount") long signatureCount,
            @Bind("label") String label,
            @Bind("transports") @Nullable String transports,
            @Bind("backupEligible") @Nullable Boolean backupEligible,
            @Bind("backedUp") @Nullable Boolean backedUp);

    /** Every key of one account, oldest first - which is the order somebody registered them in. */
    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label, transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<Credentials.Key> forAccount(@Bind("discordId") String discordId);

    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label, transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential
            WHERE credential_id = :credentialId
            """)
    Optional<Credentials.Key> byId(@Bind("credentialId") byte[] credentialId);

    /**
     * Whether this credential id is known to anybody at all.
     *
     * Deliberately not scoped to an account: two accounts claiming the same credential id is a
     * state this table must never be able to hold.
     */
    @SqlQuery("SELECT EXISTS(SELECT 1 FROM steward_credential WHERE credential_id = :credentialId)")
    boolean exists(@Bind("credentialId") byte[] credentialId);

    /**
     * Every key of one account, gone.
     *
     * Deliberately not exposed over HTTP at any privilege: clearing a second factor from a browser
     * would make it worth exactly as much as the first. {@code forget-factors} on the host is the
     * one caller.
     *
     * @return how many keys were removed, so the command can say a number rather than "done"
     */
    @SqlUpdate("DELETE FROM steward_credential WHERE discord_id = :discordId")
    int forget(@Bind("discordId") String discordId);

    /**
     * One key, gone - and only if it belongs to the account asking.
     *
     * The {@code discord_id} in the WHERE clause matters: a credential id is handed to any browser
     * that starts a sign-in, so without that column this would delete anybody's key by its id.
     *
     * @return 1 when a key was removed, 0 when there was none of that id on that account
     */
    @SqlUpdate("""
            DELETE FROM steward_credential
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int remove(@Bind("credentialId") byte[] credentialId, @Bind("discordId") String discordId);

    /** Renames one key of one account. Same argument about the second column as {@link #remove}. */
    @SqlUpdate("""
            UPDATE steward_credential SET label = :label
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int rename(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") String discordId,
            @Bind("label") String label);

    /**
     * The counter and the time, written after a successful assertion.
     *
     * Only ever forward: {@code GREATEST} keeps a replayed assertion from moving it backwards. An
     * authenticator that always reports 0 updates nothing here except the time.
     */
    @SqlUpdate("""
            UPDATE steward_credential
            SET signature_count = GREATEST(signature_count, :signatureCount),
                last_used_at = now()
            WHERE credential_id = :credentialId
            """)
    int used(@Bind("credentialId") byte[] credentialId, @Bind("signatureCount") long signatureCount);
}
