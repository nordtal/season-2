package eu.nordtal.s2.common.network;

import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** The only implementation of {@link SnapshotDirectory}. */
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
        // The query always returns one row; the null guard answers an empty network for the impossible case.
        final NetworkSnapshot snapshot = dao.snapshot();
        return snapshot == null ? NetworkSnapshot.EMPTY : snapshot;
    }
}
