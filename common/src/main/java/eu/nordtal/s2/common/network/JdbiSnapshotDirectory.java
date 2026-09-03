package eu.nordtal.s2.common.network;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * The only implementation of {@link SnapshotDirectory}. Package-private, like every other
 * {@code Jdbi*Directory} here: consumers get it from the factory method on the interface and never
 * name JDBI themselves.
 */
final class JdbiSnapshotDirectory implements SnapshotDirectory {

    private final SnapshotDao dao;

    JdbiSnapshotDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SnapshotDao.class);
    }

    @Override
    public NetworkSnapshot snapshot() {
        // The query is scalar subqueries over a one-row VALUES, so it always produces exactly one
        // row. The null guard is for the impossible case only, and answers what an empty network
        // looks like rather than handing a caller a null to trip over.
        final NetworkSnapshot snapshot = dao.snapshot();
        return snapshot == null ? NetworkSnapshot.EMPTY : snapshot;
    }
}
