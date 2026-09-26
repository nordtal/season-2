package eu.nordtal.s2.common.command;

import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The three statements behind {@link AllowlistDirectory}.
 *
 * The row lives in {@code network_setting}, the proxy's own table. The notification is a second statement
 * so it is sent only when the value moved; listeners re-read in full and the poll is the guarantee.
 */
interface AllowlistDao {

    /** The key this network's command allowlist is stored under. */
    String KEY = "network.command-allowlist";

    @SqlQuery("SELECT value FROM network_setting WHERE key = :key")
    Optional<String> read(@Bind("key") String key);

    /**
     * Writes the list, and answers whether it changed anything.
     *
     * {@code WHERE network_setting.value IS DISTINCT FROM EXCLUDED.value} on the conflict branch is
     * what makes a proxy restart with an unchanged list cost one statement and wake nobody.
     *
     * @return 1 when the row was written, 0 when it already said this
     */
    @SqlUpdate("""
            INSERT INTO network_setting (key, value)
            VALUES (:key, :value)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
            WHERE network_setting.value IS DISTINCT FROM EXCLUDED.value
            """)
    int write(@Bind("key") String key, @Bind("value") String value);

    /**
     * Wakes every backend.
     *
     * The bare {@code NOTIFY} rather than {@code SELECT pg_notify(...)}, because this is the one
     * notification in the repository that is not part of a statement that returns something: a
     * {@code SELECT} would have to be issued as a query and mapped to a type, and
     * {@code pg_notify}'s type is {@code void}. There is no payload for the reason every channel
     * here gives - the listener re-reads, because a notification is never the state.
     */
    @SqlUpdate("NOTIFY nordtal_allowlist")
    void notifyChanged();
}
