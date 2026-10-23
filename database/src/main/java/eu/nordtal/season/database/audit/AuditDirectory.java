package eu.nordtal.season.database.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads the journal, {@code journal_entry} with the values of each entry, newest first. */
public interface AuditDirectory {

    /** Returns a directory that uses {@code dataSource}. */
    static AuditDirectory using(final DataSource dataSource) {
        return new JdbiAuditDirectory(dataSource);
    }

    /**
     * Returns the newest entries.
     *
     * @param limit the maximum count, raised to 1 if lower
     */
    List<AuditEntry> recent(int limit);

    /** Returns the entries {@code ids} in no order, leaving out an id that is no entry or an alert. */
    List<AuditEntry> entries(List<UUID> ids);

    Optional<AuditEntry> entry(UUID id);

    /** Writes one entry in a statement of its own, so a failed entry never locks a member out. */
    void record(AuditLine line);
}
