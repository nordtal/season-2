package eu.nordtal.s2.steward.worker.configfile;

import org.jspecify.annotations.Nullable;

/**
 * One placeholder a message declares in its jar's {@code schema.json}.
 *
 * <p>A role the spec declares with a context type ({@code winner}, a player) arrives here already
 * expanded, one placeholder per property of its type: {@code winner.name}. So does every global
 * ({@code server.name}, {@code season.number}), which any message may use and none has to.</p>
 *
 * @param name      the placeholder's name, dotted for a role's property
 * @param component whether a Component fills it, written {@code <name>} in the text rather than
 *                  {@code {name}}
 * @param type      the context type the role has, e.g. {@code player}; {@code null} for a plain value
 * @param global    whether every message of the network has it, rather than this one alone
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
