package eu.nordtal.s2.common.metric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a row of a {@link MetricDirectory#range} answer.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code UpdateRequestMapper} and {@code AccessGrantMapper} both give: that mapper matches record
 * components by parameter name, which only survives compilation with {@code -parameters}, and a
 * build that loses the flag loses the mapping at runtime rather than at compile time.
 * </p>
 * <p>
 * Public, like {@code UpdateRequestMapper}, and for a reason that is not a matter of taste:
 * {@code @RegisterRowMapper} makes JDBI instantiate it reflectively from its own unnamed module, so
 * a package-private mapper fails at the first query with {@code IllegalAccessException} - at
 * runtime, from inside a dynamic proxy, with the SQL nowhere in the message. Nothing else about it
 * is API.
 * </p>
 */
public final class MetricPointMapper implements RowMapper<MetricPoint> {

    @Override
    public MetricPoint map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // getObject(OffsetDateTime.class) and not getTimestamp: pgjdbc hands back the stored
        // instant with its offset attached, so the JVM's default zone never enters into it. The
        // same reason the arguments go out as OffsetDateTime - see MetricDao.
        final OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);

        // Resolution.valueOf and not a lenient lookup: the column is behind a CHECK that allows two
        // strings, so a third one here means the migration and the enum have drifted apart, and the
        // exception is the only thing that would say so.
        final Resolution resolution = Resolution.valueOf(rs.getString("resolution"));

        return new MetricPoint(at.toInstant(), rs.getDouble("value"), resolution);
    }
}
