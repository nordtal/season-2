package eu.nordtal.s2.steward.ui.auth;

import com.yubico.webauthn.CredentialRecord;
import com.yubico.webauthn.CredentialRepositoryV2;
import com.yubico.webauthn.ToPublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The registered security keys, and the library's view of them.
 *
 * <h2>The user handle is the Discord id, in bytes</h2>
 * WebAuthn calls the thing an authenticator stores beside a credential the <em>user handle</em>,
 * and the specification asks that it not be anything personal - because a discoverable credential
 * hands it back to any site that asks, before anybody has authenticated. A Discord id is a
 * snowflake: it is not a name, not an address, and it is already the actor written into every
 * {@code audit_log} row. It is also the only identifier this stack has that never changes, which
 * is the other half of the requirement - a handle that moved would orphan every key bound to it.
 *
 * <p>The consequence is deliberate and worth knowing: {@code lookup} and
 * {@code getCredentialDescriptorsForUserHandle} are a direct query on {@code discord_id}, with no
 * table of accounts in between. There is no account table in this schema, and inventing one so
 * that a random handle could point at it would be a table whose only row content is a second name
 * for something already named.</p>
 *
 * <h2>What this class is NOT</h2>
 * It holds no Jackson and no ceremony. Verifying a signature, producing a challenge and reading
 * the browser's answer all live in {@link WebAuthn}, which is the one class in this repository
 * that speaks Jackson. This one is rows.
 */
public final class Credentials implements CredentialRepositoryV2<Credentials.Key> {

    private static final Logger log = LoggerFactory.getLogger(Credentials.class);

    private final CredentialDao dao;

    public Credentials(final @NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(CredentialDao.class);
    }

    /** The user handle of an account: its Discord id as UTF-8 bytes. See the class note. */
    public static @NotNull ByteArray handleOf(final @NotNull String discordId) {
        return new ByteArray(discordId.getBytes(StandardCharsets.UTF_8));
    }

    /** The account behind a user handle, or empty if it is not one this service ever issued. */
    public static @NotNull Optional<String> accountOf(final @NotNull ByteArray handle) {
        final String text = new String(handle.getBytes(), StandardCharsets.UTF_8);
        // A Discord id is decimal digits. Anything else is a handle from somewhere else entirely,
        // and turning it into a query would be turning a stranger's bytes into a WHERE clause.
        return text.isEmpty() || !text.chars().allMatch(Character::isDigit)
                ? Optional.empty()
                : Optional.of(text);
    }

    /** Every key of one account, oldest first. */
    public @NotNull List<Key> of(final @NotNull String discordId) {
        return dao.forAccount(discordId);
    }

    /** Whether this account can get past the door at all. */
    public boolean any(final @NotNull String discordId) {
        return !dao.forAccount(discordId).isEmpty();
    }

    /** Records a finished registration. */
    public void add(final @NotNull String discordId, final @NotNull ByteArray credentialId,
                    final @NotNull ByteArray publicKey, final long signatureCount,
                    final @NotNull String label, final @NotNull Set<AuthenticatorTransport> transports,
                    final @Nullable Boolean backupEligible, final @Nullable Boolean backedUp) {
        dao.add(credentialId.getBytes(), discordId, publicKey.getBytes(), signatureCount,
                label.trim(),
                transports.isEmpty() ? null
                        : transports.stream().map(AuthenticatorTransport::getId)
                                .collect(Collectors.joining(",")),
                backupEligible, backedUp);
        log.info("registered a security key for {} - \"{}\", {} key(s) on that account now",
                discordId, label.trim(), dao.forAccount(discordId).size());
    }

    /**
     * Removes every key of one account. See {@link CredentialDao#forget}.
     *
     * @return how many were removed
     */
    public int forget(final @NotNull String discordId) {
        final int gone = dao.forget(Objects.requireNonNull(discordId, "discordId"));
        log.warn("removed {} security key(s) of {} - that account's second factor is gone until it"
                + " registers a new one", gone, discordId);
        return gone;
    }

