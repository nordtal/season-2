package eu.nordtal.s2.database.audit;

import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

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
     * Returns the journal filtered by action, by subject, by both or by neither, newest first.
     * Both filters are exact matches, and a {@code null} or blank one matches anything.
     *
     * @param limit how many at most; a number below 1 is clamped to 1
     */
    List<AuditEntry> search(@Nullable String action, @Nullable String subject, int limit);

    /**
     * Writes one line into the journal.
     * It is a separate statement from the action it describes, so failed bookkeeping never locks a member out.
     *
     * @param action a short upper-case constant such as {@code GRANT_ACCESS}, unconstrained by the schema
     * @param actor who did it, as a person reads it; null for the system itself
     * @param subject whom it was about, a Discord id where there is one; may be null
     * @param mcUuid the Minecraft account it concerned; may be null
     * @param detail one line of what happened; may be null
     */
    void record(
            String action,
            @Nullable String actor,
            @Nullable String subject,
            java.util.@Nullable UUID mcUuid,
            @Nullable String detail);
}
