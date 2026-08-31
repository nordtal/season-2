package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.message.Locales;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Maps the single row the login query returns. See {@link AccessDao#accessState(UUID)}. */
public final class AccessStateMapper implements RowMapper<AccessState> {

    @Override
    public AccessState map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new AccessState(
                rs.getObject("mc_uuid", UUID.class),
                rs.getString("discord_id"),
                MemberState.fromDatabase(rs.getString("member_state")),
                rs.getBoolean("access_active"),
                AccessGrantMapper.instant(rs, "valid_until"),
                rs.getBoolean("donor"),
                rs.getBoolean("admin"),
                Locales.parse(rs.getString("locale")));
    }
}
