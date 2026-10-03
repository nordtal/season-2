package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.common.language.Locales;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps one row of {@code AccessDao#identity}. */
public final class PlayerIdentityMapper implements RowMapper<PlayerIdentity> {

    @Override
    public PlayerIdentity map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new PlayerIdentity(
                PlayerId.of(rs.getObject("mc_uuid", UUID.class)),
                DiscordId.ofNullable(rs.getString("discord_id")),
                rs.getString("mc_name"),
                Locales.parse(rs.getString("locale")),
                zone(rs.getString("time_zone")),
                rs.getBoolean("admin"),
                rs.getBoolean("donor"),
                rs.getInt("aura"),
                rs.getLong("playtime_seconds"));
    }

    /** A zone this JVM does not know reads as none, so the network's applies rather than a login failing. */
    private static @Nullable ZoneId zone(final @Nullable String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return ZoneId.of(stored);
        } catch (final DateTimeException unknown) {
            return null;
        }
    }
}
