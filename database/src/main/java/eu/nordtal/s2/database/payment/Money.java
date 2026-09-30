package eu.nordtal.s2.database.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Integer cents in, decimal strings out, and back.
 * The only conversion point, and it goes through {@link BigDecimal}, never {@code double}.
 */
public final class Money {

    private static final BigDecimal CENTS_PER_EURO = BigDecimal.valueOf(100);

    private Money() {}

    /** Returns {@code cents} as bunq wants it, e.g. {@code "3.00"}. */
    public static String toDecimalString(final int cents) {
        return BigDecimal.valueOf(cents)
                .divide(CENTS_PER_EURO, 2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /**
     * Returns a decimal amount as bunq returns it, in cents.
     *
     * @throws NumberFormatException if the value is not a decimal number
     */
    public static int toCents(final String value) {
        return new BigDecimal(value.trim())
                .multiply(CENTS_PER_EURO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** Returns {@code cents} for a human, e.g. {@code "3.00 €"}. */
    public static String format(final int cents) {
        return String.format(Locale.ROOT, "%d.%02d €", cents / 100, Math.abs(cents % 100));
    }
}
