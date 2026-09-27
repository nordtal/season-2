package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.push.AlertType;
import eu.nordtal.s2.steward.ui.push.AlertWatch;
import eu.nordtal.s2.steward.ui.push.PushPreferences;
import eu.nordtal.s2.steward.ui.push.PushSubscriptions;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The web push half: browser subscriptions, notification preferences and the VAPID public key.
 *
 * {@code vapidKeys} and {@code alertWatch} are null together on a deployment with no VAPID
 * keypair yet - "not configured", not "not started".
 */
final class PushEndpoints {

    private final Function<Context, Sessions.Session> sessions;
    private final @Nullable Data data;
    private final @Nullable PushSubscriptions pushSubscriptions;
    private final @Nullable PushPreferences pushPreferences;
    private final @Nullable AlertWatch alertWatch;
    private final com.interaso.webpush.@Nullable VapidKeys vapidKeys;

    PushEndpoints(
            final Function<Context, Sessions.Session> sessions,
            final @Nullable Data data,
            final @Nullable PushSubscriptions pushSubscriptions,
            final @Nullable PushPreferences pushPreferences,
            final @Nullable AlertWatch alertWatch,
            final com.interaso.webpush.@Nullable VapidKeys vapidKeys) {
        this.sessions = sessions;
        this.data = data;
        this.pushSubscriptions = pushSubscriptions;
        this.pushPreferences = pushPreferences;
        this.alertWatch = alertWatch;
        this.vapidKeys = vapidKeys;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    private PushSubscriptions pushSubscriptions() {
        return Objects.requireNonNull(pushSubscriptions, "web-push is not configured on this deployment yet");
    }

    private PushPreferences pushPreferences() {
        return Objects.requireNonNull(pushPreferences, "web-push is not configured on this deployment yet");
    }

    /**
     * {@code GET /api/web-push/public-key} - the VAPID public key, re-encoded for the Push API.
     *
     * {@link #vapidKeys} is parsed once at startup so this raw-EC-point conversion happens once,
     * not on every call.
     */
    void publicKey(final Context ctx) {
        if (vapidKeys == null) {
            throw new NotFoundResponse(
                    "web-push is not configured on this deployment yet - see" + " web-push in steward-ui.yml");
        }
        ctx.json(Map.of(
                "publicKey",
                Base64.getUrlEncoder().withoutPadding().encodeToString(vapidKeys.getApplicationServerKey())));
    }

    /** {@code POST /api/web-push/subscribe} - the body is a browser's own {@code PushSubscription.toJSON()}. */
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
                .record(
                        "WEB_PUSH_SUBSCRIBE",
                        who.signedInDiscordId(),
                        who.signedInDiscordId(),
                        null,
                        "subscribed a browser to the traffic light's web push");
        ctx.status(204);
    }

    /** {@code DELETE /api/web-push/subscribe} - only the endpoint is needed to name the row. */
    void unsubscribe(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PushSubscriptionBody body = ctx.bodyAsClass(PushSubscriptionBody.class);
        if (body == null || body.endpoint == null || body.endpoint.isBlank()) {
            throw new BadRequestResponse("no endpoint in that request");
        }
        if (!pushSubscriptions().unsubscribe(who.signedInDiscordId(), body.endpoint)) {
            throw new NotFoundResponse("this account has no web push subscription of that endpoint");
        }
        data().audit()
                .record(
                        "WEB_PUSH_UNSUBSCRIBE",
                        who.signedInDiscordId(),
                        who.signedInDiscordId(),
                        null,
                        "unsubscribed a browser from the traffic light's web push");
        ctx.status(204);
    }

    /**
     * {@code GET /api/web-push/devices} - every browser of this account, named.
     *
     * The endpoint is sent along so the browser can match it against its own subscription and mark
     * one row as "this device"; it is not a secret.
     */
    void devices(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final List<Map<String, Object>> listed = new ArrayList<>();
        for (final PushSubscriptions.Subscription subscription :
                pushSubscriptions().of(who.signedInDiscordId())) {
            final Map<String, Object> one = new LinkedHashMap<>();
            one.put("endpoint", subscription.endpoint());
            // Absent rather than a placeholder; the interface writes its own words for "unknown".
            if (subscription.device() != null) {
                one.put("device", subscription.device());
            }
            one.put("subscribedAt", subscription.createdAt().toString());
            if (subscription.lastSentAt() != null) {
                one.put("lastSentAt", subscription.lastSentAt().toString());
            }
            listed.add(one);
        }
        ctx.json(listed);
    }

    /**
     * {@code GET /api/web-push/preferences} - which kinds of alert this account wants.
     *
     * Every type is answered with the effective value: an account that never opened the dialog
     * gets {@link AlertType}'s own defaults, not an empty object.
     */
    void preferences(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final Map<String, Boolean> answer = new LinkedHashMap<>();
        pushPreferences().of(who.signedInDiscordId()).forEach((type, enabled) -> answer.put(type.key(), enabled));
        ctx.json(answer);
    }

    /** {@code PUT /api/web-push/preferences} - one switch, for the account that is signed in. */
    void setPreference(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PushPreferenceBody body = ctx.bodyAsClass(PushPreferenceBody.class);
        final AlertType type = body == null ? null : AlertType.of(body.type);
        if (type == null || body.enabled == null) {
            throw new BadRequestResponse("a notification preference is a known type and an enabled" + " flag");
        }
        pushPreferences().set(who.signedInDiscordId(), type, body.enabled);
        ctx.json(Map.of("type", type.key(), "enabled", body.enabled));
    }

    /**
     * {@code POST /api/web-push/test} - one notification of one type, to one of this account's browsers.
     *
     * Scoped by {@code discordId} AND endpoint, since an endpoint alone is not a secret. The
     * preference switch is deliberately not consulted - see {@link AlertWatch#sendSample}.
     */
    void test(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        if (alertWatch == null) {
            throw new NotFoundResponse(
                    "web-push is not configured on this deployment yet - see" + " web-push in steward-ui.yml");
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
            throw new NotFoundResponse("this account has no web push subscription of that endpoint");
        }
        final AlertWatch.Delivery delivery = alertWatch.sendSample(subscription, type);
        if (delivery == AlertWatch.Delivery.GONE) {
            // sendSample already removed the row; a silent 204 would leave the browser waiting.
            throw new NotFoundResponse(
                    "that browser's subscription no longer exists and has been" + " removed - subscribe again on it");
        }
        if (delivery == AlertWatch.Delivery.FAILED) {
            throw new BadRequestResponse(
                    "the push service did not accept it - see the log of" + " steward-ui for what it said");
        }
        data().audit()
                .record(
                        "WEB_PUSH_TEST",
                        who.signedInDiscordId(),
                        who.signedInDiscordId(),
                        null,
                        "sent a test " + type.key() + " notification to one of its own browsers");
        ctx.status(204);
    }

    /** The body of {@code PUT /api/web-push/preferences}. */
    private static final class PushPreferenceBody {
        private @Nullable String type;
        /** Boxed: a missing field is a bad request here, not a false. */
        private @Nullable Boolean enabled;
    }

    /** The body of {@code POST /api/web-push/test}. */
    private static final class PushTestBody {
        private @Nullable String endpoint;
        private @Nullable String type;
    }

    /** The one shape both web-push routes read; unsubscribe only needs its {@code endpoint}. */
    private static final class PushSubscriptionBody {
        private @Nullable String endpoint;
        private @Nullable Keys keys;

        private static final class Keys {
            private @Nullable String p256dh;
            private @Nullable String auth;
        }
    }
}