    /**
     * Removes one key of one account.
     *
     * <p><b>Removing the last one is allowed, and that is the decision.</b> It does not lock
     * anybody out: an account with no key reaches the setup page and registers a new one, which is
     * exactly the state a fresh deployment is in. Refusing it would be refusing the one sensible
     * thing to do with a key you have just thrown away - and it would still not protect anybody,
     * because removing a key already requires holding one.</p>
     *
     * @return whether a key of that id was on that account
     */
    public boolean remove(final @NotNull String discordId, final @NotNull ByteArray credentialId) {
        final boolean gone = dao.remove(credentialId.getBytes(), discordId) == 1;
        if (gone) {
            log.info("removed a security key of {} - {} left on that account", discordId,
                    dao.forAccount(discordId).size());
        }
        return gone;
    }

    /**
     * Renames one key of one account.
     *
     * @return whether a key of that id was on that account
     */
    public boolean rename(final @NotNull String discordId, final @NotNull ByteArray credentialId,
                          final @NotNull String label) {
        return dao.rename(credentialId.getBytes(), discordId, label.trim()) == 1;
    }

    /** Moves the counter forward and stamps the time, after an assertion the library accepted. */
    public void used(final @NotNull ByteArray credentialId, final long signatureCount) {
        dao.used(credentialId.getBytes(), signatureCount);
    }

    // --- what the library asks -----------------------------------------------------------------

    @Override
    public Set<? extends ToPublicKeyCredentialDescriptor> getCredentialDescriptorsForUserHandle(
            final ByteArray userHandle) {
        return accountOf(userHandle)
                .map(dao::forAccount)
                .map(keys -> (Set<Key>) new LinkedHashSet<>(keys))
                .orElseGet(Set::of);
    }

    @Override
    public Optional<Key> lookup(final ByteArray credentialId, final ByteArray userHandle) {
        // BOTH halves are checked, and the second one matters: without it, a credential belonging
        // to one account would authenticate a ceremony started for another. The library passes
        // what the browser sent, which is not a thing this end gets to trust.
        return dao.byId(credentialId.getBytes())
                .filter(key -> accountOf(userHandle)
                        .map(account -> account.equals(key.discordId()))
                        .orElse(false));
    }

    @Override
    public boolean credentialIdExists(final ByteArray credentialId) {
        return dao.exists(credentialId.getBytes());
    }

    /**
     * One row of {@code steward_credential}, and the library's {@code CredentialRecord} at once.
     *
     * <p>Two roles in one type rather than a record and an adapter, because the adapter would have
     * exactly the fields of the record and one more place to forget a column.</p>
     *
     * <p><b>It holds arrays, so it does not compare the way a record usually does.</b> {@code
     * equals} on a record with a {@code byte[]} component compares the reference, not the bytes -
     * two reads of the same row are unequal. Nothing here compares them (the sets above are built
     * from one query each, and identity is the credential id), but a future {@code contains} would
     * be quietly wrong, which is why it is written down rather than left to be discovered.</p>
     */
    public record Key(@ColumnName("credential_id") byte @NotNull [] credentialId,
                      @ColumnName("discord_id") @NotNull String discordId,
                      @ColumnName("public_key") byte @NotNull [] publicKey,
                      @ColumnName("signature_count") long signatureCount,
                      @NotNull String label,
                      @Nullable String transports,
                      @ColumnName("backup_eligible") @Nullable Boolean backupEligible,
                      @ColumnName("backed_up") @Nullable Boolean backedUp,
                      @ColumnName("created_at") @NotNull Instant createdAt,
                      @ColumnName("last_used_at") @Nullable Instant lastUsedAt)
            implements CredentialRecord {

        @Override
        public ByteArray getCredentialId() {
            return new ByteArray(credentialId);
        }

        @Override
        public ByteArray getUserHandle() {
            return handleOf(discordId);
        }

        @Override
        public ByteArray getPublicKeyCose() {
            return new ByteArray(publicKey);
        }

        @Override
        public long getSignatureCount() {
            return signatureCount;
        }

        @Override
        public Optional<Set<AuthenticatorTransport>> getTransports() {
            if (transports == null || transports.isBlank()) {
                return Optional.empty();
            }
            // `of` accepts anything, including a transport invented after this jar was built -
            // which is the right behaviour for a value that came out of a browser and is only
            // ever handed back to one.
            return Optional.of(Arrays.stream(transports.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .map(AuthenticatorTransport::of)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }

        @Override
        public Optional<Boolean> isBackupEligible() {
            return Optional.ofNullable(backupEligible);
        }

        @Override
        public Optional<Boolean> isBackedUp() {
            return Optional.ofNullable(backedUp);
        }
    }
}
