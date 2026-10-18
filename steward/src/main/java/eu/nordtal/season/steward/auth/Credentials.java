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

/** The user handle is the Discord id as bytes. */
public final class Credentials implements CredentialRepositoryV2<Credentials.Key> {

    private static final Logger log = LoggerFactory.getLogger(Credentials.class);

    private final CredentialDao dao;

    public Credentials(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(CredentialDao.class);
    }

    public static ByteArray handleOf(final DiscordId discordId) {
        return new ByteArray(discordId.value().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the Discord id in {@code handle}, or empty when the handle is not decimal digits. */
    public static Optional<String> accountOf(final ByteArray handle) {
        final String text = new String(handle.getBytes(), StandardCharsets.UTF_8);
        return text.isEmpty() || !text.chars().allMatch(Character::isDigit) ? Optional.empty() : Optional.of(text);
    }

    public List<Key> of(final DiscordId discordId) {
        return dao.forAccount(discordId);
    }

    public boolean any(final DiscordId discordId) {
        return !dao.forAccount(discordId).isEmpty();
    }

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
                transports.stream().map(AuthenticatorTransport::getId).toList(),
                backupEligible,
                backedUp);
        log.info(
                "registered a security key for {} - \"{}\", {} key(s) on that account now",
                discordId,
                label.trim(),
                dao.forAccount(discordId).size());
    }

    public int forget(final DiscordId discordId) {
        final int gone = dao.forget(Objects.requireNonNull(discordId, "discordId"));
        log.warn(
                "removed {} security key(s) of {} - that account's second factor is gone until it"
                        + " registers a new one",
                gone,
                discordId);
        return gone;
    }

    /** Deleting the last key is allowed. */
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

    public boolean rename(final DiscordId discordId, final ByteArray credentialId, final String label) {
        return dao.rename(credentialId.getBytes(), discordId, label.trim()) == 1;
    }

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
        // Both ids must match, else a credential of one account could answer the ceremony of another.
        return dao.byId(credentialId.getBytes())
                .filter(key -> accountOf(userHandle)
                        .map(account -> account.equals(key.discordId().value()))
                        .orElse(false));
    }

    @Override
    public boolean credentialIdExists(final ByteArray credentialId) {
        return dao.exists(credentialId.getBytes());
    }

    /** Its {@code byte[]} components make {@code equals} compare references. */
    public record Key(
            @ColumnName("credential_id") byte[] credentialId,
            @ColumnName("discord_id") DiscordId discordId,
            @ColumnName("public_key") byte[] publicKey,
            @ColumnName("signature_count") long signatureCount,
            String label,
            List<String> transports,
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
            if (transports.isEmpty()) {
                return Optional.empty();
            }
            // AuthenticatorTransport.of accepts transports unknown to this library version.
            return Optional.of(transports.stream()
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
