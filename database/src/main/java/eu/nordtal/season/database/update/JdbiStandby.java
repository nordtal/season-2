package eu.nordtal.season.database.update;

import eu.nordtal.season.database.Jdbis;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

final class JdbiStandby implements StandbyDirectory {

    private final StandbyDao dao;

    JdbiStandby(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(StandbyDao.class);
    }

    @Override
    public Optional<Standby> current() {
        return dao.current();
    }
}
