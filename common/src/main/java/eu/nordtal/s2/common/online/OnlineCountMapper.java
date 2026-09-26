package eu.nordtal.s2.common.online;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a row of {@code online_count}.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code MetricPointMapper} gives: that mapper matches record components by parameter name, which
 * only survives compilation with {@code -parameters}, and a build that loses the flag loses the
 * mapping at runtime instead of at compile time.
 * </p>
 * <p>
 * Public, like {@code MetricPointMapper}, because {@code @RegisterRowMapper} instantiates it
 * reflectively from JDBI's own unnamed module - a package-private mapper fails the first query with
 * an {@code IllegalAccessException} raised from inside a dynamic proxy, with the SQL nowhere in the
 * message. Nothing else about it is API.
 * </p>
 */
public final class OnlineCountMapper implements RowMapper<OnlineCount> {

    @Override
    public OnlineCount map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getObject(OffsetDateTime.class) and not getTimestamp: pgjdbc hands back the stored instant
        // with its offset attached, so the JVM's default zone never enters into it - see OnlineDao.
        final OffsetDateTime updated = rs.getObject("updated", OffsetDateTime.class);
        return new OnlineCount(rs.getString("subject"), rs.getInt("players"), updated.toInstant());
    }
}
