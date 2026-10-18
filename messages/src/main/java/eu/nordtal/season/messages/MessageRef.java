package eu.nordtal.season.messages;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bundle key and the values of its placeholders.
 * A message spec creates it, and the language is chosen where it is rendered.
 *
 * @param args the value of each placeholder name, in declaration order
 */
public record MessageRef(String key, Map<String, Object> args) {

    public MessageRef {
        Objects.requireNonNull(key, "key");
        args = args == null || args.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public static MessageRef of(final String key) {
        return new MessageRef(key, Map.of());
    }
}
