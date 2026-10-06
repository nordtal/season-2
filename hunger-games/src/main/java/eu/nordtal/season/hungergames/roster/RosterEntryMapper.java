package eu.nordtal.season.hungergames.roster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps one row of {@link RosterDao#ROSTER}. */
public final class RosterEntryMapper implements RowMapper<RosterEntry> {

    @Override
    public RosterEntry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new RosterEntry(
                rs.getObject("member_id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getString("team_name"),
                rs.getBoolean("ready"),
                rs.getObject("mc_uuid", UUID.class));
    }
}
