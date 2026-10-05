package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages the {@code access} group defaults to, {@code en} and {@code de}, with empty ids and their own names.
 *
 * {@code createUnsafe} applies no defaults, so every {@code @Key} of {@link AccessSpec.LanguageSpec} is listed.
 */
final class DefaultLanguages {

    /** English first, as the mandatory fallback; the link screen prints languages in this order. */
    static final List<AccessSpec.LanguageSpec> LIST = List.of(language("en"), language("de"));

    private DefaultLanguages() {}

    private static AccessSpec.LanguageSpec language(final String tag) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("tag", tag);
        values.put("role-name", "");
        values.put("contribution-channel", "");
        values.put("link-channel", "");
        values.put("hunger-games-channel", "");
        values.put("status-channel", "");
        values.put("announcement-channel", "");
        return Specs.createUnsafe(AccessSpec.LanguageSpec.class, values);
    }
}
