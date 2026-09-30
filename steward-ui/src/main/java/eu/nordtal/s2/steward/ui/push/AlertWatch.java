package eu.nordtal.s2.steward.ui.push;

import eu.nordtal.s2.common.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the traffic light and pushes each type that moved to every browser whose account left it on.
 *
 * The first poll never sends, since there is nothing yet to compare it against.
 */
public final class AlertWatch {

    private static final Logger log = LoggerFactory.getLogger(AlertWatch.class);

    private final AlertLevelSource source;
    private final PushSubscriptions subscriptions;
    private final PushPreferences preferences;
    private final PushSender sender;
    private final Alerts.Thresholds thresholds;

    /** Null until the first successful poll. */
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

    /** Wires the real worker, database and push protocol. */
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
     * A failure is logged and swallowed, since a throwing task stops its {@code ScheduledExecutorService} for good.
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
     * Sends one sample notification of {@code type} to one browser, ignoring {@link PushPreferences}.
     *
     * @return what the push service said, so the route can report a dead subscription
     */
    public Delivery sendSample(final PushSubscriptions.Subscription subscription, final AlertType type) {
        final PushSender.Result result = send(subscription, payloadOf(type, sample(type), null));
        if (result == PushSender.Result.EXPIRED) {
            subscriptions.expired(subscription.endpoint());
            return Delivery.GONE;
        }
        return result == PushSender.Result.SENT ? Delivery.SENT : Delivery.FAILED;
    }

    /** {@link PushSender.Result} outside this package, so a route can tell "it went" from "that browser is gone". */
    public enum Delivery {
        /** The push service took it. */
        SENT,
        /** The push service says that subscription no longer exists; the row has been removed. */
        GONE,
        /** Neither: a network failure, or a status the library did not expect. */
        FAILED
    }

    private void push(
            final AlertType type,
            final String payload,
            final Map<String, Map<AlertType, Boolean>> chosen,
            final List<PushSubscriptions.Subscription> browsers) {
        for (final PushSubscriptions.Subscription subscription : browsers) {
            if (!PushPreferences.enabled(chosen.get(subscription.discordId().value()), type)) {
                continue;
            }
            final PushSender.Result result = send(subscription, payload);
            switch (result) {
                case SENT -> subscriptions.touchSent(subscription.endpoint());
                // A dead subscription (404/410) is removed, or it keeps failing forever.
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

    /** A real-looking alert of that type, to show what one looks like. */
    private static Alerts.Alert sample(final AlertType type) {
        return switch (type) {
            case SERVICE -> new Alerts.Alert(type, "down", "smp", "/services/smp");
            case BACKUP -> new Alerts.Alert(type, "down", "backups", "/operations/backups");
            case DISK -> new Alerts.Alert(type, "warn", "disk", "/");
            case MEMORY -> new Alerts.Alert(type, "warn", "memory", "/");
            case DRIFT -> new Alerts.Alert(type, "warn", "registry", "/operations/updates");
        };
    }

    /** The push body; a null {@code alert} with a {@code cleared} one is an all-clear naming what it clears. */
    private static String payloadOf(
            final AlertType type, final Alerts.@Nullable Alert alert, final Alerts.@Nullable Alert cleared) {
        final Alerts.Alert named = alert != null ? alert : cleared;
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.key());
        body.put("level", alert == null ? "ok" : alert.level());
        body.put("subject", named == null ? "" : named.subject());
        body.put("path", named == null ? "/" : named.path());
        return Json.encode(body);
    }

    private static String describe(final Alerts.@Nullable Alert alert) {
        return alert == null ? "ok" : alert.level() + "/" + alert.subject();
    }
}
