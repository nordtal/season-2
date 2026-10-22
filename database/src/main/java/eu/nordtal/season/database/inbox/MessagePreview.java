package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.spec.Display;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A text an admin tries for one key, shown to the admin alone. Nothing is saved.
 * The recipient fills the placeholders from {@code examples} and the key's schema, in the colours of {@code service}.
 *
 * @param key the key whose text is tried
 * @param language the language tag of the text
 * @param text the text in place of the text of the key
 * @param shown the place the text is shown in
 * @param service the service whose colour settings paint the tones
 * @param examples the admin's example values, ordered by name. A placeholder without one takes its declared example.
 */
public record MessagePreview(
        String key, String language, String text, Display shown, String service, List<Example> examples) {

    public MessagePreview {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(service, "service");
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
        examples = examples.stream().sorted(Comparator.comparing(Example::name)).toList();
        if (examples.stream().map(Example::name).distinct().count() != examples.size()) {
            throw new IllegalArgumentException("a preview names each placeholder once: " + examples);
        }
    }

    /** A preview with one example per entry of {@code given}. */
    public MessagePreview(
            final String key,
            final String language,
            final String text,
            final Display shown,
            final String service,
            final Map<String, String> given) {
        this(
                key,
                language,
                text,
                shown,
                service,
                given.entrySet().stream()
                        .map(entry -> new Example(entry.getKey(), entry.getValue()))
                        .toList());
    }

    /** Returns the example values by placeholder. */
    public Map<String, String> given() {
        return examples.stream().collect(Collectors.toMap(Example::name, Example::value, (a, b) -> a, TreeMap::new));
    }

    /** The value an admin gave a placeholder, as the admin typed it. */
    public record Example(String name, String value) {

        public Example {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }
}
