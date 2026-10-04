package eu.nordtal.s2.internalapi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One message key in every language it has, packaged and overridden, kept apart so an override shows as one.
 *
 * @param key the dotted key, for example {@code contribution.title}
 * @param bundle the packaged bundle the key belongs to, the directory under {@code messages/} in the jar
 * @param texts language tag to every text the jar ships, its variants in order; English first, then by tag
 * @param overrides language tag to the admin's override, its variants in order, which steward fills in from the
 *     database; empty for a key without one
 * @param inBundle whether the jar declares this key
 * @param name the name an admin reads, from the schema; {@code null} when the schema does not describe the key
 * @param description a sentence for a hard case, or {@code null}
 * @param args the placeholders in parameter order; empty when the schema does not describe the key
 * @param section the names of the sections around the key, outermost first; {@code null} for one without a name
 * @param format {@code MINIMESSAGE}, {@code DISCORD_MARKDOWN} or {@code PLAIN}; {@code null} when the schema is silent
 * @param shown where the text is shown, for example {@code TITLE}, or {@code null} when the schema does not say
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
        @Nullable String shown) {

    /** English first, as the fallback every process shows, then by tag. */
    private static final Comparator<String> BY_LANGUAGE =
            Comparator.comparing((String language) -> !"en".equals(language)).thenComparing(language -> language);

    public MessageEntry {
        texts = ordered(texts);
        overrides = ordered(overrides);
        args = List.copyOf(args);
        // A nameless section is null here, which List.copyOf would refuse.
        section = Collections.unmodifiableList(new ArrayList<>(section));
    }

    /** The same key with the admin's overrides, language tag to variants. */
    public MessageEntry withOverrides(final Map<String, List<String>> overrides) {
        return new MessageEntry(
                key, bundle, texts, overrides, inBundle, name, description, args, section, format, shown);
    }

    /** Every text the jar ships for the key in {@code language}, empty where it ships none. */
    public List<String> packaged(final String language) {
        return texts.getOrDefault(language, List.of());
    }

    /** Whether the jar's schema describes this key, which is what makes its placeholders checkable. */
    public boolean described() {
        return name != null;
    }

    /** English first, then by tag, each language's variants copied; a language without a text is left out. */
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
