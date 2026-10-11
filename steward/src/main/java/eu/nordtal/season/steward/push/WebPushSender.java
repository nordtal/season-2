package eu.nordtal.season.steward.push;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import eu.nordtal.season.steward.Web;
import eu.nordtal.season.steward.config.WebSpec;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PushSender} over the real protocol, built by {@code com.interaso.webpush}.
 *
 * The library maps both 404 and 410 to {@code SubscriptionState.EXPIRED}, and declares no checked exception to catch.
 */
public final class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final WebPushService service;

    public WebPushSender(final String subject, final VapidKeys keys) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(keys, "keys");
        this.service = new WebPushService(subject, keys);
    }

    /**
     * The VAPID keypair from config, or null when {@code web-push} is not configured.
     *
     * Exactly one blank key is refused, since neither alone can sign or serve as an {@code applicationServerKey}.
     */
    public static @Nullable VapidKeys keysOf(final WebSpec.WebPushSpec webPush) {
        final String publicKey = webPush.publicKey();
        final String privateKey = webPush.privateKey();
        if (publicKey.isBlank() && privateKey.isBlank()) {
            return null;
        }
        if (publicKey.isBlank() || privateKey.isBlank()) {
            throw new IllegalArgumentException("web-push has only one of public-key/private-key set"
                    + " - both or neither. Run `steward " + Web.GENERATE_VAPID_KEYS + "` and paste"
                    + " both lines it prints into the web group.");
        }
        return VapidKeys.create(publicKey, privateKey);
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
