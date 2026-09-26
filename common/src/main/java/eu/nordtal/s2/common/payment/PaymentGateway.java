package eu.nordtal.s2.common.payment;

import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Records whether steward-worker has bunq credentials, so the bot can tell before offering a purchase.
 *
 * Written at every worker start; the bot starts after the worker is healthy, so it reads this boot's value.
 * A missing row is {@link State#UNKNOWN}, not off.
 */
public final class PaymentGateway {

    /** The {@code bot_setting} key. Values are {@link State#ON} and {@link State#OFF}, verbatim. */
    private static final String KEY = "payment.gateway";

    /** What steward-worker last said about its bunq credentials. */
    public enum State {

        /** An API key and an account id are both set; the poll runs and a tab can be made. */
        ON,

        /** Neither is set. The stack is healthy and nothing can be bought - deliberately. */
        OFF,

        /** No steward-worker has written the row yet, which does not mean bunq is off. */
        UNKNOWN
    }

    private PaymentGateway() {}

    /**
     * Records what steward-worker found, overwriting the previous state.
     *
     * @param on whether the bunq credentials are both set
     */
    public static void announce(final Jdbi jdbi, final boolean on) {
        jdbi.onDemand(GatewayDao.class).upsert(KEY, (on ? State.ON : State.OFF).name());
    }

    /**
     * @param jdbi the database
     * @return what steward-worker last said, or {@link State#UNKNOWN} when nothing has
     */
    public static State state(final Jdbi jdbi) {
        final Optional<String> stored = jdbi.onDemand(GatewayDao.class).value(KEY);
        if (stored.isEmpty()) {
            return State.UNKNOWN;
        }
        try {
            return State.valueOf(stored.get().trim());
        } catch (final IllegalArgumentException unreadable) {
            // Edited by hand; the worker's start line is the truth.
            return State.UNKNOWN;
        }
    }

    /** {@code bot_setting}, for the one key here that is rewritten on every start. */
    interface GatewayDao {

        @SqlUpdate("""
                INSERT INTO bot_setting (key, value)
                VALUES (:key, :value)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value
                """)
        void upsert(@Bind("key") String key, @Bind("value") String value);

        @SqlQuery("SELECT value FROM bot_setting WHERE key = :key")
        Optional<String> value(@Bind("key") String key);
    }
}
