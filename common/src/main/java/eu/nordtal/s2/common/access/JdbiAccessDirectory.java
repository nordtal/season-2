package eu.nordtal.s2.common.access;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.common.message.Locales;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link AccessDirectory}. Package-private: consumers get it from the
 * factory methods on the interface and never name JDBI or HikariCP themselves.
 * <p>
 * This is deliberately not jcore's {@code Database}. It does the same three lines of setup, and
 * doing them here is what keeps a Paper plugin from having to shade jcore's whole dependency
 * block - Flyway, the config system, commons-*, gson, snakeyaml - for a connection pool. Migration
 * is not duplicated: the schema is owned and applied by the bot.
 * </p>
 */
final class JdbiAccessDirectory implements AccessDirectory {

    /** Small on purpose: the proxy's login path is the only hot caller and it is one query. */
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 5;

    private final Jdbi jdbi;
    private final AccessDao dao;
    private final HikariDataSource ownedPool;

    private JdbiAccessDirectory(final DataSource dataSource, final HikariDataSource ownedPool) {
        this.jdbi = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
        this.dao = jdbi.onDemand(AccessDao.class);
        this.ownedPool = ownedPool;
    }

    static AccessDirectory borrowing(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new JdbiAccessDirectory(dataSource, null);
    }

    static AccessDirectory owning(final String jdbcUrl, final String username, final String password) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("jdbcUrl must start with \"jdbc:\", got: " + jdbcUrl);
        }

        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("nordtal-access");
        config.setMaximumPoolSize(DEFAULT_MAXIMUM_POOL_SIZE);

        // HikariCP's default initializationFailTimeout makes this fail here, at startup, rather
        // than on the first login attempt.
        final HikariDataSource pool = new HikariDataSource(config);
        try {
            return new JdbiAccessDirectory(pool, pool);
        } catch (final RuntimeException exception) {
            pool.close();
            throw exception;
        }
    }

    // ---------------------------------------------------------------- reads

    @Override
    public Optional<UUID> linkedMinecraftAccount(final String discordId) {
        return dao.minecraftAccountOf(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public Optional<String> linkedDiscordAccount(final UUID mcUuid) {
        return dao.discordAccountOf(Objects.requireNonNull(mcUuid, "mcUuid"));
    }

    @Override
    public AccessState accessState(final UUID mcUuid) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        return dao.accessState(mcUuid).orElseGet(() -> AccessState.unlinked(mcUuid));
    }

    @Override
    public Locale locale(final UUID mcUuid) {
        if (mcUuid == null) {
            return Locales.DEFAULT;
        }
        try {
            return Locales.parse(dao.localeOf(mcUuid).orElse(null));
        } catch (final RuntimeException exception) {
            // Documented never to throw: a disconnect screen still has to render when the
            // database is unreachable, and English is always a correct answer here.
            return Locales.DEFAULT;
        }
    }

    @Override
    public boolean isDonor(final String discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return dao.donor(discordId).orElse(Boolean.FALSE);
    }

    @Override
    public List<AccessGrant> grantsOf(final String discordId) {
        return dao.grantsOf(Objects.requireNonNull(discordId, "discordId"));
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void ensureUser(final String discordId) {
        dao.ensureUser(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public void setMemberState(final String discordId, final MemberState memberState) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(memberState, "memberState");
        dao.setMemberState(discordId, memberState.name());
    }

    @Override
    public void setLocale(final String discordId, final Locale locale) {
        Objects.requireNonNull(discordId, "discordId");
        dao.setLocale(discordId, Locales.tag(locale));
    }

    @Override
    public void setDonor(final String discordId, final boolean donor) {
        dao.setDonor(Objects.requireNonNull(discordId, "discordId"), donor);
    }

    @Override
    public boolean link(final String discordId, final UUID mcUuid) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(mcUuid, "mcUuid");

        // ensureUser and the insert share one transaction: without it a rolled-back link would
        // leave a discord_user row behind for an account the bot has never actually seen.
        return jdbi.inTransaction(handle -> {
            final AccessDao transactional = handle.attach(AccessDao.class);
            transactional.ensureUser(discordId);
            return transactional.link(discordId, mcUuid) == 1;
        });
    }

    @Override
    public boolean unlink(final String discordId) {
        return dao.unlink(Objects.requireNonNull(discordId, "discordId")) > 0;
    }

    @Override
    public AccessGrant grantAccess(final String discordId,
                                   final int days,
                                   final AccessSource source,
                                   final UUID paymentRequestId) {
        Objects.requireNonNull(discordId, "discordId");
        Objects.requireNonNull(source, "source");
        if (days <= 0) {
            throw new IllegalArgumentException("days must be positive, got: " + days);
        }

        return jdbi.inTransaction(handle -> {
            final AccessDao transactional = handle.attach(AccessDao.class);
            transactional.ensureUser(discordId);
            return transactional.grantAccess(discordId, days, source.name(), paymentRequestId);
        });
    }

    @Override
    public int revokeAccess(final String discordId) {
        return dao.revokeAccess(Objects.requireNonNull(discordId, "discordId"));
    }

    @Override
    public void close() {
        if (ownedPool != null) {
            ownedPool.close();
        }
    }
}
