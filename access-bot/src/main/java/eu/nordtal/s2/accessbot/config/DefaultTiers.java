package eu.nordtal.s2.accessbot.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The price list a fresh {@code access.yml} is written with: 30/60/90 days at 3/5/7 €.
 *
 * <h2>Why this class exists</h2>
 * {@code tiers} is a list so that a fourth tier is a config edit rather than a release. A default
 * method on a spec interface can only return values, and a nested spec is served by a reflective
 * proxy - there is no {@code new TierSpec(30, 300)} to write. {@code Specs.createUnsafe} is jcore's
 * documented way to build one from a map of its keys, and jcore's writer knows how to serialise a
 * list of them (the vendored Spec copy was patched for exactly that case).
 * <p>
 * <b>Verified, not assumed</b> (2026-08-30): a fresh file comes out carrying all three entries and
 * reads back as three tiers. The alternative the plan allowed - an empty default plus a loud
 * configuration error - was therefore not needed, though the emptiness check in
 * {@link Configs} stays, because somebody can still delete the entries by hand.
 * </p>
 * <p>
 * The map keys are the {@code @Key} names from {@link AccessSpec.TierSpec}, not the method names.
 * {@code createUnsafe} does not apply defaults, so <b>every</b> key of the spec has to be listed
 * here; a new setting on {@code TierSpec} has to be added below or it comes out null.
 * </p>
 */
final class DefaultTiers {

    /** The agreed season 2 product. Referenced by {@code AccessSpec#tiers()}. */
    static final List<AccessSpec.TierSpec> LIST = List.of(tier(30, 300), tier(60, 500), tier(90, 700));

    private DefaultTiers() {
    }

    private static AccessSpec.TierSpec tier(final int days, final int priceCents) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("days", days);
        values.put("price-cents", priceCents);
        return Specs.createUnsafe(AccessSpec.TierSpec.class, values);
    }
}
