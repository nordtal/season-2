package eu.nordtal.s2.database.alert;

import java.util.Locale;
import java.util.Objects;

/**
 * One thing an admin should hear of, in the words every channel shows.
 *
 * @param subject a word or a few, such as {@code smp} or {@code disk}, for a list of names
 * @param title one line, for a lock screen and the admin channel
 * @param detail the admin channel's longer text, Discord markdown allowed; may be empty
 * @param path the page in Steward that can act on it
 */
public record Alert(AlertType type, Level level, String subject, String title, String detail, String path) {

    /** How bad it is; {@link #OK} clears what an earlier alert of the same type raised. */
    public enum Level {
        OK,
        WARN,
        DOWN;

        /** Returns the name the browser and a push carry: the constant, lowercased. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Alert {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(path, "path");
        if (title.isBlank()) {
            throw new IllegalArgumentException("an alert has a title");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("an alert's path is a page in Steward, got '" + path + "'");
        }
    }
}
