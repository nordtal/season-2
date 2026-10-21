package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.JournalDetail;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jspecify.annotations.Nullable;

/** Appends an entry to {@code journal_entry} in the transaction of its caller. */
public final class Journal {

    private Journal() {}

    /** Appends the entry and its values through {@code handle} and returns the id. */
    public static UUID write(final Handle handle, final AuditLine line) {
        return write(handle, line, null);
    }

    /** Appends an entry at {@code level}, the name of an alert's level, null for an entry that is no alert. */
    public static UUID write(final Handle handle, final AuditLine line, final @Nullable String level) {
        Objects.requireNonNull(line, "line");
        final JournalDetail detail = line.detail();
        final UUID entry = handle.createQuery("""
                        INSERT INTO journal_entry (action, actor_kind, actor_id, subject, mc_uuid, service, run, payment,
                                                   level)
                        VALUES (:action, :actorKind, :actorId, :subject, :mcUuid, :service, :run, :payment, :level)
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
                .bind("level", level)
                .mapTo(UUID.class)
                .one();
        if (detail != null) {
            detail.insert(handle, entry);
        }
        return entry;
    }
}
