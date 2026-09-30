package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.common.language.Locales;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps one row of {@code AccessDao#identity}. */
public final class PlayerIdentityMapper implements RowMapper<PlayerIdentity> {

    @Override
    public PlayerIdentity map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new PlayerIdentity(
                PlayerId.of(rs.getObject("mc_uuid", UUID.class)),
                DiscordId.ofNullable(rs.getString("discord_id")),
                Locales.parse(rs.getString("locale")),
                rs.getBoolean("admin"),
                rs.getBoolean("donor"),
                rs.getLong("playtime_seconds"));
    }
}
