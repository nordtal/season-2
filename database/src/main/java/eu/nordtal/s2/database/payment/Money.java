package eu.nordtal.s2.database.payment;

import java.util.Locale;

/** Integer cents as a person reads them; steward-bunq alone converts them to and from the bank's decimals. */
public final class Money {

    private Money() {}

    /** Returns {@code cents} for a human, e.g. {@code "3.00 €"}. */
    public static String format(final int cents) {
        return String.format(Locale.ROOT, "%d.%02d €", cents / 100, Math.abs(cents % 100));
    }
}
