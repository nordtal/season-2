package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

@RegisterConstructorMapper(Credentials.Key.class)
public interface CredentialDao {

    @Transaction
    default void add(
            final byte[] credentialId,
            final DiscordId discordId,
            final byte[] publicKey,
            final long signatureCount,
            final String label,
            final Collection<String> transports,
            final @Nullable Boolean backupEligible,
            final @Nullable Boolean backedUp) {
        addKey(credentialId, discordId, publicKey, signatureCount, label, backupEligible, backedUp);
        addTransports(credentialId, transports.toArray(String[]::new));
    }

    @SqlUpdate("""
            INSERT INTO steward_credential
                (credential_id, discord_id, public_key, signature_count, label, backup_eligible, backed_up, created_at)
            VALUES (:credentialId, :discordId, :publicKey, :signatureCount, :label, :backupEligible, :backedUp, now())
            """)
    void addKey(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") DiscordId discordId,
            @Bind("publicKey") byte[] publicKey,
            @Bind("signatureCount") long signatureCount,
            @Bind("label") String label,
            @Bind("backupEligible") @Nullable Boolean backupEligible,
            @Bind("backedUp") @Nullable Boolean backedUp);

    @SqlUpdate("""
            INSERT INTO steward_credential_transport (credential_id, transport)
            SELECT :credentialId, transport FROM unnest(:transports) AS transport
            """)
    void addTransports(@Bind("credentialId") byte[] credentialId, @Bind("transports") String[] transports);

    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label,
                   ARRAY(SELECT transport FROM steward_credential_transport t
                         WHERE t.credential_id = c.credential_id ORDER BY transport) AS transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential c
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<Credentials.Key> forAccount(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT credential_id, discord_id, public_key, signature_count, label,
                   ARRAY(SELECT transport FROM steward_credential_transport t
                         WHERE t.credential_id = c.credential_id ORDER BY transport) AS transports,
                   backup_eligible, backed_up, created_at, last_used_at
            FROM steward_credential c
            WHERE credential_id = :credentialId
            """)
    Optional<Credentials.Key> byId(@Bind("credentialId") byte[] credentialId);

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM steward_credential WHERE credential_id = :credentialId)")
    boolean exists(@Bind("credentialId") byte[] credentialId);

    @SqlUpdate("DELETE FROM steward_credential WHERE discord_id = :discordId")
    int forget(@Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            DELETE FROM steward_credential
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int remove(@Bind("credentialId") byte[] credentialId, @Bind("discordId") DiscordId discordId);

    @SqlUpdate("""
            UPDATE steward_credential SET label = :label
            WHERE credential_id = :credentialId AND discord_id = :discordId
            """)
    int rename(
            @Bind("credentialId") byte[] credentialId,
            @Bind("discordId") DiscordId discordId,
            @Bind("label") String label);

    /** Writes the counter and the time. {@code GREATEST} keeps a replay from lowering the counter. */
    @SqlUpdate("""
            UPDATE steward_credential
            SET signature_count = GREATEST(signature_count, :signatureCount),
                last_used_at = now()
            WHERE credential_id = :credentialId
            """)
    int used(@Bind("credentialId") byte[] credentialId, @Bind("signatureCount") long signatureCount);
}
