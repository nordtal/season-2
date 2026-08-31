package eu.nordtal.s2.hungergames.db;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Maps one row of {@link HungerGamesDao#roster(UUID)}. */
public final class RosterEntryMapper implements RowMapper<RosterEntry> {

    @Override
    public RosterEntry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final int colourRgb = rs.getInt("colour_rgb");
        return new RosterEntry(
                rs.getObject("member_id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getString("team_name"),
                rs.wasNull() ? null : colourRgb,
                rs.getString("colour_named"),
                rs.getString("discord_id"),
                MemberState.valueOf(rs.getString("state")),
                rs.getBoolean("ready"),
                rs.getObject("mc_uuid", UUID.class));
    }
}
