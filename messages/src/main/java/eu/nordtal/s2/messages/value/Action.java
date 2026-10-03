package eu.nordtal.s2.messages.value;

import java.util.Objects;

/**
 * What clicking a part of a message does: bound by the code, placed by the text.
 * The text writes {@code <action:name>[Label]</action>}; plain text keeps the label alone.
 *
 * @param command the command the click runs, with its slash
 */
public record Action(String command) {

    public Action {
        Objects.requireNonNull(command, "command");
        if (!command.startsWith("/")) {
            throw new IllegalArgumentException("an action runs a command, and " + command + " is none");
        }
    }
}
