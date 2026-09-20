package eu.nordtal.s2.common.access;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * {@code access_request} row to {@link AccessRequest}.
 *
 * <p>Hand-written rather than a constructor mapper for the same reason {@code UpdateRequestMapper}
 * is: three of the five instants are nullable, and {@code getTimestamp} answers {@code null} for a
 * SQL NULL while {@code toInstant} would throw on it.</p>
 */
public final class AccessRequestMapper implements RowMapper<AccessRequest> {

    @Override
    public AccessRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new AccessRequest(
                rs.getLong("id"),
                AccessRequestKind.valueOf(rs.getString("kind")),
                AccessRequestStatus.valueOf(rs.getString("status")),
                rs.getString("subject"),
                rs.getString("argument"),
                AccessRequestSource.valueOf(rs.getString("source")),
                rs.getString("requested_by"),
                instant(rs.getTimestamp("requested")),
                instant(rs.getTimestamp("expires")),
                instant(rs.getTimestamp("started")),
                instant(rs.getTimestamp("finished")),
                rs.getString("result"));
    }

    private static Instant instant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
