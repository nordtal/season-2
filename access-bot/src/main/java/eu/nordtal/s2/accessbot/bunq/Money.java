package eu.nordtal.s2.accessbot.bunq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Integer cents in, decimal strings out, and back.
 * <p>
 * Money is an {@code int} of cents everywhere in this module and an {@code int} of cents in the
 * database. bunq speaks decimal strings ({@code "3.00"}), so the conversion happens here and
 * nowhere else - and it goes through {@link BigDecimal}, never {@code double}. Season 1 parsed
 * amounts with {@code Float.parseFloat} and compared them with {@code <}, which is how you end up
 * deciding that 5.00 is not at least 5.
 * </p>
 */
public final class Money {

    private static final BigDecimal CENTS_PER_EURO = BigDecimal.valueOf(100);

    private Money() {
    }

    /**
     * @param cents an amount in cents
     * @return the amount as bunq wants it, e.g. {@code "3.00"}
     */
    public static String toDecimalString(final int cents) {
        return BigDecimal.valueOf(cents).divide(CENTS_PER_EURO, 2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * @param value a decimal amount as bunq returns it
     * @return the amount in cents
     * @throws NumberFormatException if the value is not a decimal number
     */
    public static int toCents(final String value) {
        return new BigDecimal(value.trim())
                .multiply(CENTS_PER_EURO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /**
     * @param cents an amount in cents
     * @return the amount for a human, e.g. {@code "3.00 €"}
     */
    public static String format(final int cents) {
        return String.format(Locale.ROOT, "%d.%02d €", cents / 100, Math.abs(cents % 100));
    }
}
