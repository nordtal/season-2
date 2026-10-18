package eu.nordtal.season.messages.value;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money.
 *
 * @param minor the amount in the currency's smallest unit, cents for the euro
 */
public record Money(long minor, Currency currency) {

    private static final Currency EURO = Currency.getInstance("EUR");

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money euroCents(final long cents) {
        return new Money(cents, EURO);
    }
}
