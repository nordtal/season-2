package eu.nordtal.s2.discordbot.discord;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

/**
 * The only writer of {@code audit_log}. The table is append-only by discipline - nothing in this
 * codebase issues an {@code UPDATE} or {@code DELETE} against it - and this interface is that
 * discipline made visible.
 */
interface AuditDao {

    @SqlUpdate("""
            INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
            VALUES (:action, :actor, :subject, :mcUuid, :detail)
            """)
    void record(@Bind("action") String action,
                @Bind("actor") String actor,
                @Bind("subject") String subject,
                @Bind("mcUuid") UUID mcUuid,
                @Bind("detail") String detail);
}
