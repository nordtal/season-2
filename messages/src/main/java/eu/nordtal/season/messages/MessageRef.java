package eu.nordtal.season.messages;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One message, chosen and filled: a bundle key plus the values of its placeholders.
 * Made only by a message spec; the language is chosen where it is rendered.
 *
 * @param key  the bundle key
 * @param args placeholder name to value, in declaration order; a {@code Component} fills a {@code <name>} tag
 */
public record MessageRef(String key, Map<String, Object> args) {

    public MessageRef {
        Objects.requireNonNull(key, "key");
        args = args == null || args.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** Returns a message without placeholders. */
    public static MessageRef of(final String key) {
        return new MessageRef(key, Map.of());
    }
}
