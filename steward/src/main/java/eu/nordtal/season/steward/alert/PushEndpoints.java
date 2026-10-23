package eu.nordtal.season.steward.alert;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.push.PushSubscriptions;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Serves the web push routes: browser subscriptions, a test send and the VAPID public key. */
public final class PushEndpoints {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final Function<Context, Sessions.Session> sessions;
    private final PushSubscriptions pushSubscriptions;
    private final AlertRouter router;
    private final com.interaso.webpush.@Nullable VapidKeys vapidKeys;

    public PushEndpoints(
            final Function<Context, Sessions.Session> sessions,
            final PushSubscriptions pushSubscriptions,
            final AlertRouter router,
            final com.interaso.webpush.@Nullable VapidKeys vapidKeys) {
        this.sessions = sessions;
        this.pushSubscriptions = pushSubscriptions;
        this.router = router;
        this.vapidKeys = vapidKeys;
    }

    public void publicKey(final Context ctx) {
        if (vapidKeys == null) {
            throw new RequestRefused(404, ANSWER.pushUnconfigured());
        }
        ctx.json(new WebPushPublicKey(
                Base64.getUrlEncoder().withoutPadding().encodeToString(vapidKeys.getApplicationServerKey())));
    }

    public void subscribe(final Context ctx) {
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
                    "that is not a PushSubscription - endpoint and keys.p256dh/keys.auth are required");
        }
        // The User-Agent becomes a system and a browser; the raw string is stored nowhere.
        pushSubscriptions.subscribe(
                who.signedInDiscordId(), body.endpoint, body.keys.p256dh, body.keys.auth, ctx.header("User-Agent"));
        Journaling.line(ctx, who.signedInDiscordId(), null);
        ctx.status(204);
    }

    public void unsubscribe(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PushSubscriptionBody body = ctx.bodyAsClass(PushSubscriptionBody.class);
        if (body == null || body.endpoint == null || body.endpoint.isBlank()) {
            throw new BadRequestResponse("no endpoint in that request");
        }
        if (!pushSubscriptions.unsubscribe(who.signedInDiscordId(), body.endpoint)) {
            throw new RequestRefused(404, ANSWER.noSubscription());
        }
        Journaling.line(ctx, who.signedInDiscordId(), null);
        ctx.status(204);
    }

    /** Describes a subscribed browser. {@code system} and {@code browser} are null when not recognised. */
    public record PushDevice(
            String endpoint,
            @Nullable String system,
            @Nullable String browser,
            Instant subscribedAt,
            @Nullable Instant lastSentAt) {}

    public record WebPushPublicKey(String publicKey) {}

    public void devices(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        ctx.json(pushSubscriptions.of(who.signedInDiscordId()).stream()
                .map(subscription -> new PushDevice(
                        subscription.endpoint(),
                        subscription.system(),
                        subscription.browser(),
                        subscription.createdAt(),
                        subscription.lastSentAt()))
                .toList());
    }

    /** Sends one notification of one type to one browser of the account. Preferences are ignored. */
    public void test(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        if (vapidKeys == null) {
            throw new RequestRefused(404, ANSWER.pushUnconfigured());
        }
        final PushTestBody body = ctx.bodyAsClass(PushTestBody.class);
        final AlertType type = body == null ? null : AlertType.of(body.type);
        if (body == null || body.endpoint == null || body.endpoint.isBlank() || type == null) {
            throw new BadRequestResponse("a test send is an endpoint of this account and a known notification type");
        }
        final PushSubscriptions.Subscription subscription =
                pushSubscriptions.find(who.signedInDiscordId(), body.endpoint);
        if (subscription == null) {
            throw new RequestRefused(404, ANSWER.noSubscription());
        }
        final AlertRouter.Delivery delivery = router.sendSample(subscription, type);
        if (delivery == AlertRouter.Delivery.GONE) {
            // sendSample has already removed the row.
            throw new RequestRefused(404, ANSWER.subscriptionGone());
        }
        if (delivery != AlertRouter.Delivery.SENT) {
            throw new RequestRefused(400, ANSWER.pushNotAccepted());
        }
        Journaling.line(ctx, who.signedInDiscordId(), new JournalDetail.PushTested(type));
        ctx.status(204);
    }

    private static final class PushTestBody {
        private @Nullable String endpoint;
        private @Nullable String type;
    }

    private static final class PushSubscriptionBody {
        private @Nullable String endpoint;
        private @Nullable Keys keys;

        private static final class Keys {
            private @Nullable String p256dh;
            private @Nullable String auth;
        }
    }
}
