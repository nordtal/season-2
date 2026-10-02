package eu.nordtal.s2.steward.push;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The SQL behind {@link PushPreferences}, which is the API. */
@RegisterConstructorMapper(PushPreferences.Row.class)
interface PushPreferenceDao {

    /** Sets one switch, one row per account and type however often it is tapped. */
    @SqlUpdate("""
            INSERT INTO steward_alert_preference (discord_id, alert_type, channel, enabled, updated_at)
            VALUES (:discordId, :alertType, 'PUSH', :enabled, now())
            ON CONFLICT (discord_id, alert_type, channel) DO UPDATE SET
                enabled = excluded.enabled,
                updated_at = now()
            """)
    void set(
            @Bind("discordId") DiscordId discordId,
            @Bind("alertType") String alertType,
            @Bind("enabled") boolean enabled);

    /** One account's own switches. */
    @SqlQuery("""
            SELECT discord_id, alert_type, enabled
            FROM steward_alert_preference
            WHERE discord_id = :discordId AND channel = 'PUSH'
            """)
    List<PushPreferences.Row> forAccount(@Bind("discordId") DiscordId discordId);

    /** Every switch there is, for one sweep of {@code AlertWatch}. */
    @SqlQuery("SELECT discord_id, alert_type, enabled FROM steward_alert_preference WHERE channel = 'PUSH'")
    List<PushPreferences.Row> all();
}
