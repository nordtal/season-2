package eu.nordtal.s2.steward.ui.push;

import eu.nordtal.s2.database.Jdbis;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every browser's Web Push subscription, one row per endpoint in {@code steward_push_subscription}. */
public final class PushSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptions.class);

    private final PushSubscriptionDao dao;

    public PushSubscriptions(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(PushSubscriptionDao.class);
    }

    /** Records a browser's subscription, or refreshes it if this endpoint has already subscribed. */
    public void subscribe(final String discordId, final String endpoint, final String p256dh, final String auth) {
        subscribe(discordId, endpoint, p256dh, auth, null);
    }

    /** Records a subscription, naming the browser from its User-Agent; the raw string is never stored. */
    public void subscribe(
            final String discordId,
            final String endpoint,
            final String p256dh,
            final String auth,
            final @Nullable String userAgent) {
        dao.add(endpoint, discordId, p256dh, auth, Devices.nameOf(userAgent));
        log.info(
                "{} subscribed a browser to web push - {} subscription(s) on that account now",
                discordId,
                dao.forAccount(discordId).size());
    }

    /** Every subscription there is, for {@link AlertWatch}. */
    public List<Subscription> all() {
        return dao.all();
    }

    /** One account's own subscriptions, oldest first. */
    public List<Subscription> of(final String discordId) {
        return dao.forAccount(discordId);
    }

    /** One subscription of this account, or null; looked up by endpoint and account, never endpoint alone. */
    public @Nullable Subscription find(final String discordId, final String endpoint) {
        return dao.find(endpoint, discordId);
    }

    /**
     * Removes one subscription of one account, the settings page's unsubscribe.
     *
     * @return whether a subscription of that endpoint was on that account
     */
    public boolean unsubscribe(final String discordId, final String endpoint) {
        return dao.remove(endpoint, discordId) == 1;
    }

    /** Removes a subscription whose push service just said 404 or 410; no account check. */
    public void expired(final String endpoint) {
        if (dao.expired(endpoint) == 1) {
            log.info("a push subscription answered 404/410 and was removed: {}", endpoint);
        }
    }

    /** Stamps the moment a push last reached this endpoint without a 404/410 back. */
    public void touchSent(final String endpoint) {
        dao.touchSent(endpoint);
    }

    /** One row of {@code steward_push_subscription}. */
    public record Subscription(
            String endpoint,
            @ColumnName("discord_id") String discordId,
            String p256dh,
            String auth,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("last_sent_at") @Nullable Instant lastSentAt,
            @Nullable String device) {}
}
