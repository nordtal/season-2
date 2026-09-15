package eu.nordtal.s2.common.audit;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps an {@code audit_log} row.
 * <p>
 * Written out rather than reached for with {@code ConstructorMapper}, for the reason
 * {@code AccessGrantMapper} gives: that mapper matches record components by parameter name, which
 * only survives compilation with {@code -parameters}, and this repository does not set it. It also
 * documents that {@code occurred} comes back as {@code timestamptz} and is converted through
 * {@link OffsetDateTime} - the only way to get an {@link Instant} out of the PostgreSQL driver
 * without going through the JVM's default time zone.
 * </p>
 */
public final class AuditEntryMapper implements RowMapper<AuditEntry> {

    @Override
    public AuditEntry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final OffsetDateTime occurred = rs.getObject("occurred", OffsetDateTime.class);
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                occurred == null ? null : occurred.toInstant(),
                rs.getString("action"),
                rs.getString("actor"),
                rs.getString("subject"),
                rs.getObject("mc_uuid", UUID.class),
                rs.getString("detail"));
    }
}
