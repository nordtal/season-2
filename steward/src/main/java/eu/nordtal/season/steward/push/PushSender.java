package eu.nordtal.season.steward.push;

/** Sends one push to one subscription. */
public interface PushSender {

    enum Result {
        /** The push service accepted it (HTTP 200-202). */
        SENT,
        /** The push service reports that the subscription does not exist (HTTP 404 or 410). */
        EXPIRED,
        /** A network failure or an unexpected status. */
        FAILED
    }

    Result send(PushSubscriptions.Subscription subscription, String payload);
}
