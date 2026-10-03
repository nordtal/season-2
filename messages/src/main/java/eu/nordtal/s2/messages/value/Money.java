package eu.nordtal.s2.messages.value;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in the currency's smallest unit, so no rounding ever happens before it is shown.
 *
 * @param minor    the amount in cents for the euro
 * @param currency its currency
 */
public record Money(long minor, Currency currency) {

    private static final Currency EURO = Currency.getInstance("EUR");

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /** Returns an amount in euro cents, the network's one currency. */
    public static Money euroCents(final long cents) {
        return new Money(cents, EURO);
    }
}
