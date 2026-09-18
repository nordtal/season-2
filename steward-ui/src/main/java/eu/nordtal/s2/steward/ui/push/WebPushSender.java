package eu.nordtal.s2.steward.ui.push;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link PushSender} over the real protocol: VAPID's ES256 JWT and the aes128gcm envelope, both
 * built by {@code com.interaso.webpush} - see the version catalog for why this library and not the
 * Bouncy-Castle-based fork.
 *
 * <p>{@code WebPush.SubscriptionState.EXPIRED} <b>is</b> the 404/410 the ticket asks a sender to
 * notice - the library maps both status codes to it already (confirmed against its source,
 * {@code WebPush.getSubscriptionState}), which is why nothing here matches a status code by hand.
 * Every parameter after {@code auth} is passed explicitly as {@code null}: the library has no
 * {@code @JvmOverloads}, so its Kotlin default arguments do not exist from Java.</p>
 *
 * <p><b>{@code catch (Exception)}, not the library's own {@code WebPushException}, and this was
 * measured rather than guessed:</b> {@code javap -v} on {@code webpush-1.3.0.jar} shows no
 * {@code Exceptions} attribute on {@code WebPushService.send} at all, for the library's own checked
 * type or for the {@code java.io.IOException}/{@code InterruptedException} its internal
 * {@code HttpClient.send} call can throw - Kotlin has no checked exceptions, so none of that made it
 * into the {@code throws} the JVM's verifier would otherwise expect. {@code catch
 * (WebPushException)} around this call is therefore a compile error ("exception is never thrown in
 * body of corresponding try statement"), and the broader catch is what is actually needed to see a
 * failure of either kind rather than let it fall out of the scheduler that calls {@link AlertWatch}.</p>
 */
final class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final WebPushService service;

    WebPushSender(final @NotNull String subject, final @NotNull VapidKeys keys) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(keys, "keys");
        this.service = new WebPushService(subject, keys);
    }

    @Override
    public @NotNull Result send(final @NotNull PushSubscriptions.Subscription subscription,
                                final @NotNull String payload) {
        try {
            final WebPush.SubscriptionState state = service.send(payload, subscription.endpoint(),
                    subscription.p256dh(), subscription.auth(), null, null, null);
            return state == WebPush.SubscriptionState.EXPIRED ? Result.EXPIRED : Result.SENT;
        } catch (final Exception failure) {
            log.warn("a push to {} failed and was not a 404/410: {}", subscription.endpoint(),
                    failure.getMessage());
            return Result.FAILED;
        }
    }
}
