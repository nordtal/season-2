package eu.nordtal.s2.steward.worker.configfile;

import org.jetbrains.annotations.NotNull;

/**
 * One placeholder a message declares in its jar's {@code schema.json}.
 *
 * @param name      the placeholder's name
 * @param component whether a Component fills it, written {@code <name>} in the text rather than
 *                  {@code {name}}
 */
public record MessageArg(@NotNull String name, boolean component) {

    /** How the placeholder is written in a text. */
    public @NotNull String token() {
        return component ? "<" + name + ">" : "{" + name + "}";
    }
}
