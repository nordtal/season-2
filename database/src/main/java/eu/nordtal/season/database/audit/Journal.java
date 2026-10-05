package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.DatabaseJson;
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
                        INSERT INTO audit_log (action, actor_kind, actor_id, subject, mc_uuid, line)
                        VALUES (:action, :actorKind, :actorId, :subject, :mcUuid, cast(:line AS jsonb))
                        """)
                .bind("action", line.action().name())
                .bind("actorKind", line.actor().kind().name())
                .bind("actorId", line.actor().id())
                .bind("subject", line.subject() == null ? null : line.subject().value())
                .bind("mcUuid", line.mcUuid())
                .bind("line", DatabaseJson.encode(line.line()))
                .execute();
    }
}
