package eu.nordtal.s2.common.audit;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link AuditDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, so there is no {@code close()} here and none on
 * the interface.
 * </p>
 */
final class JdbiAuditDirectory implements AuditDirectory {

    private final AuditDao dao;

    JdbiAuditDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(AuditDao.class);
    }

    @Override
    public List<AuditEntry> recent(final int limit) {
        return dao.recent(clamp(limit));
    }

    @Override
    public List<AuditEntry> search(final String action, final String subject, final int limit) {
        return dao.search(any(action), any(subject), clamp(limit));
    }

    @Override
    public void record(
            final String action,
            final String actor,
            final String subject,
            final java.util.UUID mcUuid,
            final String detail) {
        Objects.requireNonNull(action, "action");
        dao.record(action, actor, subject, mcUuid, detail);
    }

    /**
     * Turns an empty search box into "no filter". The blank case is folded here rather than in the
     * SQL so that the statement has one meaning: a parameter is either a value or it is absent.
     */
    private static String any(final String filter) {
        return filter == null || filter.isBlank() ? null : filter;
    }

    /** Clamped rather than rejected, exactly as {@code JdbiUpdateDirectory#recent} clamps it. */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
