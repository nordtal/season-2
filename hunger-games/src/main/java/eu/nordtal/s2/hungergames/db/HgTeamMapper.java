package eu.nordtal.s2.hungergames.db;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Maps one row of {@code hg_team}. */
public final class HgTeamMapper implements RowMapper<HgTeam> {

    @Override
    public HgTeam map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final int colourRgb = rs.getInt("colour_rgb");
        return new HgTeam(
                rs.getObject("id", UUID.class),
                rs.getObject("game_id", UUID.class),
                rs.getString("name"),
                rs.wasNull() ? null : colourRgb,
                rs.getString("colour_named"));
    }
}
