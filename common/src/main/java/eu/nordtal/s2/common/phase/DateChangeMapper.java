package eu.nordtal.s2.common.phase;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps the single row the two date writes in {@link PhaseDao} return.
 * Written out, reading both timestamps through {@link OffsetDateTime} so the JVM's zone never enters.
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

    private static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
