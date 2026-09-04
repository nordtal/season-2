package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one line of arithmetic in {@link OpenPayment}: cents to something a person reads.
 *
 * <p>It is a small thing with a history. Money is integer cents in Java and in the database
 * everywhere in this repository, because season 1 used {@code Float.parseFloat} and {@code <} and
 * that is how a payment of 4.999999 was once not enough for a five euro tier. Anything that turns
 * cents back into a decimal is therefore worth pinning, particularly the case that looks right and
 * is not: a value under ten cents has to keep its leading zero.
 */
class OpenPaymentTest {

    private static OpenPayment of(final int cents) {
        return new OpenPayment("NT-A1B2C3", 30, cents, 0, true, Instant.EPOCH);
    }

    @Test
    @DisplayName("a whole euro amount keeps both decimal places")
    void wholeEuros() {
        assertEquals("3.00", of(300).amount());
        assertEquals("7.00", of(700).amount());
    }

    @Test
    @DisplayName("cents under ten keep their leading zero")
    void theCaseThatLooksRightAndIsNot() {
        // 1205 formatted without padding is "12.5", which reads as twelve euros fifty.
        assertEquals("12.05", of(1205).amount());
        assertEquals("0.01", of(1).amount());
    }

    @Test
    @DisplayName("an ordinary tier price with a donation on top")
    void aTierWithADonation() {
        // 7 EUR for 90 days plus the 5 EUR donation surcharge - amountCents is the total the tab
        // asks for, donation included, which is what the row stores.
        assertEquals("12.00", new OpenPayment("NT-A1B2C3", 90, 1200, 500, true, Instant.EPOCH).amount());
    }
}
