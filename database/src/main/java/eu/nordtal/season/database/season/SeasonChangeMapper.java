package eu.nordtal.season.database.season;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps the row of a write of {@link SeasonDao}. */
public final class SeasonChangeMapper implements RowMapper<SeasonChange> {

    @Override
    public SeasonChange map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new SeasonChange(
                SeasonStateMapper.read(rs, "previous_"),
                SeasonStateMapper.read(rs, ""),
                rs.getInt("moved_grants"),
                rs.getInt("moved_accounts"));
    }
}
