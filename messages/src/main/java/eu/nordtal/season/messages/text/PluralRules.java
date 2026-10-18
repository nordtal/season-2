package eu.nordtal.season.messages.text;

import java.math.BigDecimal;

/** Every language the network ships uses {@code one} for exactly 1 without decimals and {@code other} otherwise.
 * A language with other rules needs its own rule here before its first bundle.
 */
final class PluralRules {

    private PluralRules() {}

    static String category(final BigDecimal number) {
        return number.scale() <= 0 && number.compareTo(BigDecimal.ONE) == 0 ? "one" : "other";
    }
}
