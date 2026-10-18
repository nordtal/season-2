package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.spec.Specs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default languages of the {@code access} group: the network's default languages with empty ids.
 * {@code createUnsafe} applies no defaults, so {@code language} lists every {@code @Key} of
 * {@link AccessSpec.LanguageSpec}.
 */
final class DefaultLanguages {

    static final List<AccessSpec.LanguageSpec> LIST = NetworkSettings.defaultLanguages().tags().stream()
            .map(DefaultLanguages::language)
            .toList();

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
