package eu.nordtal.s2.common.access;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the two profile-cache columns of an {@code account_link} row. See {@link AccessGrantMapper}
 * for why this is written by hand rather than reached for with {@code ConstructorMapper}. */
public final class MinecraftProfileMapper implements RowMapper<MinecraftProfile> {

    @Override
    public MinecraftProfile map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new MinecraftProfile(
                rs.getString("mc_name"),
                AccessGrantMapper.instant(rs, "mc_name_updated"));
    }
}
