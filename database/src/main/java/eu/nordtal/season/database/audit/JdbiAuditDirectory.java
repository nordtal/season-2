package eu.nordtal.season.database.audit;

import eu.nordtal.season.database.Jdbis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;

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
    public List<AuditEntry> entries(final List<UUID> ids) {
        return ids.isEmpty() ? List.of() : dao.entries(ids.toArray(UUID[]::new));
    }

    @Override
    public Optional<AuditEntry> entry(final UUID id) {
        return dao.entry(Objects.requireNonNull(id, "id"));
    }

    @Override
    public void record(final AuditLine line) {
        jdbi.useHandle(handle -> Journal.write(handle, line));
    }

    /** Raises the limit to 1, as {@code JdbiUpdateDirectory#recent} does. */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
