package eu.nordtal.s2.common.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps an {@code access_grant} row.
 *
 * Written out rather than reached for with {@code ConstructorMapper}: that mapper matches record
 * components by parameter name, which only survives compilation with {@code -parameters}. A build
 * flag is a bad thing for a query to depend on, and the explicit version also documents that every
 * point in time comes back as {@code timestamptz} and is converted through
 * {@link OffsetDateTime} - the only reliable way to get a {@link Instant} out of the PostgreSQL
 * driver without going through the JVM's default time zone.
 */
public final class AccessGrantMapper implements RowMapper<AccessGrant> {

    @Override
    public AccessGrant map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new AccessGrant(
                rs.getObject("id", UUID.class),
                rs.getString("discord_id"),
                Objects.requireNonNull(instant(rs, "valid_from"), "valid_from"),
                Objects.requireNonNull(instant(rs, "valid_until"), "valid_until"),
                AccessSource.valueOf(rs.getString("source")),
                rs.getObject("payment_request_id", UUID.class),
                instant(rs, "revoked"),
                Objects.requireNonNull(instant(rs, "created"), "created"));
    }

    static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
