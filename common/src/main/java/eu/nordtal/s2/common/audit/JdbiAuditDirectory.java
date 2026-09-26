package eu.nordtal.s2.common.audit;

import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The only implementation of {@link AuditDirectory}.
 *
 * It borrows the pool it is given and owns nothing, so there is no {@code close()}.
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
    public List<AuditEntry> search(final @Nullable String action, final @Nullable String subject, final int limit) {
        return dao.search(any(action), any(subject), clamp(limit));
    }

    @Override
    public void record(
            final String action,
            final @Nullable String actor,
            final @Nullable String subject,
            final java.util.@Nullable UUID mcUuid,
            final @Nullable String detail) {
        Objects.requireNonNull(action, "action");
        dao.record(action, actor, subject, mcUuid, detail);
    }

    /** Turns an empty search box into no filter, so a statement parameter is a value or absent. */
    private static @Nullable String any(final @Nullable String filter) {
        return filter == null || filter.isBlank() ? null : filter;
    }

    /** Clamped rather than rejected, exactly as {@code JdbiUpdateDirectory#recent} clamps it. */
    private static int clamp(final int limit) {
        return Math.max(1, limit);
    }
}
