package eu.nordtal.s2.steward.ui.push;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

/**
 * The SQL behind {@link PushSubscriptions}. Package-private: {@code PushSubscriptions} is the API.
 *
 * @see eu.nordtal.s2.steward.ui.auth.CredentialDao the sibling this is modelled on - a table with no
 *      accounts table to point at, keyed by an identity the browser itself hands over
 */
@RegisterConstructorMapper(PushSubscriptions.Subscription.class)
interface PushSubscriptionDao {

    /**
     * One browser, subscribed or resubscribed.
     *
     * <p>{@code ON CONFLICT} rather than a lookup-then-branch: a browser that lost its subscription
     * and asked the Push API for a new one is handed a fresh endpoint by the push service, which
     * lands here as a plain insert - but a page reloaded twice in the same tab calls this with the
     * SAME endpoint and the same keys, and that is a resubscription, not a second browser. Only
     * {@code discord_id} can legitimately change under one endpoint: the same browser, signed in as
     * someone else. {@code created_at} is left alone on a conflict - it names when this endpoint was
     * first seen, not when it was last confirmed, which is what {@code last_sent_at} is for.
     */
    @SqlUpdate("""
            INSERT INTO steward_push_subscription (endpoint, discord_id, p256dh, auth, created_at)
            VALUES (:endpoint, :discordId, :p256dh, :auth, now())
            ON CONFLICT (endpoint) DO UPDATE SET
                discord_id = excluded.discord_id,
                p256dh = excluded.p256dh,
                auth = excluded.auth
            """)
    void add(@Bind("endpoint") String endpoint, @Bind("discordId") String discordId,
             @Bind("p256dh") String p256dh, @Bind("auth") String auth);

    /** Every subscription, for {@code AlertWatch} - whose it is does not matter on that path. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at
            FROM steward_push_subscription
            """)
    List<PushSubscriptions.Subscription> all();

    /** One account's own subscriptions, oldest first - the settings page's own list. */
    @SqlQuery("""
            SELECT endpoint, discord_id, p256dh, auth, created_at, last_sent_at
            FROM steward_push_subscription
            WHERE discord_id = :discordId
            ORDER BY created_at
            """)
    List<PushSubscriptions.Subscription> forAccount(@Bind("discordId") String discordId);

    /**
     * One subscription of one account, gone - the settings page's own unsubscribe.
     *
     * <p>The {@code discord_id} in the WHERE clause is the same argument {@code CredentialDao#remove}
     * makes: an endpoint is not a secret, so without this a browser could unsubscribe an endpoint it
     * merely knows the address of.</p>
     *
     * @return 1 when a subscription of that endpoint was on that account, 0 otherwise
     */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint AND discord_id = :discordId")
    int remove(@Bind("endpoint") String endpoint, @Bind("discordId") String discordId);

    /**
     * One subscription, gone - no account check.
     *
     * <p>The one caller is {@code AlertWatch}, after a push service answered 404 or 410. At that
     * point the account is not the question; the endpoint itself has told this service it no longer
     * exists, whoever it belonged to.</p>
     *
     * @return 1 when that endpoint was a row, 0 when it had already gone
     */
    @SqlUpdate("DELETE FROM steward_push_subscription WHERE endpoint = :endpoint")
    int expired(@Bind("endpoint") String endpoint);

    /** Stamps the moment a push last reached this endpoint without a 404/410 back. */
    @SqlUpdate("UPDATE steward_push_subscription SET last_sent_at = now() WHERE endpoint = :endpoint")
    void touchSent(@Bind("endpoint") String endpoint);
}
