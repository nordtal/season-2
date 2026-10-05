package eu.nordtal.season.internalapi.agent;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One placeholder or action a message declares in its jar's {@code schema.json}.
 * Roles and globals are already expanded into their attributes.
 *
 * @param name    the placeholder's name, dotted for a role's attribute; for an action, its name
 * @param kind    the value kind's token, for example {@code duration}; {@code null} for an action or a value declared
 *                without a kind
 * @param type    the role's context type, for example {@code player}; {@code null} for a plain value
 * @param global  whether every message has it, rather than this one alone
 * @param example the value an editor shows for it, as the kind writes it, or {@code null}
 * @param action  whether it is an action, placed as {@code <action:name>} around a text that runs it
 * @param exampleWords for a value that is a message, whose example is a key: that key's first text in each
 *                language the jar ships it in, by language tag; empty for any other value
 */
public record MessageArg(
        String name,
        @Nullable String kind,
        @Nullable String type,
        boolean global,
        @Nullable String example,
        boolean action,
        Map<String, String> exampleWords) {

    public MessageArg {
        // A body without it reads as no words, and the editor shows the example as it is.
        exampleWords = exampleWords == null ? Map.of() : Map.copyOf(exampleWords);
    }

    /** A placeholder whose example is not a message's key, so it has no words of its own. */
    public MessageArg(
            final String name,
            final @Nullable String kind,
            final @Nullable String type,
            final boolean global,
            final @Nullable String example,
            final boolean action) {
        this(name, kind, type, global, example, action, Map.of());
    }

    /** The same placeholder with its example message's words. */
    public MessageArg withExampleWords(final Map<String, String> words) {
        return new MessageArg(name, kind, type, global, example, action, words);
    }

    /** How the placeholder is written in a text. */
    public String token() {
        return action ? "<action:" + name + ">" : "{" + name + "}";
    }
}
