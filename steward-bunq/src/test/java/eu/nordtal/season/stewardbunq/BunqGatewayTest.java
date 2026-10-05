package eu.nordtal.season.stewardbunq;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bunq.sdk.model.generated.endpoint.PaymentApiObject;
import com.bunq.sdk.model.generated.object.AmountObject;
import eu.nordtal.season.internalapi.BankWire;
import org.junit.jupiter.api.Test;

/** What crosses the wire to steward: amounts in whole cents, and only money that came in, in euros. */
class BunqGatewayTest {

    @Test
    void centsAndBunqsDecimalsConvertBothWaysWithoutADouble() {
        assertAll(
                () -> assertEquals("3.00", BunqGateway.decimal(300)),
                () -> assertEquals("12.05", BunqGateway.decimal(1205)),
                () -> assertEquals(300, BunqGateway.cents("3.00")),
                () -> assertEquals(500, BunqGateway.cents("5")),
                () -> assertEquals(1205, BunqGateway.cents("12.05")));
    }

    @Test
    void anIncomingEuroPaymentCrossesWithItsTimeInUtc() {
        final BankWire.Payment wired = BunqGateway.wire(payment(41L, "12.05", "EUR", "2026-09-30 18:04:05.123456"));

        assertEquals(new BankWire.Payment(41L, 1205, "2026-09-30T18:04:05.123456Z", "NT-ABC123"), wired);
    }

    @Test
    void nothingElseCrosses() {
        assertAll(
                () -> assertNull(BunqGateway.wire(payment(1L, "-5.00", "EUR", "2026-09-30 18:04:05")), "outgoing"),
                () -> assertNull(BunqGateway.wire(payment(2L, "5.00", "USD", "2026-09-30 18:04:05")), "dollars"),
                () -> assertNull(BunqGateway.wire(payment(3L, "5.00", "EUR", "yesterday")), "an unreadable time"),
                () -> assertNull(BunqGateway.wire(payment(null, "5.00", "EUR", "2026-09-30 18:04:05")), "no id"));
    }

    /** Returns a payment as the SDK would have read it from bunq's answer. */
    private static PaymentApiObject payment(final Long id, final String value, final String currency, final String at) {
        final PaymentApiObject payment = new PaymentApiObject();
        payment.setId(id);
        // The two-argument constructor fills the request's fields; an answer from bunq fills these.
        final AmountObject amount = new AmountObject();
        amount.setValue(value);
        amount.setCurrency(currency);
        payment.setAmount(amount);
        payment.setCreated(at);
        payment.setDescription("NT-ABC123");
        return payment;
    }
}
