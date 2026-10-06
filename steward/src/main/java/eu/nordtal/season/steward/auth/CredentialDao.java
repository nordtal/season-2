package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/**
 * The SQL behind {@link Credentials}, which is the API.
 *
 * Nothing in here is secret: a public key is public, and a credential id is handed to any browser signing in.
 */
@RegisterConstructorMapper(Credentials.Key.class)
public interface CredentialDao {

    @SqlUpdate("""
            INSERT INTO steward_credential
                (credential_id, discord_id, public_key, signature_count, label, transports,
                 backup_eligible, backed_up, created_at)
            VALUES (:credentialId, :discordId, :publicKey, :signatureCount, :label, :transports,
                    :backupEligible, :backedUp, now())
            """)
    void add(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") DiscordId discordId,
            @Bind("publicKey") byte[] publicKey,
            @Bind("signatureCount") long signatureCount,
            @Bind("label") String label,
            @Bind("transports") @Nullable String transports,
            @Bind("backupEligible") @Nullable Boolean backupEligible,
            @Bind("backedUp") @Nullable Boolean backedUp);

    /** Every key of one account, in the order they were registered. */
    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label, transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<Credentials.Key> forAccount(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label, transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential
            WHERE credential_id = :credentialId
            """)
    Optional<Credentials.Key> byId(@Bind("credentialId") byte[] credentialId);

    /** Whether this credential id is known on any account, since two accounts must never share one. */
    @SqlQuery("SELECT EXISTS(SELECT 1 FROM steward_credential WHERE credential_id = :credentialId)")
    boolean exists(@Bind("credentialId") byte[] credentialId);

    /** Removes every key of one account, called only by {@code forget-factors} on the host, never over HTTP. */
    @SqlUpdate("DELETE FROM steward_credential WHERE discord_id = :discordId")
    int forget(@Bind("discordId") DiscordId discordId);

    /** Removes one key, only if it belongs to the account asking, since any signing-in browser learns key ids. */
    @SqlUpdate("""
            DELETE FROM steward_credential
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int remove(@Bind("credentialId") byte[] credentialId, @Bind("discordId") DiscordId discordId);

    /** Renames one key of one account, scoped like {@link #remove}. */
    @SqlUpdate("""
            UPDATE steward_credential SET label = :label
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int rename(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") DiscordId discordId,
            @Bind("label") String label);

    /** Writes the counter and the time after an assertion; {@code GREATEST} keeps a replay from moving it backwards. */
    @SqlUpdate("""
            UPDATE steward_credential
            SET signature_count = GREATEST(signature_count, :signatureCount),
                last_used_at = now()
            WHERE credential_id = :credentialId
            """)
    int used(@Bind("credentialId") byte[] credentialId, @Bind("signatureCount") long signatureCount);
}
