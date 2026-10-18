package eu.nordtal.season.messages.value;

import eu.nordtal.season.messages.MessageRef;
import java.util.Map;

/** The words that values are shown with in one language, from the {@code values} bundle.
 * A message inside a message is rendered here too, for the same reader.
 */
@FunctionalInterface
public interface Words {

    /**
     * @param key the key without its {@code values.} prefix, such as {@code duration.days}
     */
    String word(String key, Map<String, Object> values);

    /**
     * Returns the word a value of {@code kind} shows when it has no value, such as {@code someone}
     * for a name.
     */
    default String missing(final Kind kind) {
        return word("missing." + kind.token(), Map.of());
    }

    /** Returns the message as plain text for the same reader.
     * Words that render no messages return its replacement word.
     */
    default String message(final MessageRef message) {
        return missing(Kind.MESSAGE);
    }
}
