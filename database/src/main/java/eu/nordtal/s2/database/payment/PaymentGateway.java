package eu.nordtal.s2.database.payment;

import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Records whether steward-bunq has bunq credentials, so the bot can tell before offering a purchase.
 * Written at every steward start; a missing row is {@link State#UNKNOWN}, not off.
 */
public final class PaymentGateway {

    /** What steward last found out about steward-bunq's credentials. */
    public enum State {

        /** An API key and an account id are both set; the poll runs and a tab can be made. */
        ON,

        /** Neither is set. The stack is healthy and nothing can be bought, deliberately. */
        OFF,

        /** No steward has written the row yet, which does not mean bunq is off. */
        UNKNOWN
    }

    private PaymentGateway() {}

    /** Records what steward found, overwriting the previous state. */
    public static void announce(final Jdbi jdbi, final boolean on) {
        jdbi.onDemand(GatewayDao.class).upsert((on ? State.ON : State.OFF).name());
    }

    /** Returns what steward last said, or {@link State#UNKNOWN} when nothing has. */
    public static State state(final Jdbi jdbi) {
        return jdbi.onDemand(GatewayDao.class).state().map(State::valueOf).orElse(State.UNKNOWN);
    }

    interface GatewayDao {

        @SqlUpdate("""
                INSERT INTO payment_gateway (state)
                VALUES (:state)
                ON CONFLICT (id) DO UPDATE SET state = excluded.state
                """)
        void upsert(@Bind("state") String state);

        @SqlQuery("SELECT state FROM payment_gateway WHERE state IS NOT NULL")
        Optional<String> state();
    }
}
