package eu.nordtal.season.database.metric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a row of a {@link MetricDirectory#range} answer.
 * Public because JDBI instantiates it reflectively; written out because {@code ConstructorMapper} needs {@code
 * -parameters}.
 */
public final class MetricPointMapper implements RowMapper<MetricPoint> {

    @Override
    public MetricPoint map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // Read as an OffsetDateTime, so the JVM's default zone never enters into it.
        final OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);

        // valueOf, not lenient: an unknown value means the enum and the CHECK have drifted apart.
        final Resolution resolution = Resolution.valueOf(rs.getString("resolution"));

        return new MetricPoint(at.toInstant(), rs.getDouble("value"), resolution);
    }
}
