package eu.nordtal.s2.internalapi.agent;

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
 */
public record MessageArg(
        String name,
        @Nullable String kind,
        @Nullable String type,
        boolean global,
        @Nullable String example,
        boolean action) {

    /** How the placeholder is written in a text. */
    public String token() {
        return action ? "<action:" + name + ">" : "{" + name + "}";
    }
}
