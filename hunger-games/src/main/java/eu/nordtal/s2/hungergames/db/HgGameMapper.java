package eu.nordtal.s2.hungergames.db;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Maps one row of {@code hg_game}. */
public final class HgGameMapper implements RowMapper<HgGame> {

    @Override
    public HgGame map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new HgGame(
                rs.getObject("id", UUID.class),
                GameState.valueOf(rs.getString("state")),
                instant(rs, "started"),
                instant(rs, "ended"),
                rs.getObject("winner_member_id", UUID.class));
    }

    static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
