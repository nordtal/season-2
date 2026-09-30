package eu.nordtal.s2.database.network;

import eu.nordtal.s2.database.Jdbis;
import java.util.Objects;
import javax.sql.DataSource;

/** The only implementation of {@link SnapshotDirectory}. */
final class JdbiSnapshotDirectory implements SnapshotDirectory {

    private final SnapshotDao dao;

    JdbiSnapshotDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(SnapshotDao.class);
    }

    @Override
    public NetworkSnapshot snapshot() {
        // The query always returns one row; the guard answers an empty network for the impossible case.
        final NetworkSnapshot snapshot = dao.snapshot();
        return snapshot == null ? NetworkSnapshot.EMPTY : snapshot;
    }
}
