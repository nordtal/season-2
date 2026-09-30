package eu.nordtal.s2.database.command;

import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The three statements behind {@link AllowlistDirectory}.
 * The notification is a second statement, so it is sent only when the value moved.
 */
interface AllowlistDao {

    /** The key this network's command allowlist is stored under. */
    String KEY = "network.command-allowlist";

    @SqlQuery("SELECT value FROM network_setting WHERE key = :key")
    Optional<String> read(@Bind("key") String key);

    /**
     * Writes the list, and answers whether it changed anything; an unchanged list wakes nobody.
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

    /** Wakes every backend with a bare {@code NOTIFY}, since this statement returns nothing. */
    @SqlUpdate("NOTIFY nordtal_allowlist")
    void notifyChanged();
}
