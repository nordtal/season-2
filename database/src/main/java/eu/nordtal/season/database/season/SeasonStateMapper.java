package eu.nordtal.season.database.season;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/** Maps {@code launch}, {@code maintenance} and {@code mode} of a row, under an optional column prefix. */
public final class SeasonStateMapper implements RowMapper<SeasonState> {

    @Override
    public SeasonState map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        return read(rs, "");
    }

    /** Reads the three columns named {@code prefix} plus {@code launch}, {@code maintenance} and {@code mode}. */
    public static SeasonState read(final ResultSet rs, final String prefix) throws SQLException {
        return new SeasonState(
                instant(rs, prefix + "launch"),
                rs.getBoolean(prefix + "maintenance"),
                SeasonMode.fromDatabase(rs.getString(prefix + "mode")));
    }

    static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
