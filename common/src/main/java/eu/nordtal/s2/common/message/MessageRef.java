package eu.nordtal.s2.common.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One message, chosen and filled: a bundle key plus the values of its placeholders.
 *
 * <p>Made by a message spec (see {@code eu.nordtal.s2.common.message.spec.MessageSpecs}), never by
 * hand in a module - the spec is what makes the key exist and the placeholders the ones the text
 * names. It is only a reference: the language is chosen where it is rendered, by
 * {@link Messages#format(java.util.Locale, MessageRef)} or {@link MessageRenderer}, so the same
 * value can be rendered for every reader of a broadcast, or carried across the network as key and
 * values and rendered on the other side.</p>
 *
 * @param key  the bundle key
 * @param args placeholder name to value, in declaration order; a value that is an Adventure
 *             {@code Component} fills a {@code <name>} tag, anything else a {@code {name}}
 */
public record MessageRef(String key, Map<String, Object> args) {

    public MessageRef {
        Objects.requireNonNull(key, "key");
        args = args == null || args.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** A message without placeholders. */
    public static MessageRef of(final String key) {
        return new MessageRef(key, Map.of());
    }
}
