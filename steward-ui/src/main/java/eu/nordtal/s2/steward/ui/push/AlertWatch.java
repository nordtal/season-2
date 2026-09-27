package eu.nordtal.s2.steward.ui.push;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the traffic light and pushes every subscribed browser that asked for that kind of news.
 *
 * This side polls rather than steward-worker pushing: only this process holds a browser's
 * subscription and the VAPID private key that signs every send. A poll compares each
 * {@link AlertType} against the same type in the previous poll, so a browser only hears about a
 * type its account left switched on. The first poll never sends: there is nothing yet to compare
 * it against.
 */
public final class AlertWatch {

    private static final Logger log = LoggerFactory.getLogger(AlertWatch.class);
    private static final Gson GSON = new Gson();

    private final AlertLevelSource source;
    private final PushSubscriptions subscriptions;
    private final PushPreferences preferences;
    private final PushSender sender;
    private final Alerts.Thresholds thresholds;

    /** Null until the first successful poll - see the class note on why that poll never sends. */
    private volatile @Nullable Map<AlertType, Alerts.Alert> last;

    AlertWatch(
            final AlertLevelSource source,
            final PushSubscriptions subscriptions,
            final PushPreferences preferences,
            final PushSender sender,
            final Alerts.Thresholds thresholds) {
        this.source = Objects.requireNonNull(source, "source");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** The public constructor: the real worker, the real database, the real push protocol. */
    public AlertWatch(
            final eu.nordtal.s2.steward.ui.internal.InternalClient worker,
            final PushSubscriptions subscriptions,
            final PushPreferences preferences,
            final String vapidSubject,
            final com.interaso.webpush.VapidKeys vapidKeys,
            final int diskPercent,
            final int memoryPercent,
            final int backupAgeHours) {
        this(
                new WorkerAlertLevelSource(worker),
                subscriptions,
                preferences,
                new WebPushSender(vapidSubject, vapidKeys),
                new Alerts.Thresholds(diskPercent, memoryPercent, backupAgeHours));
    }

    /**
     * One cycle: read the state, and tell every subscribed browser about each type that moved.
     *
     * A failure to reach the worker is logged and swallowed, since a background poll that throws
     * stops running forever on a {@code ScheduledExecutorService}, silently.
     */
    public void poll() {
        final AlertReading reading;
        try {
            reading = source.current();
        } catch (final RuntimeException unreachable) {
            log.warn("could not read the traffic light this cycle: {}", unreachable.getMessage());
            return;
        }
        final Map<AlertType, Alerts.Alert> current = Alerts.of(reading, thresholds);
        final Map<AlertType, Alerts.Alert> previous = last;
        last = current;
        if (previous == null) {
            return;
        }

        // Read once for the whole cycle, and only once something has moved.
        Map<String, Map<AlertType, Boolean>> chosen = null;
        List<PushSubscriptions.Subscription> browsers = null;
        for (final AlertType type : AlertType.values()) {
            final Alerts.Alert before = previous.get(type);
            final Alerts.Alert now = current.get(type);
            if (Objects.equals(before, now)) {
                continue;
            }
            if (chosen == null) {
                chosen = preferences.all();
                browsers = subscriptions.all();
            }
            log.info(
                    "{} moved from {} to {} - pushing to every subscription that wants it",
                    type.key(),
                    describe(before),
                    describe(now));
            push(type, payloadOf(type, now, before), Objects.requireNonNull(chosen), Objects.requireNonNull(browsers));
        }
    }

    /**
     * One notification of one type, to one browser, on request - the dialog's own test send.
     *
     * Deliberately ignores {@link PushPreferences}: the switch only governs what arrives unbidden.
     *
     * @return what the push service said, so that the route can report a dead subscription rather
     *         than a silent success
     */
    public Delivery sendSample(final PushSubscriptions.Subscription subscription, final AlertType type) {
        final PushSender.Result result = send(subscription, payloadOf(type, sample(type), null));
        if (result == PushSender.Result.EXPIRED) {
            subscriptions.expired(subscription.endpoint());
            return Delivery.GONE;
        }
        return result == PushSender.Result.SENT ? Delivery.SENT : Delivery.FAILED;
    }

    /**
     * What one deliberate send came back as - {@link PushSender.Result} said outside this package.
     *
     * {@code PushSender} is package-private, the seam a test stands in front of; this is the one
     * enum that crosses the package line so a route can tell "it went" from "that browser is gone".
     */
    public enum Delivery {
        /** The push service took it. */
        SENT,
        /** The push service says that subscription no longer exists; the row has been removed. */
        GONE,
        /** Neither - a network failure, or a status the library did not expect. */
        FAILED
    }

    private void push(
            final AlertType type,
            final String payload,
            final Map<String, Map<AlertType, Boolean>> chosen,
            final List<PushSubscriptions.Subscription> browsers) {
        for (final PushSubscriptions.Subscription subscription : browsers) {
            if (!PushPreferences.enabled(chosen.get(subscription.discordId()), type)) {
                continue;
            }
            final PushSender.Result result = send(subscription, payload);
            switch (result) {
                case SENT -> subscriptions.touchSent(subscription.endpoint());
                // A dead subscription (404/410) has to be removed, not just detected, or it keeps failing forever.
                case EXPIRED -> subscriptions.expired(subscription.endpoint());
                case FAILED -> log.warn("push to {} failed (not a 404/410)", subscription.endpoint());
            }
        }
    }

    private PushSender.Result send(final PushSubscriptions.Subscription subscription, final String payload) {
        try {
            return sender.send(subscription, payload);
        } catch (final RuntimeException failure) {
            log.warn("push to {} failed: {}", subscription.endpoint(), failure.getMessage());
            return PushSender.Result.FAILED;
        }
    }

    /** A real-looking alert of that type rather than the word "test", to show what it looks like. */
    private static Alerts.Alert sample(final AlertType type) {
        return switch (type) {
            case SERVICE -> new Alerts.Alert(type, "down", "smp", "/services/smp");
            case BACKUP -> new Alerts.Alert(type, "down", "backups", "/operations/backups");
            case DISK -> new Alerts.Alert(type, "warn", "disk", "/");
            case MEMORY -> new Alerts.Alert(type, "warn", "memory", "/");
            case DRIFT -> new Alerts.Alert(type, "warn", "registry", "/operations/updates");
        };
    }

    /**
     * The push body.
     *
     * A null {@code alert} is a type that stopped being true and is still sent as a real
     * notification; {@code cleared} keeps the subject so an all-clear names what it clears.
     */
    private static String payloadOf(
            final AlertType type, final Alerts.@Nullable Alert alert, final Alerts.@Nullable Alert cleared) {
        final Alerts.Alert named = alert != null ? alert : cleared;
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.key());
        body.put("level", alert == null ? "ok" : alert.level());
        body.put("subject", named == null ? "" : named.subject());
        body.put("path", named == null ? "/" : named.path());
        return GSON.toJson(body);
    }

    private static String describe(final Alerts.@Nullable Alert alert) {
        return alert == null ? "ok" : alert.level() + "/" + alert.subject();
    }
}
