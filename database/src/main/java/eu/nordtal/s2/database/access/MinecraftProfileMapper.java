package eu.nordtal.s2.database.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps the two profile-cache columns of an {@code account_link} row. */
public final class MinecraftProfileMapper implements RowMapper<MinecraftProfile> {

    @Override
    public MinecraftProfile map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new MinecraftProfile(rs.getString("mc_name"), AccessGrantMapper.instant(rs, "mc_name_updated"));
    }
}
