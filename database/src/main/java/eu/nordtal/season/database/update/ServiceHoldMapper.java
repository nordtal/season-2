package eu.nordtal.season.database.update;

import eu.nordtal.season.common.id.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a {@code service_hold} row. */
public final class ServiceHoldMapper implements RowMapper<ServiceHold> {

    @Override
    public ServiceHold map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getLong answers 0 for NULL, and wasNull() describes the last column read.
        final long requestId = rs.getLong("request_id");
        final Long request = rs.wasNull() ? null : requestId;
        return new ServiceHold(
                rs.getString("service"),
                rs.getObject("since", OffsetDateTime.class).toInstant(),
                Actor.of(rs.getString("actor_kind"), rs.getString("actor_id")),
                request);
    }
}
