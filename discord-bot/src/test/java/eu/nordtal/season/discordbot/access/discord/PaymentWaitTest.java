package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The buyer's wait for a payment link, decided from the request row and the time waited alone. */
class PaymentWaitTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Test
    void aRequestWithoutATabYetKeepsWaiting() {
        assertEquals(
                PaymentWait.WAITING, PaymentWait.of(request(PaymentRequestStatus.OPEN, null, null), Duration.ZERO));
    }

    @Test
    void aShareUrlEndsTheWaitWithTheLink() {
        assertEquals(
                PaymentWait.LINK,
                PaymentWait.of(request(PaymentRequestStatus.OPEN, "https://bunq.me/x", null), Duration.ofSeconds(3)));
    }

    @Test
    void aRefusedTabEndsTheWaitWithTheRefusal() {
        assertEquals(
                PaymentWait.REFUSED,
                PaymentWait.of(request(PaymentRequestStatus.OPEN, null, "tab refused"), Duration.ofSeconds(3)));
    }

    @Test
    void aRequestThatIsNoLongerOpenIsGoneWhateverElseItHolds() {
        for (final PaymentRequestStatus status : PaymentRequestStatus.values()) {
            if (status == PaymentRequestStatus.OPEN) {
                continue;
            }
            assertEquals(
                    PaymentWait.GONE,
                    PaymentWait.of(request(status, "https://bunq.me/x", "tab refused"), Duration.ofHours(1)),
                    status.name());
        }
    }

    @Test
    void theLinkOutranksAGiveUpThatIsDueAtTheSameTime() {
        assertEquals(
                PaymentWait.LINK,
                PaymentWait.of(request(PaymentRequestStatus.OPEN, "https://bunq.me/x", null), Duration.ofMinutes(11)));
        assertEquals(
                PaymentWait.REFUSED,
                PaymentWait.of(request(PaymentRequestStatus.OPEN, null, "tab refused"), Duration.ofMinutes(11)));
    }

    @Test
    void theWaitGivesUpOnlyAfterTenFullMinutes() {
        final PaymentRequest open = request(PaymentRequestStatus.OPEN, null, null);

        assertEquals(PaymentWait.WAITING, PaymentWait.of(open, Duration.ofMinutes(10)));
        assertEquals(
                PaymentWait.GIVE_UP, PaymentWait.of(open, Duration.ofMinutes(10).plusMillis(1)));
    }

    @Test
    void everyAnswerButWaitingIsFinal() {
        for (final PaymentWait wait : PaymentWait.values()) {
            assertEquals(wait != PaymentWait.WAITING, wait.ends(), wait.name());
        }
        assertTrue(PaymentWait.GIVE_UP.ends());
        assertFalse(PaymentWait.WAITING.ends());
    }

    private static PaymentRequest request(
            final PaymentRequestStatus status, final @Nullable String shareUrl, final @Nullable String tabFailed) {
        return new PaymentRequest(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "NT-ABC123",
                DiscordId.of("300000000000000003"),
                30,
                1500,
                0,
                status,
                null,
                shareUrl,
                null,
                NOW,
                NOW.plus(Duration.ofHours(1)),
                null,
                tabFailed,
                null,
                null,
                null);
    }
}
