package eu.nordtal.s2.steward.ui.push;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Polls the traffic light and pushes every subscribed browser when it changes (steward/98, concept
 * §10c).
 *
 * <h2>Where the traffic light changes state</h2>
 * Nowhere on a server before steward/98: {@code health.ts}'s {@code summarise()} computed it only in
 * the browser that had the page open. This class is the new, deliberately narrower half - see
 * {@code AlertLevel} in {@code :steward-worker} for the binary/presence subset it reads, and why the
 * two threshold-based warnings (disk/memory %, backup age) are not in it.
 *
 * <h2>Why steward-ui polls rather than steward-worker pushing</h2>
 * Only this process ever holds a browser's subscription (endpoint, keys) and the VAPID private key
 * that has to sign every send - {@code :steward-worker} has neither and does not need them just to
 * answer "what is the state right now". Polling on this side keeps the push protocol in the one
 * process a browser can ever reach.
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
    private final PushSender sender;

    /** Null until the first successful poll - see the class note on why that poll never sends. */
    private volatile AlertReading last;

    AlertWatch(final @NotNull AlertLevelSource source, final @NotNull PushSubscriptions subscriptions,
              final @NotNull PushSender sender) {
        this.source = Objects.requireNonNull(source, "source");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /** The public constructor: the real worker, the real database, the real push protocol. */
    public AlertWatch(final @NotNull eu.nordtal.s2.steward.ui.internal.InternalClient worker,
                      final @NotNull PushSubscriptions subscriptions,
                      final @NotNull String vapidSubject,
                      final @NotNull com.interaso.webpush.VapidKeys vapidKeys) {
        this(new WorkerAlertLevelSource(worker), subscriptions,
                new WebPushSender(vapidSubject, vapidKeys));
    }

    /**
     * One cycle: read the state, and if it moved since the last cycle that could read one, tell
     * every subscribed browser.
     *
     * <p>Called on {@code StewardUi}'s existing heartbeat scheduler - see {@code sweepSessions} for
     * the sibling this is scheduled beside. A failure to reach the worker is logged and swallowed:
     * a background poll that throws stops running forever on a
     * {@code ScheduledExecutorService}, silently, which would be a worse outcome than skipping one
     * cycle.</p>
     */
    public void poll() {
        final AlertReading current;
        try {
            current = source.current();
        } catch (final RuntimeException unreachable) {
            log.warn("could not read the traffic light this cycle: {}", unreachable.getMessage());
            return;
        }
        final AlertReading previous = last;
        last = current;
        if (previous == null || previous.equals(current)) {
            return;
        }
        log.info("the traffic light moved from {}/{} to {}/{} - pushing to every subscription",
                previous.level(), previous.subject(), current.level(), current.subject());
        final String payload = payloadOf(current);
        for (final PushSubscriptions.Subscription subscription : subscriptions.all()) {
            final PushSender.Result result;
            try {
                result = sender.send(subscription, payload);
            } catch (final RuntimeException failure) {
                log.warn("push to {} failed: {}", subscription.endpoint(), failure.getMessage());
                continue;
            }
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

    private static String payloadOf(final AlertReading reading) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", reading.level());
        body.put("subject", reading.subject());
        body.put("path", reading.path());
        return GSON.toJson(body);
    }
}
