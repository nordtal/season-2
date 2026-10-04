package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Display;
import java.util.Map;
import java.util.Objects;

/**
 * A text an admin is trying for one key, shown to them alone, filled with example values; nothing is saved.
 * The bot and the game servers each take it as one kind of their inbox and render it as they render the key.
 *
 * @param message  the key, with the values its text is filled with
 * @param language the language tag the text is written in, which it is shown in
 * @param text     the text in place of the key's own, which the one validator has read
 * @param shown    where the key is shown, which decides how the recipient's screen shows it
 * @param colours  each tone's colour by its tag, as the settings of the key's own service name it; none is the
 *     recipient's own
 */
public record MessagePreview(
        MessageRef message, String language, String text, Display shown, Map<String, String> colours) {

    public MessagePreview {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(shown, "shown");
        // A row written before previews carried colours reads as none, since the codec fills a missing field with null.
        colours = Objects.requireNonNullElse(colours, Map.<String, String>of());
        colours = Map.copyOf(colours);
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
    }
}
