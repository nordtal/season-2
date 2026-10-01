package eu.nordtal.s2.steward.push;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL behind {@link PushSubscriptions}, which is the API. */
@RegisterConstructorMapper(PushSubscriptions.Subscription.class)
interface PushSubscriptionDao {

    /** Subscribes a browser, or resubscribes it by endpoint and leaves {@code created_at} alone. */
    @SqlUpdate("""
            INSERT INTO steward_push_subscription (endpoint, discord_id, p256dh, auth, created_at, device)
            VALUES (:endpoint, :discordId, :p256dh, :auth, now(), :device)
            ON CONFLICT (endpoint) DO UPDATE SET
                discord_id = excluded.discord_id,
                p256dh = excluded.p256dh,
                auth = excluded.auth,
                device = coalesce(excluded.device, steward_push_subscription.device)
            """)
    void add(
            @Bind("endpoint") String endpoint,
            @Bind("discordId") DiscordId discordId,
            @Bind("p256dh") String p256dh,
            @Bind("auth") String auth,
            @Bind("device") @Nullable String device);

    /** Every subscription, for {@code AlertWatch}. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, device
            FROM steward_push_subscription
            """)
    List<PushSubscriptions.Subscription> all();

    /** One account's own subscriptions, oldest first. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, device
            FROM steward_push_subscription
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<PushSubscriptions.Subscription> forAccount(@Bind("discordId") DiscordId discordId);

    /** One subscription of one account, by endpoint; the account is checked since an endpoint is not a secret. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at, device
            FROM steward_push_subscription
            WHERE endpoint = :endpoint AND discord_id = :discordId
            """)
    PushSubscriptions.Subscription find(@Bind("endpoint") String endpoint, @Bind("discordId") DiscordId discordId);

    /**
     * Removes one subscription of one account and answers the row count; the account is checked, as in {@link #find}.
     */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint AND discord_id = :discordId")
    int remove(@Bind("endpoint") String endpoint, @Bind("discordId") DiscordId discordId);

    /** Removes one subscription whose push service reported it gone, and answers the row count. */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint")
    int expired(@Bind("endpoint") String endpoint);

    /** Stamps the moment a push last reached this endpoint without a 404/410 back. */
    @SqlUpdate("UPDATE steward_push_subscription SET last_sent_at = now() WHERE endpoint = :endpoint")
    void touchSent(@Bind("endpoint") String endpoint);
}
