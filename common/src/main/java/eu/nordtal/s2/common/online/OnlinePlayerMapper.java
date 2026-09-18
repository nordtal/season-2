package eu.nordtal.s2.common.online;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a row of {@code online_player}.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, and public rather than
 * package-private, for the two reasons {@link OnlineCountMapper} already gives: name-based matching
 * needs {@code -parameters} to survive compilation, and {@code @RegisterRowMapper} instantiates this
 * reflectively from JDBI's own unnamed module.
 * </p>
 */
public final class OnlinePlayerMapper implements RowMapper<OnlinePlayer> {

    @Override
    public OnlinePlayer map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getObject with the target type on both of these: pgjdbc hands back a real UUID and an
        // instant with its offset attached, so neither the JVM's default zone nor a string parse
        // enters into it - see OnlineRosterDao.
        final UUID uuid = rs.getObject("mc_uuid", UUID.class);
        final OffsetDateTime updated = rs.getObject("updated", OffsetDateTime.class);
        // A NULL subject is a value here, not a missing one: the proxy has this player and no
        // backend does yet. getString already answers null for it; nothing is defaulted.
        return new OnlinePlayer(uuid, rs.getString("mc_name"), rs.getString("subject"),
                updated.toInstant());
    }
}
