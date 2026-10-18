package eu.nordtal.season.database.alert;

import eu.nordtal.season.messages.MessageRef;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One thing an admin should hear of, told in messages of the admin bundle.
 *
 * @param subject what it is about, such as {@code smp} or {@code disk}
 * @param title one line, for the lock screen and the admin channel
 * @param detail the lines below the title, may be empty
 * @param measured what it measured, empty for an event
 * @param path the Steward page that can act on it
 */
public record Alert(
        AlertType type,
        Level level,
        String subject,
        MessageRef title,
        List<MessageRef> detail,
        List<Measure> measured,
        String path) {

    /** How bad an alert is. {@link #OK} clears an earlier alert of the same type. */
    public enum Level {
        OK,
        WARN,
        DOWN;

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
        measured = List.copyOf(measured);
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("an alert's path is a page in Steward, got '" + path + "'");
        }
    }

    /** Creates an alert without measures. */
    public Alert(
            final AlertType type,
            final Level level,
            final String subject,
            final MessageRef title,
            final List<MessageRef> detail,
            final String path) {
        this(type, level, subject, title, detail, List.of(), path);
    }

    /** Creates an alert without detail and measures. */
    public Alert(
            final AlertType type, final Level level, final String subject, final MessageRef title, final String path) {
        this(type, level, subject, title, List.of(), path);
    }
}
