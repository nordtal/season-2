package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.spec.Display;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A text an admin tries for one key, or a drafted Discord document, shown to the admin alone with example values.
 * Nothing is saved. The bot and the game servers render it as they render the key or the document.
 *
 * @param message the key with the values its text is filled with, or {@code null} for a document
 * @param language the language tag of the text
 * @param text the text in place of the text of the key, or {@code null} for a document
 * @param shown where the key is shown. A document is shown as a Discord message
 * @param colours the colour of each tone by tag, as the settings of the key's service name it. Empty means the
 *     recipient's own
 * @param document the drafted document with its declaration, or {@code null} for a key's text
 */
public record MessagePreview(
        @Nullable MessageRef message,
        String language,
        @Nullable String text,
        Display shown,
        Map<String, String> colours,
        Documents.@Nullable Packaged document) {

    public MessagePreview {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(shown, "shown");
        // The codec fills a missing field with null.
        colours = Objects.requireNonNullElse(colours, Map.<String, String>of());
        colours = Map.copyOf(colours);
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
        if (document == null && (message == null || text == null)) {
            throw new IllegalArgumentException("a preview carries a key with its text or a document");
        }
        if (document != null && (message != null || text != null || shown != Display.DISCORD_MESSAGE)) {
            throw new IllegalArgumentException("a document is previewed as a Discord message, without a key");
        }
    }

    /** A key's text. */
    public MessagePreview(
            final MessageRef message,
            final String language,
            final String text,
            final Display shown,
            final Map<String, String> colours) {
        this(
                Objects.requireNonNull(message, "message"),
                language,
                Objects.requireNonNull(text, "text"),
                shown,
                colours,
                null);
    }

    /** A drafted Discord document, which the bot sends as a direct message. */
    public static MessagePreview of(final Documents.Packaged document, final String language) {
        return new MessagePreview(
                null, language, null, Display.DISCORD_MESSAGE, Map.of(), Objects.requireNonNull(document, "document"));
    }
}
