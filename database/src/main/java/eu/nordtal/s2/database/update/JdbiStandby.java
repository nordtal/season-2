package eu.nordtal.s2.database.update;

import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** The only implementation of {@link StandbyDirectory}; it borrows the pool and owns nothing. */
final class JdbiStandby implements StandbyDirectory {

    private final StandbyDao dao;

    JdbiStandby(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(StandbyDao.class);
    }

    @Override
    public Optional<Standby> current() {
        return dao.current();
    }
}
