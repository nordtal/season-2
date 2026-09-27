package eu.nordtal.s2.common.update;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps an {@code update_request} row, reading every instant through {@link OffsetDateTime}. */
public final class UpdateRequestMapper implements RowMapper<UpdateRequest> {

    @Override
    public UpdateRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new UpdateRequest(
                rs.getLong("id"),
                UpdateKind.valueOf(rs.getString("kind")),
                UpdateStatus.fromDatabase(rs.getString("status")),
                UpdateSource.valueOf(rs.getString("source")),
                rs.getString("requested_by"),
                Objects.requireNonNull(instant(rs, "requested"), "requested"),
                Objects.requireNonNull(instant(rs, "not_before"), "not_before"),
                instant(rs, "started"),
                instant(rs, "finished"),
                rs.getString("result"));
    }

    private static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
