package eu.nordtal.s2.database.audit;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseJson;
import eu.nordtal.s2.messages.MessageRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Maps an {@code audit_log} row.
 * {@code occurred} goes through {@link OffsetDateTime}, which keeps the JVM's default time zone out of it.
 */
public final class AuditEntryMapper implements RowMapper<AuditEntry> {

    @Override
    public AuditEntry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final OffsetDateTime occurred =
                Objects.requireNonNull(rs.getObject("occurred", OffsetDateTime.class), "occurred");
        return new AuditEntry(
                Objects.requireNonNull(rs.getObject("id", UUID.class), "id"),
                occurred.toInstant(),
                Objects.requireNonNull(rs.getString("action"), "action"),
                Actor.of(Objects.requireNonNull(rs.getString("actor_kind"), "actor_kind"), rs.getString("actor_id")),
                DiscordId.ofNullable(rs.getString("subject")),
                rs.getObject("mc_uuid", UUID.class),
                DatabaseJson.decode(Objects.requireNonNull(rs.getString("line"), "line"), MessageRef.class));
    }
}
