package eu.nordtal.s2.steward.ui.auth;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL behind {@link Credentials}. Package-private: {@code Credentials} is the API.
 *
 * <p>Nothing in here is secret. A credential's public key is public by construction, and the
 * credential id is handed to any browser that asks how to sign in - which is why this table, unlike
 * {@code steward_session}, can be read out loud while debugging without ending anybody's session.
 * The only thing that could be forged with a copy of it is an answer to "which keys exist".</p>
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
            @Bind("transports") String transports,
            @Bind("backupEligible") Boolean backupEligible,
            @Bind("backedUp") Boolean backedUp);

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
     * <p>The library asks this while finishing a registration, to refuse a key that is already
     * registered - to somebody else as much as to the same person. It is deliberately not scoped
     * to an account: a credential id belongs to one authenticator and one relying party, and two
     * accounts claiming the same one is a state this table must never be able to hold.</p>
     */
    @SqlQuery("SELECT EXISTS(SELECT 1 FROM steward_credential WHERE credential_id = :credentialId)")
    boolean exists(@Bind("credentialId") byte[] credentialId);

    /**
     * Every key of one account, gone.
     *
     * <p>The way back when somebody has lost their only authenticator, and the only route to this
     * table that destroys anything. It is deliberately not exposed over HTTP at any privilege:
     * being able to clear somebody's second factor from a browser would make the second factor
     * worth exactly as much as the first. {@code forget-factors} on the host is the one caller,
     * and it is a person standing at the machine.</p>
     *
     * @return how many keys were removed, so the command can say a number rather than "done"
     */
    @SqlUpdate("DELETE FROM steward_credential WHERE discord_id = :discordId")
    int forget(@Bind("discordId") String discordId);

    /**
     * One key, gone - and only if it belongs to the account asking.
     *
     * <p>The {@code discord_id} in the WHERE clause is not belt and braces: a credential id is
     * handed to any browser that starts a sign-in, so it is a value somebody else can hold. Without
     * that second column this would be "delete anybody's key if you know its id".</p>
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
     * <p>Only ever forward: the {@code >} keeps a replayed assertion from moving the counter
     * backwards even in the moment between the library checking it and this running. An
     * authenticator that reports 0 forever - every iCloud passkey - updates nothing here except
     * the time, which is the intended reading of "no counter".</p>
     */
    @SqlUpdate("""
            UPDATE steward_credential
            SET signature_count = GREATEST(signature_count, :signatureCount),
                last_used_at = now()
            WHERE credential_id = :credentialId
            """)
    int used(@Bind("credentialId") byte[] credentialId, @Bind("signatureCount") long signatureCount);
}
