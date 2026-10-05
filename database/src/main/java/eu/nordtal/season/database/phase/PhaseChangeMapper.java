package eu.nordtal.season.database.phase;

import eu.nordtal.season.common.SeasonPhase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps the single row {@link PhaseDao#switchPhase(String)} returns.
 * Written out, reading the timestamp through {@link OffsetDateTime} so the JVM's zone never enters.
 */
public final class PhaseChangeMapper implements RowMapper<PhaseChange> {

    @Override
    public PhaseChange map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final @Nullable OffsetDateTime at = rs.getObject("changed", OffsetDateTime.class);
        return new PhaseChange(
                SeasonPhase.fromDatabase(rs.getString("previous_phase")),
                SeasonPhase.fromDatabase(rs.getString("current_phase")),
                at == null ? null : at.toInstant());
    }
}
