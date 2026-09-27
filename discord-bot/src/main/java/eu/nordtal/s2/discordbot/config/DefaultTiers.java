package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The price list a fresh {@code access.yml} is written with: 30/60/90 days at 3/5/7 €.
 *
 * {@code createUnsafe} applies no defaults, so every {@code @Key} of {@link AccessSpec.TierSpec} is listed.
 */
final class DefaultTiers {

    static final List<AccessSpec.TierSpec> LIST = List.of(tier(30, 300), tier(60, 500), tier(90, 700));

    private DefaultTiers() {}

    private static AccessSpec.TierSpec tier(final int days, final int priceCents) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("days", days);
        values.put("price-cents", priceCents);
        return Specs.createUnsafe(AccessSpec.TierSpec.class, values);
    }
}
