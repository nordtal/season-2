package eu.nordtal.season.database.metric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/** Maps a row of {@link MetricDirectory#range}. It is public because JDBI creates it by reflection. */
public final class MetricPointMapper implements RowMapper<MetricPoint> {

    @Override
    public MetricPoint map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        // OffsetDateTime keeps the JVM's default zone out.
        final OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);

        // An unknown value means the enum and the CHECK differ.
        final Resolution resolution = Resolution.valueOf(rs.getString("resolution"));

        return new MetricPoint(at.toInstant(), rs.getDouble("value"), resolution);
    }
}
