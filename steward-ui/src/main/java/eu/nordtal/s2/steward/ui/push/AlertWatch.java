package eu.nordtal.s2.steward.ui.push;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Polls the traffic light and pushes every subscribed browser that asked for that kind of news
 * (steward/98, concept §10c).
 *
 * <h2>Where the traffic light changes state</h2>
 * Nowhere on a server before steward/98: {@code health.ts}'s {@code summarise()} computed it only in
 * the browser that had the page open. steward-worker's {@code AlertLevel} is the reading and this is
 * the judgement - see {@link Alerts} for where the three configured thresholds are applied, and why
 * they are applied here and not there.
 *
 * <h2>Why steward-ui polls rather than steward-worker pushing</h2>
 * Only this process ever holds a browser's subscription (endpoint, keys) and the VAPID private key
 * that has to sign every send - {@code :steward-worker} has neither and does not need them just to
 * answer "what is the state right now". Polling on this side keeps the push protocol in the one
 * process a browser can ever reach.
 *
 * <h2>One notification per type, and per account consent</h2>
 * A poll compares each {@link AlertType} against the same type in the previous poll, not the whole
 * reading against the whole reading. Two consequences, and both are the point: an image drifting
 * while a service is already down is still its own notification, and a browser only ever hears about
 * a type its account left switched on (see {@link PushPreferences}, and {@link AlertType} for what
 * the defaults are and why they live in code).
 *
 * <h2>The first poll never sends</h2>
 * There is nothing to compare it against yet, and a push on every restart of this container would
 * be a push for a change that never happened - the state was simply not known a moment before.
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
    private volatile Map<AlertType, Alerts.Alert> last;

    AlertWatch(final @NotNull AlertLevelSource source, final @NotNull PushSubscriptions subscriptions,
               final @NotNull PushPreferences preferences, final @NotNull PushSender sender,
               final @NotNull Alerts.Thresholds thresholds) {
        this.source = Objects.requireNonNull(source, "source");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** The public constructor: the real worker, the real database, the real push protocol. */
    public AlertWatch(final @NotNull eu.nordtal.s2.steward.ui.internal.InternalClient worker,
                      final @NotNull PushSubscriptions subscriptions,
                      final @NotNull PushPreferences preferences,
                      final @NotNull String vapidSubject,
                      final @NotNull com.interaso.webpush.VapidKeys vapidKeys,
                      final int diskPercent, final int memoryPercent, final int backupAgeHours) {
        this(new WorkerAlertLevelSource(worker), subscriptions, preferences,
                new WebPushSender(vapidSubject, vapidKeys),
                new Alerts.Thresholds(diskPercent, memoryPercent, backupAgeHours));
    }

    /**
     * One cycle: read the state, and tell every subscribed browser about each type that moved.
     *
     * <p>Called on {@code StewardUi}'s existing heartbeat scheduler - see {@code sweepSessions} for
     * the sibling this is scheduled beside. A failure to reach the worker is logged and swallowed:
     * a background poll that throws stops running forever on a
     * {@code ScheduledExecutorService}, silently, which would be a worse outcome than skipping one
     * cycle.</p>
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

        // Both tables are read once for the whole cycle rather than once per type, and only once
        // something has actually moved: a stack with nothing wrong touches neither of them.
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
            log.info("{} moved from {} to {} - pushing to every subscription that wants it",
                    type.key(), describe(before), describe(now));
            push(type, payloadOf(type, now, before), chosen, browsers);
        }
    }

    /**
     * One notification of one type, to one browser, on request - the dialog's own test send.
     *
     * <p>It deliberately ignores {@link PushPreferences}: somebody who has just tapped "test this"
     * has asked for this one notification, and refusing it because the switch beside it is off would
     * answer a question nobody asked. The switch governs what arrives unbidden.</p>
     *
     * @return what the push service said, so that the route can report a dead subscription rather
     *         than a silent success
     */
    public @NotNull Delivery sendSample(final @NotNull PushSubscriptions.Subscription subscription,
                                        final @NotNull AlertType type) {
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
     * <p>{@code PushSender} is package-private on purpose: it is the seam a test stands in front of,
     * not part of this class's API. A route that asks for a test send still needs to know the
     * difference between "it went" and "that browser is gone", so this is the one enum that crosses
     * the package line, and it says the three words a caller can act on.</p>
     */
    public enum Delivery {
        /** The push service took it. */
        SENT,
        /** The push service says that subscription no longer exists; the row has been removed. */
        GONE,
        /** Neither - a network failure, or a status the library did not expect. */
        FAILED
    }

    private void push(final AlertType type, final String payload,
                      final Map<String, Map<AlertType, Boolean>> chosen,
                      final List<PushSubscriptions.Subscription> browsers) {
        for (final PushSubscriptions.Subscription subscription : browsers) {
            if (!PushPreferences.enabled(chosen.get(subscription.discordId()), type)) {
                continue;
            }
            final PushSender.Result result = send(subscription, payload);
            switch (result) {
                case SENT -> subscriptions.touchSent(subscription.endpoint());
                // The 404/410 the ticket asks a sender to detect AND handle: detecting it alone
                // (see WebPushSender/WebPush.SubscriptionState.EXPIRED) is only half the job, and
                // the row would sit there being pushed to, and failing, forever otherwise.
                case EXPIRED -> subscriptions.expired(subscription.endpoint());
                case FAILED -> log.warn("push to {} failed (not a 404/410)", subscription.endpoint());
            }
        }
    }

    private PushSender.Result send(final PushSubscriptions.Subscription subscription,
                                   final String payload) {
        try {
            return sender.send(subscription, payload);
        } catch (final RuntimeException failure) {
            log.warn("push to {} failed: {}", subscription.endpoint(), failure.getMessage());
            return PushSender.Result.FAILED;
        }
    }

    /**
     * What a test send shows.
     *
     * <p>A real-looking alert of that type rather than the word "test": the whole question somebody
     * taps this to answer is "does a notification of this kind reach this phone, and what does it
     * look like when it does".</p>
     */
    private static Alerts.Alert sample(final AlertType type) {
        return switch (type) {
            case SERVICE -> new Alerts.Alert(type, "down", "smp", "/services/smp");
            case BACKUP -> new Alerts.Alert(type, "down", "backups", "/operations");
            case DISK -> new Alerts.Alert(type, "warn", "disk", "/");
            case MEMORY -> new Alerts.Alert(type, "warn", "memory", "/");
            case DRIFT -> new Alerts.Alert(type, "warn", "registry", "/operations");
        };
    }

    /**
     * The push body.
     *
     * <p>{@code alert} being null is a type that has stopped being true, and it is sent as a real
     * notification rather than swallowed: "smp is back" is the second half of "smp is down", and a
     * lock screen that only ever reports failures is one nobody can trust to be quiet.</p>
     *
     * <p><b>An all-clear keeps the subject of what it clears</b>, which is what {@code cleared} is
     * for. Till, 2026-09-19: the first line of a notification names the thing and what is up with
     * it. A clear that dropped the subject would arrive as "Steward is clear" - true, unreadable,
     * and the one notification somebody is waiting for after a service went down.</p>
     */
    private static String payloadOf(final AlertType type, final @Nullable Alerts.Alert alert,
                                    final @Nullable Alerts.Alert cleared) {
        final Alerts.Alert named = alert != null ? alert : cleared;
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type.key());
        body.put("level", alert == null ? "ok" : alert.level());
        body.put("subject", named == null ? "" : named.subject());
        body.put("path", named == null ? "/" : named.path());
        return GSON.toJson(body);
    }

    private static String describe(final @Nullable Alerts.Alert alert) {
        return alert == null ? "ok" : alert.level() + "/" + alert.subject();
    }
}
