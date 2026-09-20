package eu.nordtal.s2.common.update;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * The only implementation of {@link StandbyDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 *
 * <p>It borrows the pool it is given and owns nothing, which is why there is no {@code close()}
 * here and none on the interface - the process that built the pool closes the pool.</p>
 */
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
