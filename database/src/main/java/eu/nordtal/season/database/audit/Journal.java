package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.JournalDetail;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;

/**
 * The one way an entry is appended to {@code journal_entry}, inside a transaction its caller holds.
 * So an entry written by a writer in this module exists exactly when the change it describes does.
 */
public final class Journal {

    private Journal() {}

    /** Appends one entry and its values through {@code handle}, and returns the entry's id. */
    public static UUID write(final Handle handle, final AuditLine line) {
        Objects.requireNonNull(line, "line");
        final JournalDetail detail = line.detail();
        final UUID entry = handle.createQuery("""
                        INSERT INTO journal_entry (action, actor_kind, actor_id, subject, mc_uuid, service, run, payment)
                        VALUES (:action, :actorKind, :actorId, :subject, :mcUuid, :service, :run, :payment)
                        RETURNING id
                        """)
                .bind("action", line.action().name())
                .bind("actorKind", line.actor().kind().name())
                .bind("actorId", line.actor().id())
                .bind("subject", line.subject() == null ? null : line.subject().value())
                .bind("mcUuid", line.mcUuid())
                .bind("service", detail == null ? null : detail.service())
                .bind("run", detail == null ? null : detail.run())
                .bind("payment", detail == null ? null : detail.payment())
                .mapTo(UUID.class)
                .one();
        if (detail != null) {
            detail.insert(handle, entry);
        }
        return entry;
    }
}
