package eu.nordtal.season.database.online;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a row of {@code online_player}. It is public for the reason {@code MetricPointMapper} gives. */
public final class OnlinePlayerMapper implements RowMapper<OnlinePlayer> {

    @Override
    public OnlinePlayer map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final UUID uuid = rs.getObject("mc_uuid", UUID.class);
        final OffsetDateTime updated = rs.getObject("updated", OffsetDateTime.class);
        // A NULL subject is a value: no backend has the player yet.
        return new OnlinePlayer(uuid, rs.getString("mc_name"), rs.getString("subject"), updated.toInstant());
    }
}
