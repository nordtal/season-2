package eu.nordtal.s2.common.access;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the six profile-cache columns of a {@code discord_user} row. See {@link AccessGrantMapper}
 * for why this is written by hand rather than reached for with {@code ConstructorMapper}. */
public final class DiscordProfileMapper implements RowMapper<DiscordProfile> {

    @Override
    public DiscordProfile map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new DiscordProfile(
                rs.getString("discord_username"),
                AccessGrantMapper.instant(rs, "discord_username_updated"),
                rs.getString("discord_display_name"),
                AccessGrantMapper.instant(rs, "discord_display_name_updated"),
                rs.getString("discord_avatar_url"),
                AccessGrantMapper.instant(rs, "discord_avatar_url_updated"));
    }
}
