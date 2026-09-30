package eu.nordtal.s2.hungergames.db;

import eu.nordtal.s2.common.id.DiscordId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps one row of {@code hg_member}. */
public final class HgMemberMapper implements RowMapper<HgMember> {

    @Override
    public HgMember map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new HgMember(
                rs.getObject("id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getObject("game_id", UUID.class),
                DiscordId.of(rs.getString("discord_id")),
                MemberState.valueOf(rs.getString("state")),
                rs.getBoolean("ready"));
    }
}
