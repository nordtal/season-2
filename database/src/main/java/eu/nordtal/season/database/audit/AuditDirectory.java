package eu.nordtal.season.database.audit;

import java.util.List;
import javax.sql.DataSource;

/**
 * The journal: {@code audit_log}, newest first, whole or filtered.
 * It borrows the pool it is given, and every method is a database round trip.
 */
public interface AuditDirectory {

    /** Returns a directory over the pool the caller already reads access or the roster through. */
    static AuditDirectory using(final DataSource dataSource) {
        return new JdbiAuditDirectory(dataSource);
    }

    /**
     * Returns the journal, newest first.
     *
     * @param limit how many at most; a number below 1 is clamped to 1
     */
    List<AuditEntry> recent(int limit);

    /**
     * Returns the journal narrowed by every part of {@code filter} that is set, newest first.
     *
     * @param limit how many at most; a number below 1 is clamped to 1
     */
    List<AuditEntry> search(JournalFilter filter, int limit);

    /**
     * Writes one line into the journal.
     * It is a separate statement from the action it describes, so failed bookkeeping never locks a member out.
     */
    void record(AuditLine line);
}
