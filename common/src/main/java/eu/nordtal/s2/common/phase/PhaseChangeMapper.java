package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Maps the single row {@link PhaseDao#switchPhase(String, String, String)} returns.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper} for the same reason
 * {@code AccessGrantMapper} is: that mapper matches record components by parameter name, which only
 * survives compilation with {@code -parameters}, and a build flag is a bad thing for a query to
 * depend on. The timestamp goes through {@link OffsetDateTime}, the only reliable way to get an
 * {@link Instant} out of the PostgreSQL driver without passing through the JVM's default time zone.
 * </p>
 */
public final class PhaseChangeMapper implements RowMapper<PhaseChange> {

    @Override
    public PhaseChange map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final OffsetDateTime at = rs.getObject("changed", OffsetDateTime.class);
        return new PhaseChange(
                SeasonPhase.fromDatabase(rs.getString("previous_phase")),
                SeasonPhase.fromDatabase(rs.getString("current_phase")),
                at == null ? null : at.toInstant());
    }
}
