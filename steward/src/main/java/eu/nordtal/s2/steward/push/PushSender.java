package eu.nordtal.s2.steward.push;

/** Sends one push to one subscription; the seam {@code AlertRouterTest} stands in front of. */
public interface PushSender {

    /** What sending answered. */
    enum Result {
        /** The push service accepted it (HTTP 200-202). */
        SENT,
        /** The push service says this subscription no longer exists (HTTP 404 or 410). */
        EXPIRED,
        /** Neither: a network failure, or a status the library did not expect. */
        FAILED
    }

    Result send(PushSubscriptions.Subscription subscription, String payload);
}
