package eu.nordtal.season.steward.push;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.interaso.webpush.VapidKeys;
import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebPushSenderTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.invalid/push",
                "http://fcm.googleapis.com/push",
                "https://fcm.googleapis.com:8443/push",
                "https://fcm.googleapis.com.example.invalid/push",
                "https://example@fcm.googleapis.com/push"
            })
    void anUnsupportedStoredEndpointIsDiscarded(final String endpoint) {
        final WebPushSender sender = new WebPushSender("mailto:push@example.invalid", VapidKeys.generate());
        final PushSubscriptions.Subscription subscription = new PushSubscriptions.Subscription(
                endpoint, DiscordId.of("42"), "not-a-key", "not-a-key", Instant.EPOCH, null, null, null);
        assertEquals(PushSender.Result.EXPIRED, sender.send(subscription, "{}"));
    }
}
