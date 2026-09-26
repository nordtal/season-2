package eu.nordtal.s2.steward.ui.push;

import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL behind {@link PushPreferences}. Package-private: {@code PushPreferences} is the API.
 *
 * @see PushSubscriptionDao the sibling next to it, keyed by the same Discord id
 */
@RegisterConstructorMapper(PushPreferences.Row.class)
interface PushPreferenceDao {

    /**
     * One switch, set.
     *
     * <p>{@code ON CONFLICT} rather than a lookup-then-branch, for the same reason
     * {@link PushSubscriptionDao#add} gives: the same account tapping the same switch twice is one
     * preference, not two rows, and the primary key already says so.</p>
     */
    @SqlUpdate("""
            INSERT INTO steward_push_preference (discord_id, alert_type, enabled, updated_at)
            VALUES (:discordId, :alertType, :enabled, now())
            ON CONFLICT (discord_id, alert_type) DO UPDATE SET
                enabled = excluded.enabled,
                updated_at = now()
            """)
    void set(
            @Bind("discordId") String discordId, @Bind("alertType") String alertType, @Bind("enabled") boolean enabled);

    /** One account's own switches - the dialog's own list. */
    @SqlQuery("""
            SELECT discord_id, alert_type, enabled
            FROM steward_push_preference
            WHERE discord_id = :discordId
            """)
    List<PushPreferences.Row> forAccount(@Bind("discordId") String discordId);

    /**
     * Every switch there is, for one sweep of {@code AlertWatch}.
     *
     * <p>One query rather than one per subscription: a poll that pushes has every subscription in
     * hand already and would otherwise ask this table once per browser, for a table that holds at
     * most a handful of rows per account.</p>
     */
    @SqlQuery("SELECT discord_id, alert_type, enabled FROM steward_push_preference")
    List<PushPreferences.Row> all();
}
