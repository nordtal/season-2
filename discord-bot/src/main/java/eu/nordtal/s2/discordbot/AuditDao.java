package eu.nordtal.s2.discordbot;

import java.util.UUID;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/**
 * This module's writer of {@code audit_log}.
 *
 * It is append-only by discipline: nothing in this codebase issues an {@code UPDATE} or {@code DELETE} against it.
 *
 * Not the only writer: {@code :common} 's phase DAO writes the row for a phase switch in the same statement that
 * updates {@code season_phase}, so the phase cannot be changed without the entry. A {@code /phase set} therefore
 * records itself and must not also go through {@link AdminLog#record}, or the switch appears twice.
 */
interface AuditDao {

    @SqlUpdate("""
            INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
            VALUES (:action, :actor, :subject, :mcUuid, :detail)
            """)
    void record(
            @Bind("action") String action,
            @Bind("actor") @Nullable String actor,
            @Bind("subject") @Nullable String subject,
            @Bind("mcUuid") @Nullable UUID mcUuid,
            @Bind("detail") String detail);
}
