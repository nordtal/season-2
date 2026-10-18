package eu.nordtal.season.database.network;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.registration.Game;
import java.util.Objects;
import javax.sql.DataSource;

final class JdbiSnapshotDirectory implements SnapshotDirectory {

    private final SnapshotDao dao;

    JdbiSnapshotDirectory(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(SnapshotDao.class);
    }

    @Override
    public NetworkSnapshot snapshot() {
        final NetworkSnapshot snapshot = dao.snapshot(Game.HUNGER_GAMES.key());
        return snapshot == null ? NetworkSnapshot.EMPTY : snapshot;
    }
}
