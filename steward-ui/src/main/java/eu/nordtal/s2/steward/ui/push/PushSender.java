package eu.nordtal.s2.steward.ui.push;

import org.jetbrains.annotations.NotNull;

/**
 * Sending one push to one subscription - the seam {@code AlertWatchTest} stands in front of, so
 * that the acceptance assertions about a traffic-light change and about a 404/410 do not need a
 * real push service on the other end of a real HTTPS call.
 *
 * @see WebPushSender the real implementation, over {@code com.interaso.webpush}
 */
interface PushSender {

    /** What sending answered. */
    enum Result {
        /** The push service accepted it (HTTP 200-202). */
        SENT,
        /** The push service says this subscription no longer exists (HTTP 404 or 410). */
        EXPIRED,
        /** Neither of the above - a network failure, or a status the library did not expect. */
        FAILED
    }

    @NotNull
    Result send(@NotNull PushSubscriptions.Subscription subscription, @NotNull String payload);
}
