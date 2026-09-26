package eu.nordtal.s2.smp.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps a row of {@link SmpDao#openGraves()}.
 *
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason {@code AccessGrantMapper} in
 * {@code :common} gives: {@code created} comes back as {@code timestamptz}, and going through {@link OffsetDateTime}
 * is the only reliable way to get an {@link Instant} out of the PostgreSQL driver without going through the JVM's
 * default time zone.
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
        // NOT NULL DEFAULT now() in the schema (V6__smp.sql) - every row has one.
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return Objects.requireNonNull(value, column).toInstant();
    }
}
