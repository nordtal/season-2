package eu.nordtal.s2.database.update;

import eu.nordtal.s2.common.id.Actor;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps a row of the run inbox as a run, reading every instant through {@link OffsetDateTime}. */
public final class UpdateRequestMapper implements RowMapper<UpdateRequest> {

    @Override
    public UpdateRequest map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new UpdateRequest(
                rs.getLong("id"),
                UpdateKind.valueOf(rs.getString("kind")),
                UpdateStatus.fromDatabase(rs.getString("status")),
                Actor.of(rs.getString("actor_kind"), rs.getString("actor_id")),
                Objects.requireNonNull(instant(rs, "requested"), "requested"),
                Objects.requireNonNull(instant(rs, "scheduled_for"), "scheduled_for"),
                instant(rs, "countdown_end"),
                texts(rs.getArray("moving")),
                instant(rs, "started"),
                instant(rs, "finished"),
                rs.getString("outcome"));
    }

    private static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> texts(final @Nullable Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
