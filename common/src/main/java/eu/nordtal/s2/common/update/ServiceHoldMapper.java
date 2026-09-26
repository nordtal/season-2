package eu.nordtal.s2.common.update;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a {@code service_hold} row.
 *
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@link UpdateRequestMapper} gives: that mapper matches record components by parameter name, which
 * only survives compilation with {@code -parameters}, and a build flag is a bad thing for a query
 * to depend on. {@code since} goes through {@link OffsetDateTime} for the same reason it does
 * there - the only reliable way out of the PostgreSQL driver that does not pass through the JVM's
 * default time zone.
 */
public final class ServiceHoldMapper implements RowMapper<ServiceHold> {

    @Override
    public ServiceHold map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getLong answers 0 for NULL, and wasNull() describes the last column read, so it is asked right here.
        final long requestId = rs.getLong("request_id");
        final Long request = rs.wasNull() ? null : requestId;
        return new ServiceHold(
                rs.getString("service"),
                rs.getObject("since", OffsetDateTime.class).toInstant(),
                rs.getString("held_by"),
                request);
    }
}
