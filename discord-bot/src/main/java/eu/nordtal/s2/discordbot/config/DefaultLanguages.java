package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.Specs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages a fresh {@code access.yml} is written with: {@code en} and {@code de}.
 *
 * <h2>Why this class exists</h2>
 * The same reason {@link DefaultTiers} does, and it is the worked example this follows.
 * {@code languages} is a list so that a third language is a config edit rather than a release
 * ({@code docs/i18n.md}), and jcore initialises a {@code List<NestedSpec>} to <b>empty</b> - so
 * without a default built here, a fresh installation would come out with no languages at all and
 * refuse to start on a file nobody had touched yet. {@code Specs.createUnsafe} is jcore's
 * documented way to build a nested spec from a map of its keys, and jcore's writer knows how to
 * serialise a list of them.
 * <p>
 * The ids are still empty, which is the standing rule for every id in this repository: a fresh file
 * carries the two <b>entries</b>, not two guesses at somebody's role and channel snowflakes. So a
 * fresh install still stops at the config check - it just stops saying "fill these in" rather than
 * "there are no languages".
 * </p>
 * <p>
 * The map keys are the {@code @Key} names from {@link AccessSpec.LanguageSpec}, not the method
 * names. {@code createUnsafe} does not apply defaults, so <b>every</b> key of the spec has to be
 * listed here; a new setting on {@code LanguageSpec} has to be added below or it comes out null.
 * </p>
 */
final class DefaultLanguages {

    /**
     * English first, because it is the mandatory fallback and the order of this list is the order
     * the link-code disconnect screen prints its languages in ({@code docs/i18n.md}).
     */
    static final List<AccessSpec.LanguageSpec> LIST = List.of(language("en"), language("de"));

    private DefaultLanguages() {
    }

    private static AccessSpec.LanguageSpec language(final String tag) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("tag", tag);
        values.put("role", "");
        values.put("contribution-channel", "");
        values.put("link-channel", "");
        return Specs.createUnsafe(AccessSpec.LanguageSpec.class, values);
    }
}
