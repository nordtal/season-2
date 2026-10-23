package eu.nordtal.season.internalapi.agent;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A placeholder or action that a message declares in {@code schema.json}.
 * Roles and globals are expanded into their attributes.
 *
 * @param name the placeholder's name, dotted for the attribute of a role, or the name of an action
 * @param kind the token of the value kind, for example {@code duration}, or {@code null} for an action or a value
 *     without a kind
 * @param type the context type of the role, for example {@code player}, or {@code null} for a plain value
 * @param global whether every message has it
 * @param example the value that an editor shows, as the kind writes it, or {@code null}
 * @param action whether it is an action, written as {@code <action:name>} around a text that runs it
 * @param exampleWords for a value whose example is a message key: the first text of that key in each language, by
 *     language tag; otherwise empty
 * @param exampleValue the example read as its kind, as {@code {kind, value}}, or {@code null} where it reads as none
 */
public record MessageArg(
        String name,
        @Nullable String kind,
        @Nullable String type,
        boolean global,
        @Nullable String example,
        boolean action,
        Map<String, String> exampleWords,
        @Nullable Object exampleValue) {

    public MessageArg {
        // A body without it reads as no words.
        exampleWords = exampleWords == null ? Map.of() : Map.copyOf(exampleWords);
    }

    public MessageArg(
            final String name,
            final @Nullable String kind,
            final @Nullable String type,
            final boolean global,
            final @Nullable String example,
            final boolean action) {
        this(name, kind, type, global, example, action, Map.of(), null);
    }

    public MessageArg withExample(final Map<String, String> words, final @Nullable Object value) {
        return new MessageArg(name, kind, type, global, example, action, words, value);
    }

    public String token() {
        return action ? "<action:" + name + ">" : "{" + name + "}";
    }
}
