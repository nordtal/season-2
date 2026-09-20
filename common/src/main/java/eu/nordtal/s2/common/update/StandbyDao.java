package eu.nordtal.s2.common.update;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.Optional;

/**
 * The read half of {@code proxy_standby_state}. The write half is the standby proxy's own
 * {@code SwapDao}, and the two are deliberately not the same interface: this table is written by
 * exactly one process and read by exactly one other, and putting an {@code INSERT} on the reader's
 * side would make "who writes this" a question somebody has to answer by grepping.
 */
interface StandbyDao {

    /** The single row, fixed by the primary key - see {@code V31__proxy_swap.sql}. */
    @SqlQuery("SELECT players, updated_at AS updated FROM proxy_standby_state WHERE only_row")
    @RegisterConstructorMapper(StandbyDirectory.Standby.class)
    Optional<StandbyDirectory.Standby> current();
}
