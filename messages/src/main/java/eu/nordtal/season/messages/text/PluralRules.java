package eu.nordtal.season.messages.text;

import java.math.BigDecimal;

/**
 * The plural category of a number.
 * Every language the network ships says {@code one} for exactly 1 without decimals and {@code other} otherwise;
 * a language whose rules differ needs its own rule here, chosen by language, before its first bundle.
 */
final class PluralRules {

    private PluralRules() {}

    static String category(final BigDecimal number) {
        return number.scale() <= 0 && number.compareTo(BigDecimal.ONE) == 0 ? "one" : "other";
    }
}
