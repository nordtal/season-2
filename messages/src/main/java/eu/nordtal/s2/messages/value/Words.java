package eu.nordtal.s2.messages.value;

import java.util.Map;

/**
 * The words values are shown with in one language, out of the {@code values} bundle.
 * They are each kind's replacement word, a duration's units, a list's last joint, yes and no.
 */
@FunctionalInterface
public interface Words {

    /**
     * Returns the {@code values} bundle's text at {@code key}, filled and as plain text.
     *
     * @param key the key without its {@code values.} prefix, such as {@code duration.days}
     */
    String word(String key, Map<String, Object> values);

    /** Returns the word a value of {@code kind} shows when it has none, {@code someone} for a name. */
    default String missing(final Kind kind) {
        return word("missing." + kind.token(), Map.of());
    }
}
