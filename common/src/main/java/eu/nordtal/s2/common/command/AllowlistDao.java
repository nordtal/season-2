package eu.nordtal.s2.common.command;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

/**
 * The three statements behind {@link AllowlistDirectory}. Package-private: the interface is the API.
 *
 * <h2>Why {@code bot_setting} and not a table of its own</h2>
 * Because this is one row of text, and a table for it would be a migration. What it costs is a
 * name: {@code bot_setting} was introduced in V3 for "values the bot decides once and must never
 * decide again", and this row is neither the bot's nor decided once - the proxy writes it from
 * {@code network.yml} on every start. The mismatch is written down here rather than left for a
 * reader to trip over, and moving the row into a table of its own is one migration and one constant
 * away.
 *
 * <h2>Why the notification is a second statement and not a CTE</h2>
 * Every other {@code pg_notify} in this repository rides inside the statement that writes, so that
 * a notification can only exist for a row that committed. That rule is about a <em>state change</em>
 * somebody could act on wrongly. Here there is nothing to act on wrongly: the value is idempotent,
 * every listener re-reads it in full, and the poll is the guarantee. Two statements buy a
 * conditional notification - one that is sent only when the value actually moved - which a CTE
 * cannot express without relying on the planner to evaluate a select-list function nobody reads.
 */
interface AllowlistDao {

    /** The key this network's command allowlist is stored under. */
    String KEY = "network.command-allowlist";

    @SqlQuery("SELECT value FROM bot_setting WHERE key = :key")
    Optional<String> read(@Bind("key") String key);

    /**
     * Writes the list, and answers whether it changed anything.
     *
     * <p>{@code WHERE bot_setting.value IS DISTINCT FROM EXCLUDED.value} on the conflict branch is
     * what makes a proxy restart with an unchanged list cost one statement and wake nobody.</p>
     *
     * @return 1 when the row was written, 0 when it already said this
     */
    @SqlUpdate("""
            INSERT INTO bot_setting (key, value)
            VALUES (:key, :value)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
            WHERE bot_setting.value IS DISTINCT FROM EXCLUDED.value
            """)
    int write(@Bind("key") String key, @Bind("value") String value);

    /**
     * Wakes every backend.
     *
     * <p>The bare {@code NOTIFY} rather than {@code SELECT pg_notify(...)}, because this is the one
     * notification in the repository that is not part of a statement that returns something: a
     * {@code SELECT} would have to be issued as a query and mapped to a type, and
     * {@code pg_notify}'s type is {@code void}. There is no payload for the reason every channel
     * here gives - the listener re-reads, because a notification is never the state.</p>
     */
    @SqlUpdate("NOTIFY nordtal_allowlist")
    void notifyChanged();
}
