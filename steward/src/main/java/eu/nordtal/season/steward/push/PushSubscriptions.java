package eu.nordtal.season.steward.push;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
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

    /** Stores a subscription, or refreshes the subscription of the same endpoint. */
    public void subscribe(final DiscordId discordId, final String endpoint, final String p256dh, final String auth) {
        subscribe(discordId, endpoint, p256dh, auth, null);
    }

    /** Stores a subscription with the system and browser names parsed from the User-Agent. */
    public void subscribe(
            final DiscordId discordId,
            final String endpoint,
            final String p256dh,
            final String auth,
            final @Nullable String userAgent) {
        final Devices.Device device = Devices.of(userAgent);
        dao.add(endpoint, discordId, p256dh, auth, device.system(), device.browser());
        log.info(
                "{} subscribed a browser to web push - {} subscription(s) on that account now",
                discordId,
                dao.forAccount(discordId).size());
    }

    public List<Subscription> all() {
        return dao.all();
    }

    public List<Subscription> of(final DiscordId discordId) {
        return dao.forAccount(discordId);
    }

    /** Returns the subscription of this endpoint and account, or {@code null}. */
    public @Nullable Subscription find(final DiscordId discordId, final String endpoint) {
        return dao.find(endpoint, discordId);
    }

    /**
     * Removes one subscription of one account.
     *
     * @return whether the account had a subscription of that endpoint
     */
    public boolean unsubscribe(final DiscordId discordId, final String endpoint) {
        return dao.remove(endpoint, discordId) == 1;
    }

    /** Removes the subscription of {@code endpoint} without an account check. */
    public void expired(final String endpoint) {
        if (dao.expired(endpoint) == 1) {
            log.info("a push subscription answered 404/410 and was removed: {}", endpoint);
        }
    }

    /** Stores the time of the last push that the push service accepted. */
    public void touchSent(final String endpoint) {
        dao.touchSent(endpoint);
    }

    /** One row of {@code steward_push_subscription}. */
    public record Subscription(
            String endpoint,
            @ColumnName("discord_id") DiscordId discordId,
            String p256dh,
            String auth,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("last_sent_at") @Nullable Instant lastSentAt,
            @Nullable String system,
            @Nullable String browser) {}
}
