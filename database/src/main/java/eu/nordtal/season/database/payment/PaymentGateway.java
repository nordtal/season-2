package eu.nordtal.season.database.payment;

import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Records whether steward-bunq has bunq credentials, so the bot can tell before it offers a purchase.
 * Steward writes it at every start. A missing row is {@link State#UNKNOWN}, not off.
 */
public final class PaymentGateway {

    /** What steward last found out about the credentials of steward-bunq. */
    public enum State {

        /** An API key and an account id are both set. */
        ON,

        /** Neither is set. */
        OFF,

        /** No steward has written the row yet. */
        UNKNOWN
    }

    private PaymentGateway() {}

    public static void announce(final Jdbi jdbi, final boolean on) {
        jdbi.onDemand(GatewayDao.class).upsert((on ? State.ON : State.OFF).name());
    }

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
