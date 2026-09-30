package eu.nordtal.s2.database.access;

import eu.nordtal.s2.database.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps an {@code access_request} row by hand, because three of its five instants are nullable. */
public final class AccessRequestMapper implements RowMapper<AccessRequest> {

    @Override
    public AccessRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new AccessRequest(
                rs.getLong("id"),
                AccessRequestKind.valueOf(rs.getString("kind")),
                AccessRequestStatus.valueOf(rs.getString("status")),
                rs.getString("subject"),
                rs.getString("argument"),
                Actor.of(rs.getString("actor_kind"), rs.getString("actor_id")),
                Objects.requireNonNull(instant(rs.getTimestamp("requested")), "requested"),
                Objects.requireNonNull(instant(rs.getTimestamp("expires")), "expires"),
                instant(rs.getTimestamp("started")),
                instant(rs.getTimestamp("finished")),
                rs.getString("result"));
    }

    private static @Nullable Instant instant(final @Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
