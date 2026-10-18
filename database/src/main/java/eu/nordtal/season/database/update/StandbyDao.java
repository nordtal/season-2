package eu.nordtal.season.database.update;

import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** Reads {@code proxy_standby_state}. Only the {@code SwapDao} of the standby proxy writes it. */
interface StandbyDao {

    @SqlQuery("SELECT players, updated_at AS updated FROM proxy_standby_state WHERE only_row")
    @RegisterConstructorMapper(StandbyDirectory.Standby.class)
    Optional<StandbyDirectory.Standby> current();
}
