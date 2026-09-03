package eu.nordtal.s2.common.phase;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Maps the single row the two date writes in {@link PhaseDao} return.
 * <p>
 * Written out by hand for the reason {@link PhaseChangeMapper} gives: {@code ConstructorMapper}
 * matches record components by parameter name, which only survives compilation with
 * {@code -parameters}. Both timestamps go through {@link OffsetDateTime}, the only reliable way to
 * get an {@link Instant} out of the PostgreSQL driver without passing through the JVM's default
 * time zone - which for these two columns would be the whole bug this feature exists to prevent.
 * </p>
 */
public final class DateChangeMapper implements RowMapper<DateChange> {

    @Override
    public DateChange map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return new DateChange(
                instant(rs, "previous_at"),
                instant(rs, "current_at"),
                rs.getInt("moved_grants"),
                rs.getInt("moved_accounts"));
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
