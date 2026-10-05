package eu.nordtal.season.database.update;

import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** The read half of {@code proxy_standby_state}; only the standby proxy's {@code SwapDao} writes it. */
interface StandbyDao {

    /** Returns the single row, which the primary key fixes. */
    @SqlQuery("SELECT players, updated_at AS updated FROM proxy_standby_state WHERE only_row")
    @RegisterConstructorMapper(StandbyDirectory.Standby.class)
    Optional<StandbyDirectory.Standby> current();
}
