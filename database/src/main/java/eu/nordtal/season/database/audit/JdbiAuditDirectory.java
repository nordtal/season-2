package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.Jdbis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** Implements {@link AuditDirectory} with JDBI. */
final class JdbiAuditDirectory implements AuditDirectory {

    private final Jdbi jdbi;
    private final AuditDao dao;

    JdbiAuditDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbi = Jdbis.over(dataSource);
        this.dao = jdbi.onDemand(AuditDao.class);
    }

    @Override
    public List<AuditEntry> recent(final int limit) {
        return dao.recent(clamp(limit));
    }

    @Override
    public List<AuditEntry> search(final JournalFilter filter, final int limit) {
        return dao.search(
                any(filter.action()), any(filter.subject()), any(filter.reference()), filter.minDays(), clamp(limit));
    }

    @Override
    public Optional<AuditEntry> entry(final UUID id) {
        return dao.entry(Objects.requireNonNull(id, "id"));
    }

    @Override
    public void record(final AuditLine line) {
        jdbi.useHandle(handle -> Journal.write(handle, line));
    }

    /** Turns a blank search box into null. */
    private static @Nullable String any(final @Nullable String filter) {
        return filter == null || filter.isBlank() ? null : filter;
    }

    /** Raises the limit to 1, as {@code JdbiUpdateDirectory#recent} does. */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
