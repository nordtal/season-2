package eu.nordtal.season.steward.auth;

import com.yubico.webauthn.CredentialRecord;
import com.yubico.webauthn.CredentialRepositoryV2;
import com.yubico.webauthn.ToPublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registered security keys, and the library's view of them.
 *
 * The user handle is the Discord id in bytes: not personal, never changing, and already the actor in {@code audit_log}.
 */
public final class Credentials implements CredentialRepositoryV2<Credentials.Key> {

    private static final Logger log = LoggerFactory.getLogger(Credentials.class);

    private final CredentialDao dao;

    public Credentials(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(CredentialDao.class);
    }

    /** The user handle of an account: its Discord id as UTF-8 bytes. */
    public static ByteArray handleOf(final DiscordId discordId) {
        return new ByteArray(discordId.value().getBytes(StandardCharsets.UTF_8));
    }

    /** The account behind a user handle, or empty if this service never issued it. */
    public static Optional<String> accountOf(final ByteArray handle) {
        final String text = new String(handle.getBytes(), StandardCharsets.UTF_8);
        // A Discord id is decimal digits; anything else is a handle from somewhere else.
        return text.isEmpty() || !text.chars().allMatch(Character::isDigit) ? Optional.empty() : Optional.of(text);
    }

    /** Every key of one account, oldest first. */
    public List<Key> of(final DiscordId discordId) {
        return dao.forAccount(discordId);
    }

    /** Whether this account has a key, and so can get past the door at all. */
    public boolean any(final DiscordId discordId) {
        return !dao.forAccount(discordId).isEmpty();
    }

    /** Records a finished registration. */
    public void add(
            final DiscordId discordId,
            final ByteArray credentialId,
            final ByteArray publicKey,
            final long signatureCount,
            final String label,
            final Set<AuthenticatorTransport> transports,
            final @Nullable Boolean backupEligible,
            final @Nullable Boolean backedUp) {
        dao.add(
                credentialId.getBytes(),
                discordId,
                publicKey.getBytes(),
                signatureCount,
                label.trim(),
                transports.isEmpty()
                        ? null
                        : transports.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(",")),
                backupEligible,
                backedUp);
        log.info(
                "registered a security key for {} - \"{}\", {} key(s) on that account now",
                discordId,
                label.trim(),
                dao.forAccount(discordId).size());
    }

    /** Removes every key of one account and answers how many; see {@link CredentialDao#forget}. */
    public int forget(final DiscordId discordId) {
        final int gone = dao.forget(Objects.requireNonNull(discordId, "discordId"));
        log.warn(
                "removed {} security key(s) of {} - that account's second factor is gone until it"
                        + " registers a new one",
                gone,
                discordId);
        return gone;
    }

    /**
     * Removes one key of one account, and answers whether it was there.
     *
     * Removing the last one is allowed: the account just reaches the setup page again.
     */
    public boolean remove(final DiscordId discordId, final ByteArray credentialId) {
        final boolean gone = dao.remove(credentialId.getBytes(), discordId) == 1;
        if (gone) {
            log.info(
                    "removed a security key of {} - {} left on that account",
                    discordId,
                    dao.forAccount(discordId).size());
        }
        return gone;
    }

    /** Renames one key of one account, and answers whether it was there. */
    public boolean rename(final DiscordId discordId, final ByteArray credentialId, final String label) {
        return dao.rename(credentialId.getBytes(), discordId, label.trim()) == 1;
    }

    /** Moves the counter forward and stamps the time, after an assertion the library accepted. */
    public void used(final ByteArray credentialId, final long signatureCount) {
        dao.used(credentialId.getBytes(), signatureCount);
    }

    @Override
    public Set<? extends ToPublicKeyCredentialDescriptor> getCredentialDescriptorsForUserHandle(
            final ByteArray userHandle) {
        return accountOf(userHandle)
                .map(DiscordId::of)
                .map(dao::forAccount)
                .map(keys -> (Set<Key>) new LinkedHashSet<>(keys))
                .orElseGet(Set::of);
    }

    @Override
    public Optional<Key> lookup(final ByteArray credentialId, final ByteArray userHandle) {
        // Both halves are checked, or a credential of one account could authenticate another's ceremony.
        return dao.byId(credentialId.getBytes())
                .filter(key -> accountOf(userHandle)
                        .map(account -> account.equals(key.discordId().value()))
                        .orElse(false));
    }

    @Override
    public boolean credentialIdExists(final ByteArray credentialId) {
        return dao.exists(credentialId.getBytes());
    }

    /**
     * One row of {@code steward_credential}, and the library's {@code CredentialRecord} at once.
     *
     * Its {@code byte[]} components make {@code equals} compare references, so nothing compares two instances.
     */
    public record Key(
            @ColumnName("credential_id") byte[] credentialId,
            @ColumnName("discord_id") DiscordId discordId,
            @ColumnName("public_key") byte[] publicKey,
            @ColumnName("signature_count") long signatureCount,
            String label,
            @Nullable String transports,
            @ColumnName("backup_eligible") @Nullable Boolean backupEligible,
            @ColumnName("backed_up") @Nullable Boolean backedUp,
            @ColumnName("created_at") Instant createdAt,
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
            // `of` accepts a transport newer than this jar; it only goes back to a browser.
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
