package eu.nordtal.s2.steward.worker.configfile;

import org.jspecify.annotations.Nullable;

/**
 * One placeholder a message declares in its jar's {@code schema.json}, with roles and globals already expanded.
 *
 * @param name the placeholder's name, dotted for a role's property
 * @param component whether a Component fills it, written {@code <name>} rather than {@code {name}}
 * @param type the role's context type, for example {@code player}; {@code null} for a plain value
 * @param global whether every message has it, rather than this one alone
 */
public record MessageArg(
        String name, boolean component, @Nullable String type, boolean global) {

    public MessageArg(final String name, final boolean component) {
        this(name, component, null, false);
    }

    /** How the placeholder is written in a text. */
    public String token() {
        return component ? "<" + name + ">" : "{" + name + "}";
    }
}
