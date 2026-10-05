package eu.nordtal.season.database.alert;

import eu.nordtal.season.messages.MessageRef;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One thing an admin should hear of, told in messages of the admin bundle, which each channel renders for its reader.
 *
 * @param subject what it is about, such as {@code smp} or {@code disk}: a name, which tells two alerts of a type apart
 * @param title one line, for a lock screen and the admin channel
 * @param detail the lines below it; may be empty
 * @param path the page in Steward that can act on it
 */
public record Alert(
        AlertType type, Level level, String subject, MessageRef title, List<MessageRef> detail, String path) {

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
        detail = List.copyOf(detail);
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("an alert's path is a page in Steward, got '" + path + "'");
        }
    }

    /** An alert whose title says it all. */
    public Alert(
            final AlertType type, final Level level, final String subject, final MessageRef title, final String path) {
        this(type, level, subject, title, List.of(), path);
    }
}
