package eu.nordtal.s2.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pins cents-to-euros in {@link OpenPayment}, including the leading zero under ten cents. */
class OpenPaymentTest {

    private static OpenPayment of(final int cents) {
        return new OpenPayment("NT-A1B2C3", 30, cents, 0, true, Instant.EPOCH);
    }

    @Test
    void aWholeEuroAmountKeepsBothDecimalPlaces() {
        assertEquals("3.00", of(300).amount());
        assertEquals("7.00", of(700).amount());
    }

    @Test
    void centsUnderTenKeepTheirLeadingZero() {
        // 1205 formatted without padding is "12.5", which reads as twelve euros fifty.
        assertEquals("12.05", of(1205).amount());
        assertEquals("0.01", of(1).amount());
    }

    @Test
    void anOrdinaryTierPriceWithADonationOnTop() {
        // amountCents is the tab's total, donation included.
        assertEquals("12.00", new OpenPayment("NT-A1B2C3", 90, 1200, 500, true, Instant.EPOCH).amount());
    }
}
