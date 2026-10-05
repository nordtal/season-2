package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.Jdbis;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jspecify.annotations.Nullable;

/**
 * The only implementation of {@link AccessDirectory}, over a bare {@link Jdbi} that needs no pool wrapper.
 */
final class JdbiAccessDirectory implements AccessDirectory {

    private final Jdbi jdbi;
    private final AccessDao dao;
    private final PersonDao people;

    private final InstantSource clock;

    private JdbiAccessDirectory(final DataSource dataSource, final InstantSource clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(AccessDao.class);
        this.people = jdbi.onDemand(PersonDao.class);
    }

    static AccessDirectory borrowing(final DataSource dataSource, final InstantSource clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbiAccessDirectory(dataSource, clock);
    }

    @Override
    public Optional<UUID> linkedMinecraftAccount(final DiscordId discordId) {
        return dao.minecraftAccountOf(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public Optional<DiscordId> linkedDiscordAccount(final UUID mcUuid) {
        return dao.discordAccountOf(Objects.requireNonNull(mcUuid, "mcUuid"));
    }

    @Override
    public AccessState accessState(final UUID mcUuid) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        return dao.accessState(mcUuid).orElseGet(() -> AccessState.unlinked(mcUuid));
    }

    @Override
    public List<PlayerIdentity> identities(final Collection<PlayerId> players) {
        if (players.isEmpty()) {
            return List.of();
        }
        return dao.identities(players.stream().map(PlayerId::value).toArray(UUID[]::new));
    }

    @Override
    public Locale language(final DiscordId discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return Locales.parse(dao.languageOf(discordId).orElse(null));
    }

    @Override
    public boolean isDonor(final DiscordId discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return dao.donor(discordId).orElse(Boolean.FALSE);
    }

    @Override
    public DiscordProfile discordProfile(final DiscordId discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return dao.discordProfile(discordId).orElse(DiscordProfile.EMPTY);
    }

    @Override
    public MinecraftProfile minecraftProfile(final DiscordId discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return dao.minecraftProfile(discordId).orElse(MinecraftProfile.EMPTY);
    }

    @Override
    public List<AccessGrant> grantsOf(final DiscordId discordId) {
        return dao.grantsOf(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public List<Person> people(final int limit) {
        // At least one row, since LIMIT 0 would look like an empty database.
        return people.people(Math.max(1, limit));
    }

    @Override
    public java.util.Optional<Person> personOf(final DiscordId discordId) {
        return people.personOf(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public void ensureUser(final DiscordId discordId) {
        dao.ensureUser(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public void setMemberState(final DiscordId discordId, final MemberState memberState) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(memberState, "memberState");
        dao.setMemberState(discordId, memberState.name());
    }

    @Override
    public void setLocale(final DiscordId discordId, final @Nullable Locale locale) {
        Objects.requireNonNull(discordId, "discordId");
        dao.setLocale(discordId, locale == null ? null : Locales.tag(locale));
    }

    @Override
    public void setTimeZone(final DiscordId discordId, final @Nullable ZoneId zone) {
        Objects.requireNonNull(discordId, "discordId");
        dao.setTimeZone(discordId, zone == null ? null : zone.getId());
    }

    @Override
    public void setPlaytimeSeconds(final DiscordId discordId, final long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("play time is never negative, not " + seconds);
        }
        dao.setPlaytimeSeconds(Objects.requireNonNull(discordId, "discordId"), seconds);
    }

    @Override
    public void setDiscordProfile(
            final DiscordId discordId, final String username, final String displayName, final String avatarUrl) {
        dao.setDiscordProfile(Objects.requireNonNull(discordId, "discordId"), username, displayName, avatarUrl);
    }

    @Override
    public void clearGuildProfile(final DiscordId discordId) {
        dao.clearGuildProfile(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public boolean link(final DiscordId discordId, final UUID mcUuid) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(mcUuid, "mcUuid");

        // One transaction: a rolled-back link must not leave a discord_user row behind.
        return jdbi.inTransaction(handle -> {
            final AccessDao transactional = handle.attach(AccessDao.class);
            transactional.ensureUser(discordId);
            return transactional.link(discordId, mcUuid) == 1;
        });
    }

    @Override
    public boolean unlink(final DiscordId discordId) {
        return dao.unlink(Objects.requireNonNull(discordId, "discordId")) > 0;
    }

    @Override
    public boolean setMinecraftName(final UUID mcUuid, final String name) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        return dao.setMinecraftName(mcUuid, name) > 0;
    }

    @Override
    public AccessGrant grantAccess(
            final DiscordId discordId,
            final int days,
            final AccessSource source,
            final @Nullable UUID paymentRequestId) {
        return jdbi.inTransaction(handle -> Grants.append(handle, discordId, days, source, paymentRequestId));
    }

    @Override
    public int revokeAccess(final DiscordId discordId) {
        return dao.revokeAccess(Objects.requireNonNull(discordId, "discordId"));
    }

    /** PostgreSQL's SQLSTATE for a unique-constraint violation. */
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    /** How many times a colliding link code is retried with a new candidate. */
    private static final int MAX_LINK_CODE_ATTEMPTS = 5;

    @Override
    public LinkCode issueLinkCode(final UUID mcUuid, final Duration ttl) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got: " + ttl);
        }

        RuntimeException lastCollision = null;
        for (int attempt = 0; attempt < MAX_LINK_CODE_ATTEMPTS; attempt++) {
            final String candidate = LinkCodes.random();
            try {
                return dao.upsertLinkCode(candidate, mcUuid, clock.instant().plus(ttl));
            } catch (final UnableToExecuteStatementException exception) {
                if (!isUniqueViolation(exception)) {
                    throw exception;
                }
                // A code collision is not caught by the mc_uuid ON CONFLICT; each attempt is its own statement.
                lastCollision = exception;
            }
        }
        throw new IllegalStateException(
                "Could not allocate a unique link code for " + mcUuid + " after " + MAX_LINK_CODE_ATTEMPTS
                        + " attempts",
                lastCollision);
    }

    @Override
    public LinkRedemption redeemLinkCode(final DiscordId discordId, final String code) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(code, "code");

        return jdbi.inTransaction(handle -> {
            final AccessDao transactional = handle.attach(AccessDao.class);
            final Optional<UUID> mcUuid = transactional.mcUuidForActiveCode(code);
            if (mcUuid.isEmpty()) {
                return LinkRedemption.invalidCode();
            }

            transactional.ensureUser(discordId);
            if (transactional.link(discordId, mcUuid.get()) != 1) {
                // Either side is already linked; the code is kept so a wrong click does not burn a retry.
                return LinkRedemption.alreadyLinked();
            }

            transactional.deleteLinkCode(code);
            return LinkRedemption.linked(mcUuid.get());
        });
    }

    private static boolean isUniqueViolation(final UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState());
    }

    @Override
    public java.util.Set<String> admins() {
        return dao.adminDiscordIds();
    }

    @Override
    public java.util.Set<UUID> adminMinecraftAccounts() {
        return dao.adminMinecraftAccounts();
    }

    @Override
    public java.util.Optional<OpenPayment> openPayment(final DiscordId discordId) {
        return dao.openPayment(discordId);
    }
}
