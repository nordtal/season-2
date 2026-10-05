package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
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
 * Every {@code timestamptz} goes through {@link OffsetDateTime}, which keeps the JVM's default time zone out of it.
 */
public final class AccessGrantMapper implements RowMapper<AccessGrant> {

    @Override
    public AccessGrant map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new AccessGrant(
                rs.getObject("id", UUID.class),
                DiscordId.of(rs.getString("discord_id")),
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
