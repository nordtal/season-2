package eu.nordtal.s2.database.audit;

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
                        INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
                        VALUES (:action, :actor, :subject, :mcUuid, :detail)
                        """)
                .bind("action", line.action())
                .bind("actor", line.actor())
                .bind("subject", line.subject())
                .bind("mcUuid", line.mcUuid())
                .bind("detail", line.detail())
                .execute();
    }
}
