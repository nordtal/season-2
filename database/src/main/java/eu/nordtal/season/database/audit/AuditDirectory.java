package eu.nordtal.season.database.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The journal: {@code journal_entry} with the values of each entry, newest first, whole or filtered.
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

    /** Returns one entry, or empty when there is none with that id. */
    Optional<AuditEntry> entry(UUID id);

    /**
     * Writes one entry into the journal.
     * It is a separate statement from the action it describes, so failed bookkeeping never locks a member out.
     */
    void record(AuditLine line);
}
