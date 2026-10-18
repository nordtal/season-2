package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Display;
import java.util.Map;
import java.util.Objects;

/**
 * A text that an admin tries for one key, shown to the admin alone with example values. Nothing is saved.
 * The bot and the game servers take it as a kind of their inbox and render it as they render the key.
 *
 * @param message the key with the values its text is filled with
 * @param language the language tag of the text
 * @param text the text in place of the text of the key
 * @param shown where the key is shown
 * @param colours the colour of each tone by tag, as the settings of the key's service name it. Empty means the
 *     recipient's own
 */
public record MessagePreview(
        MessageRef message, String language, String text, Display shown, Map<String, String> colours) {

    public MessagePreview {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(shown, "shown");
        // The codec fills a missing field with null.
        colours = Objects.requireNonNullElse(colours, Map.<String, String>of());
        colours = Map.copyOf(colours);
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
    }
}
