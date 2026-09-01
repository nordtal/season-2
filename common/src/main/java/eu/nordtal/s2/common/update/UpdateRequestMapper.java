package eu.nordtal.s2.common.update;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Maps an {@code update_request} row.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code AccessGrantMapper} and {@code PhaseChangeMapper} both give: that mapper matches record
 * components by parameter name, which only survives compilation with {@code -parameters}, and a
 * build flag is a bad thing for a query to depend on. Every instant goes through
 * {@link OffsetDateTime}, the only reliable way out of the PostgreSQL driver that does not pass
 * through the JVM's default time zone.
 * </p>
 */
public final class UpdateRequestMapper implements RowMapper<UpdateRequest> {

    @Override
    public UpdateRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new UpdateRequest(
                rs.getLong("id"),
                UpdateKind.valueOf(rs.getString("kind")),
                UpdateStatus.fromDatabase(rs.getString("status")),
                UpdateSource.valueOf(rs.getString("source")),
                rs.getString("requested_by"),
                instant(rs, "requested"),
                instant(rs, "not_before"),
                instant(rs, "started"),
                instant(rs, "finished"),
                rs.getString("result"));
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
