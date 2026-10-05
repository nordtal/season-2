package eu.nordtal.season.smp.grave;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a row of {@link GraveDao#openGraves()}.
 *
 * Reads {@code created} through {@link OffsetDateTime}, the only way to avoid the JVM's default time zone.
 */
public final class GraveRowMapper implements RowMapper<GraveRow> {

    @Override
    public GraveRow map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new GraveRow(
                rs.getObject("id", UUID.class),
                rs.getString("ownerId"),
                rs.getObject("ownerUuid", UUID.class),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getBytes("contents"),
                rs.getInt("experience"),
                instant(rs, "created"));
    }

    static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return Objects.requireNonNull(value, column).toInstant();
    }
}
