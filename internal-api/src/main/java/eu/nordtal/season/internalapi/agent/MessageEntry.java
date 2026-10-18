package eu.nordtal.season.internalapi.agent;

import eu.nordtal.season.common.language.Locales;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One message key in every language it has.
 *
 * @param key the dotted key, for example {@code contribution.title}
 * @param bundle the packaged bundle of the key: the directory under {@code messages/} in the jar
 * @param texts the texts that the jar ships, by language tag, with their variants in order
 * @param overrides the admin's override by language tag, filled in by steward from the database, empty for a key
 *     without one
 * @param inBundle whether the jar declares this key
 * @param name the name an admin reads, from the schema, or {@code null} if the schema does not describe the key
 * @param description a sentence for a hard case, or {@code null}
 * @param args the placeholders in parameter order, empty if the schema does not describe the key
 * @param section the names of the enclosing sections, outermost first, or {@code null} for one without a name
 * @param format {@code MINIMESSAGE}, {@code DISCORD_MARKDOWN} or {@code PLAIN}, or {@code null} if the schema is silent
 * @param shown every place the text is shown, for example {@code TITLE}, with the place that an editor previews
 *     first leading
 * @param limit the maximum number of characters, the strictest of its places, or {@code 0} for no limit
 */
public record MessageEntry(
        String key,
        String bundle,
        Map<String, List<String>> texts,
        Map<String, List<String>> overrides,
        boolean inBundle,
        @Nullable String name,
        @Nullable String description,
        List<MessageArg> args,
        List<@Nullable String> section,
        @Nullable String format,
        List<String> shown,
        int limit) {

    /** Orders English first, then by tag. */
    private static final Comparator<String> BY_LANGUAGE = Comparator.comparing(
                    (String language) -> !Locales.DEFAULT_TAG.equals(language))
            .thenComparing(language -> language);

    public MessageEntry {
        texts = ordered(texts);
        overrides = ordered(overrides);
        args = List.copyOf(args);
        shown = List.copyOf(shown);
        // A nameless section is null, which List.copyOf refuses.
        section = Collections.unmodifiableList(new ArrayList<>(section));
    }

    public MessageEntry withOverrides(final Map<String, List<String>> overrides) {
        return new MessageEntry(
                key, bundle, texts, overrides, inBundle, name, description, args, section, format, shown, limit);
    }

    public List<String> packaged(final String language) {
        return texts.getOrDefault(language, List.of());
    }

    public boolean described() {
        return name != null;
    }

    /**
     * Orders English first, then by tag, and copies each language's variants.
     * A language without a text is left out.
     */
    private static Map<String, List<String>> ordered(final Map<String, List<String>> byLanguage) {
        final Map<String, List<String>> sorted = new TreeMap<>(BY_LANGUAGE);
        byLanguage.forEach((language, variants) -> {
            if (!variants.isEmpty()) {
                sorted.put(language, List.copyOf(variants));
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
