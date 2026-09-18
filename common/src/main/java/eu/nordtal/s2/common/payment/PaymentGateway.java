package eu.nordtal.s2.common.payment;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

/**
 * Whether the process that talks to bunq has a bunq to talk to - written by steward-worker at every
 * start, read by anybody who needs to say so.
 *
 * <h2>Why this exists at all</h2>
 * steward/109 moved the bunq credentials out of {@code discord-bot} and into {@code steward-worker},
 * and steward/101 named the failure that move makes possible: the two variables are deliberately
 * <b>not</b> {@code :?} in {@code compose.yml}, because a season without a bank account is a valid
 * season. So an environment file that still carries the old names produces a stack where every
 * container is healthy, every log is quiet, and <b>no payment is ever noticed again</b>.
 *
 * <p>steward-worker says which of the two it is in one line at startup. That line is the answer, and
 * this row is how it reaches the process that <em>offers the button</em>: the bot cannot see
 * steward-worker's configuration, has no bunq key of its own any more, and would otherwise report
 * "everything is configured" while nothing anybody buys can ever be paid for.</p>
 *
 * <h2>Why it is safe to read the way it is</h2>
 * {@code discord-bot} waits for steward-worker's health marker before it starts (compose
 * {@code depends_on: service_healthy}), and the marker is written after the worker has loaded its
 * config - so by the time the bot reads this, the value is this deployment's own and not the
 * previous boot's. {@link #state(Jdbi)} still answers {@link State#UNKNOWN} for a missing row rather
 * than guessing, because "no worker has ever started against this database" is a real state and is
 * not the same sentence as "bunq is off".
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

        /**
         * No steward-worker has written the row yet. Not the same as {@link #OFF}: a deployment
         * whose worker is older than steward/109, or has not started, looks exactly like this, and
         * telling somebody "bunq is off" on that evidence would be an invention.
         */
        UNKNOWN
    }

    private PaymentGateway() {
    }

    /**
     * Records what steward-worker found. Overwrites: this is the current state of a running
     * container, not a decision taken once - which is the whole difference between this key and
     * {@code payment.watermark} sitting next to it in the same table.
     *
     * @param jdbi the database
     * @param on   whether the bunq credentials are both set
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
            // Somebody edited the row by hand. UNKNOWN is the honest answer - it says "look at the
            // worker's own start line", which is where the truth is either way.
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
