package eu.nordtal.s2.steward.web;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.steward.alert.AlertRouter;
import eu.nordtal.s2.steward.auth.Sessions;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.push.PushSubscriptions;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The web push half: browser subscriptions, a test send and the VAPID public key.
 *
 * Without a VAPID keypair {@code vapidKeys} is null and the router sends no push.
 */
final class PushEndpoints {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final Function<Context, Sessions.Session> sessions;
    private final @Nullable Data data;
    private final @Nullable PushSubscriptions pushSubscriptions;
    private final @Nullable AlertRouter router;
    private final com.interaso.webpush.@Nullable VapidKeys vapidKeys;

    PushEndpoints(
            final Function<Context, Sessions.Session> sessions,
            final @Nullable Data data,
            final @Nullable PushSubscriptions pushSubscriptions,
            final @Nullable AlertRouter router,
            final com.interaso.webpush.@Nullable VapidKeys vapidKeys) {
        this.sessions = sessions;
        this.data = data;
        this.pushSubscriptions = pushSubscriptions;
        this.router = router;
        this.vapidKeys = vapidKeys;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    private PushSubscriptions pushSubscriptions() {
        return Objects.requireNonNull(pushSubscriptions, "web-push is not configured on this deployment yet");
    }

    /** {@code GET /api/web-push/public-key}: the VAPID public key, encoded for the Push API. */
    void publicKey(final Context ctx) {
        if (vapidKeys == null) {
            throw new RequestRefused(404, ANSWER.pushUnconfigured());
        }
        ctx.json(new WebPushPublicKey(
                Base64.getUrlEncoder().withoutPadding().encodeToString(vapidKeys.getApplicationServerKey())));
    }

    /** {@code POST /api/web-push/subscribe} with a browser's own {@code PushSubscription.toJSON()}. */
    void subscribe(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PushSubscriptionBody body = ctx.bodyAsClass(PushSubscriptionBody.class);
        if (body == null
                || body.endpoint == null
                || body.endpoint.isBlank()
                || body.keys == null
                || body.keys.p256dh == null
                || body.keys.p256dh.isBlank()
                || body.keys.auth == null
                || body.keys.auth.isBlank()) {
            throw new BadRequestResponse(
                    "that is not a PushSubscription - endpoint and" + " keys.p256dh/keys.auth are required");
        }
        // The User-Agent becomes a short device name; the raw string is stored nowhere.
        pushSubscriptions()
                .subscribe(
                        who.signedInDiscordId(),
                        body.endpoint,
                        body.keys.p256dh,
                        body.keys.auth,
                        ctx.header("User-Agent"));
        data().audit()
                .record(who.ownLine(
                        JournalAction.WEB_PUSH_SUBSCRIBE, TEXTS.journal().webPushSubscribe()));
        ctx.status(204);
    }

    /** {@code DELETE /api/web-push/subscribe}: only the endpoint is needed to name the row. */
    void unsubscribe(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PushSubscriptionBody body = ctx.bodyAsClass(PushSubscriptionBody.class);
        if (body == null || body.endpoint == null || body.endpoint.isBlank()) {
            throw new BadRequestResponse("no endpoint in that request");
        }
        if (!pushSubscriptions().unsubscribe(who.signedInDiscordId(), body.endpoint)) {
            throw new RequestRefused(404, ANSWER.noSubscription());
        }
        data().audit()
                .record(who.ownLine(
                        JournalAction.WEB_PUSH_UNSUBSCRIBE, TEXTS.journal().webPushUnsubscribe()));
        ctx.status(204);
    }

    /** A browser this account subscribed; {@code device} is absent without a User-Agent. */
    public record PushDevice(
            String endpoint,
            @Nullable String device,
            Instant subscribedAt,
            @Nullable Instant lastSentAt) {}

    /** {@code GET /api/web-push/public-key}: the VAPID public key, base64url. */
    public record WebPushPublicKey(String publicKey) {}

    /** {@code GET /api/web-push/devices}: every browser of this account, with its endpoint to mark "this device". */
    void devices(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        ctx.json(pushSubscriptions().of(who.signedInDiscordId()).stream()
                .map(subscription -> new PushDevice(
                        subscription.endpoint(),
                        subscription.device(),
                        subscription.createdAt(),
                        subscription.lastSentAt()))
                .toList());
    }

    /**
     * {@code POST /api/web-push/test}: one notification of one type, to one of this account's browsers.
     *
     * Scoped by {@code discordId} and endpoint, since an endpoint alone is not a secret; preferences are ignored.
     */
    void test(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        if (router == null || vapidKeys == null) {
            throw new RequestRefused(404, ANSWER.pushUnconfigured());
        }
        final PushTestBody body = ctx.bodyAsClass(PushTestBody.class);
        final AlertType type = body == null ? null : AlertType.of(body.type);
        if (body == null || body.endpoint == null || body.endpoint.isBlank() || type == null) {
            throw new BadRequestResponse(
                    "a test send is an endpoint of this account and a known" + " notification type");
        }
        final PushSubscriptions.Subscription subscription =
                pushSubscriptions().find(who.signedInDiscordId(), body.endpoint);
        if (subscription == null) {
            throw new RequestRefused(404, ANSWER.noSubscription());
        }
        final AlertRouter.Delivery delivery = router.sendSample(subscription, type);
        if (delivery == AlertRouter.Delivery.GONE) {
            // sendSample already removed the row; a silent 204 would leave the browser waiting.
            throw new RequestRefused(404, ANSWER.subscriptionGone());
        }
        if (delivery != AlertRouter.Delivery.SENT) {
            throw new RequestRefused(400, ANSWER.pushNotAccepted());
        }
        data().audit()
                .record(who.ownLine(JournalAction.WEB_PUSH_TEST, TEXTS.journal().webPushTest(type)));
        ctx.status(204);
    }

    private static final class PushTestBody {
        private @Nullable String endpoint;
        private @Nullable String type;
    }

    /** The body of both subscription routes; unsubscribe only reads {@code endpoint}. */
    private static final class PushSubscriptionBody {
        private @Nullable String endpoint;
        private @Nullable Keys keys;

        private static final class Keys {
            private @Nullable String p256dh;
            private @Nullable String auth;
        }
    }
}
