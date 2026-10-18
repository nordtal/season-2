package eu.nordtal.season.steward.push;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL of {@link PushSubscriptions}. */
@RegisterConstructorMapper(PushSubscriptions.Subscription.class)
public interface PushSubscriptionDao {

    /** Inserts a subscription, or updates the subscription of the same endpoint. */
    @SqlUpdate("""
            INSERT INTO steward_push_subscription (endpoint, discord_id, p256dh, auth, created_at, system, browser)
            VALUES (:endpoint, :discordId, :p256dh, :auth, now(), :system, :browser)
            ON CONFLICT (endpoint) DO UPDATE SET
                discord_id = excluded.discord_id,
                p256dh = excluded.p256dh,
                auth = excluded.auth,
                system = CASE WHEN excluded.system IS NULL AND excluded.browser IS NULL
                              THEN steward_push_subscription.system ELSE excluded.system END,
                browser = CASE WHEN excluded.system IS NULL AND excluded.browser IS NULL
                               THEN steward_push_subscription.browser ELSE excluded.browser END
            """)
    void add(
            @Bind("endpoint") String endpoint,
            @Bind("discordId") DiscordId discordId,
            @Bind("p256dh") String p256dh,
            @Bind("auth") String auth,
            @Bind("system") @Nullable String system,
            @Bind("browser") @Nullable String browser);

    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, system, browser
            FROM steward_push_subscription
            """)
    List<PushSubscriptions.Subscription> all();

    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, system, browser
            FROM steward_push_subscription
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<PushSubscriptions.Subscription> forAccount(@Bind("discordId") DiscordId discordId);

    /** Returns the subscription of one account by endpoint. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, system, browser
            FROM steward_push_subscription
            WHERE endpoint = :endpoint AND discord_id = :discordId
            """)
    PushSubscriptions.Subscription find(@Bind("endpoint") String endpoint, @Bind("discordId") DiscordId discordId);

    /** Deletes the subscription of one account by endpoint and returns the row count. */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint AND discord_id = :discordId")
    int remove(@Bind("endpoint") String endpoint, @Bind("discordId") DiscordId discordId);

    /** Deletes the subscription of {@code endpoint} and returns the row count. */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint")
    int expired(@Bind("endpoint") String endpoint);

    /** Sets {@code last_sent_at} of the endpoint to now. */
    @SqlUpdate("UPDATE steward_push_subscription SET last_sent_at = now() WHERE endpoint = :endpoint")
    void touchSent(@Bind("endpoint") String endpoint);
}
