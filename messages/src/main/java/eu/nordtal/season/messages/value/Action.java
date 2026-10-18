package eu.nordtal.season.messages.value;

import java.util.Objects;

/**
 * The code binds the command, and the text places it as {@code <action:name>[Label]</action>}.
 * Plain text keeps the label alone.
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
