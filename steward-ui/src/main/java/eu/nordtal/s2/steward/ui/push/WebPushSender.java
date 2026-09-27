package eu.nordtal.s2.steward.ui.push;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PushSender} over the real protocol.
 *
 * VAPID's ES256 JWT and the aes128gcm envelope, both built by {@code com.interaso.webpush}.
 * {@code WebPush.SubscriptionState.EXPIRED} is the 404/410 the ticket asks a sender to notice; the
 * library maps both status codes to it already, so nothing here matches a status code by hand.
 * {@code catch (Exception)}, not the library's own checked type, since the library declares no
 * checked exceptions at all for this call to catch more narrowly.
 */
final class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final WebPushService service;

    WebPushSender(final String subject, final VapidKeys keys) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(keys, "keys");
        this.service = new WebPushService(subject, keys);
    }

    @Override
    public Result send(final PushSubscriptions.Subscription subscription, final String payload) {
        try {
            final WebPush.SubscriptionState state = service.send(
                    payload, subscription.endpoint(), subscription.p256dh(), subscription.auth(), null, null, null);
            return state == WebPush.SubscriptionState.EXPIRED ? Result.EXPIRED : Result.SENT;
        } catch (final Exception failure) {
            log.warn("a push to {} failed and was not a 404/410: {}", subscription.endpoint(), failure.getMessage());
            return Result.FAILED;
        }
    }
}
