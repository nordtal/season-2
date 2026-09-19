package eu.nordtal.s2.steward.ui.push;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Every browser's Web Push subscription (steward/98, concept §10c) - one row per endpoint, in
 * {@code steward_push_subscription}. See {@link PushSubscriptionDao} and {@code V26}.
 *
 * <p>Modelled directly on {@link eu.nordtal.s2.steward.ui.auth.Credentials}: rows only, no protocol.
 * The protocol - VAPID, the aes128gcm envelope - lives in {@code WebPushSender}, which is the one
 * class in this package that touches {@code com.interaso.webpush}.</p>
 */
public final class PushSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptions.class);

    private final PushSubscriptionDao dao;

    public PushSubscriptions(final @NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(PushSubscriptionDao.class);
    }

    /**
     * Records a browser's subscription, or refreshes it if this endpoint has already subscribed.
     *
     * <p>See {@link PushSubscriptionDao#add} for why a resubscription is an upsert rather than a
     * lookup-then-branch.</p>
     */
    public void subscribe(final @NotNull String discordId, final @NotNull String endpoint,
                          final @NotNull String p256dh, final @NotNull String auth) {
        subscribe(discordId, endpoint, p256dh, auth, null);
    }

    /**
     * The same, told what the subscribing request said about itself.
     *
     * <p>The User-Agent is turned into a name here and the string itself is never stored - see
     * {@link Devices} for what that name is and why it is not typed by a person. A request without
     * one leaves the column null, and the interface then says it does not know rather than
     * inventing something.</p>
     */
    public void subscribe(final @NotNull String discordId, final @NotNull String endpoint,
                          final @NotNull String p256dh, final @NotNull String auth,
                          final @Nullable String userAgent) {
        dao.add(endpoint, discordId, p256dh, auth, Devices.nameOf(userAgent));
        log.info("{} subscribed a browser to web push - {} subscription(s) on that account now",
                discordId, dao.forAccount(discordId).size());
    }

    /** Every subscription there is, for {@link AlertWatch} - whose account it is does not matter. */
    public @NotNull List<Subscription> all() {
        return dao.all();
    }

    /** One account's own subscriptions, oldest first - the notifications dialog's own list. */
    public @NotNull List<Subscription> of(final @NotNull String discordId) {
        return dao.forAccount(discordId);
    }

    /**
     * One subscription of this account, or null - what a test send is aimed at.
     *
     * <p>Looked up by endpoint <b>and</b> account, never by endpoint alone: see
     * {@link PushSubscriptionDao#find}.</p>
     */
    public @Nullable Subscription find(final @NotNull String discordId,
                                       final @NotNull String endpoint) {
        return dao.find(endpoint, discordId);
    }

    /**
     * Removes one subscription of one account - the settings page's own unsubscribe.
     *
     * @return whether a subscription of that endpoint was on that account
     */
    public boolean unsubscribe(final @NotNull String discordId, final @NotNull String endpoint) {
        return dao.remove(endpoint, discordId) == 1;
    }

    /**
     * Removes one subscription outright, because the push service that owns its endpoint just said
     * 404 or 410 - see {@link AlertWatch}. No account check: the endpoint itself is the report.
     */
    public void expired(final @NotNull String endpoint) {
        if (dao.expired(endpoint) == 1) {
            log.info("a push subscription answered 404/410 and was removed: {}", endpoint);
        }
    }

    /** Stamps the moment a push last reached this endpoint without a 404/410 back. */
    public void touchSent(final @NotNull String endpoint) {
        dao.touchSent(endpoint);
    }

    /** One row of {@code steward_push_subscription}. */
    public record Subscription(@NotNull String endpoint,
                               @ColumnName("discord_id") @NotNull String discordId,
                               @NotNull String p256dh,
                               @NotNull String auth,
                               @ColumnName("created_at") @NotNull Instant createdAt,
                               @ColumnName("last_sent_at") @Nullable Instant lastSentAt,
                               @Nullable String device) {
    }
}
