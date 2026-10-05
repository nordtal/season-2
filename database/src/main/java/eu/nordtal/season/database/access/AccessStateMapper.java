package eu.nordtal.season.database.access;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps the single row the login query returns, where every column but {@code mc_uuid} may be null.
 *
 * {@code member_state} stays {@code null} for an unlinked account instead of reading as {@code LEFT}.
 */
public final class AccessStateMapper implements RowMapper<AccessState> {

    @Override
    public AccessState map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final @Nullable DiscordId discordId = DiscordId.ofNullable(rs.getString("discord_id"));
        return new AccessState(
                rs.getObject("mc_uuid", UUID.class),
                discordId,
                discordId == null ? null : MemberState.fromDatabase(rs.getString("member_state")),
                rs.getBoolean("access_active"),
                AccessGrantMapper.instant(rs, "valid_until"),
                rs.getBoolean("donor"),
                rs.getBoolean("admin"),
                rs.getBoolean("pack_exempt"),
                rs.getLong("playtime_seconds"),
                Locales.parse(rs.getString("locale")),
                SeasonPhase.fromDatabase(rs.getString("phase")),
                AccessGrantMapper.instant(rs, "launch"));
    }
}
