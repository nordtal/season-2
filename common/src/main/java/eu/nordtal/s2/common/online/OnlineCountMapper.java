package eu.nordtal.s2.common.online;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a row of {@code online_count}. Public and written out, for the reasons {@code MetricPointMapper} gives. */
public final class OnlineCountMapper implements RowMapper<OnlineCount> {

    @Override
    public OnlineCount map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // Read as an OffsetDateTime, so the JVM's default zone never enters into it.
        final OffsetDateTime updated = rs.getObject("updated", OffsetDateTime.class);
        return new OnlineCount(rs.getString("subject"), rs.getInt("players"), updated.toInstant());
    }
}
