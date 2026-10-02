package eu.nordtal.s2.database.audit;

import eu.nordtal.s2.common.json.Json;
import java.util.Objects;
import org.jdbi.v3.core.Handle;

/**
 * The one statement that appends to {@code audit_log}, inside a transaction its caller holds.
 * So a line written by a writer in this module exists exactly when the change it describes does.
 */
public final class Journal {

    private Journal() {}

    /** Appends one line through {@code handle}, inside whatever transaction the caller holds. */
    public static void write(final Handle handle, final AuditLine line) {
        Objects.requireNonNull(line, "line");
        handle.createUpdate("""
                        INSERT INTO audit_log (action, actor_kind, actor_id, subject, mc_uuid, facts)
                        VALUES (:action, :actorKind, :actorId, :subject, :mcUuid, cast(:facts AS jsonb))
                        """)
                .bind("action", line.action())
                .bind("actorKind", line.actor().kind().name())
                .bind("actorId", line.actor().id())
                .bind("subject", line.subject() == null ? null : line.subject().value())
                .bind("mcUuid", line.mcUuid())
                .bind("facts", Json.encode(line.facts()))
                .execute();
    }
}
