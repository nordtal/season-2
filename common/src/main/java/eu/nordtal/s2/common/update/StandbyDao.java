package eu.nordtal.s2.common.update;

import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** The read half of {@code proxy_standby_state}; only the standby proxy's {@code SwapDao} writes it. */
interface StandbyDao {

    /** The single row, fixed by the primary key - see {@code V31__proxy_swap.sql}. */
    @SqlQuery("SELECT players, updated_at AS updated FROM proxy_standby_state WHERE only_row")
    @RegisterConstructorMapper(StandbyDirectory.Standby.class)
    Optional<StandbyDirectory.Standby> current();
}
