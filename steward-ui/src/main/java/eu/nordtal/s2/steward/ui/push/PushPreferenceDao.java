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
     * {@code ON CONFLICT} rather than a lookup-then-branch: the same account tapping the same
     * switch twice is one preference, not two rows.
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
     * One query rather than one per subscription: a poll that pushes has every subscription in
     * hand already.
     */
    @SqlQuery("SELECT discord_id, alert_type, enabled FROM steward_push_preference")
    List<PushPreferences.Row> all();
}
