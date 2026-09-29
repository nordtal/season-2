package eu.nordtal.s2.discordbot;

import java.util.UUID;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** This module's writer of the append-only {@code audit_log}. */
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
