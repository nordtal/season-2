package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.spec.Display;
import java.util.Objects;

/**
 * A drafted document shown to the admin alone with example values. Nothing is saved.
 * The bot sends a Discord document as a direct message, and a game server shows a game one.
 *
 * @param language the language tag of the draft
 * @param document the drafted document with its declaration
 */
public record DocumentPreview(String language, Documents.Packaged document) {

    public DocumentPreview {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(document, "document");
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
    }

    /** Returns the place the document is shown in. */
    public Display shown() {
        return shownOf(document.declaration().shape());
    }

    /** Returns the place a document of {@code shape} is shown in. Every Discord document is a message. */
    public static Display shownOf(final DocumentSpec.Shape shape) {
        return switch (shape) {
            case MESSAGE, MODAL, PLAIN, CHANNEL_NAME -> Display.DISCORD_MESSAGE;
            case MENU -> Display.GUI;
            case CHAT -> Display.CHAT;
            case TAB_LIST -> Display.TAB_LIST;
            case KICK_SCREEN -> Display.KICK_SCREEN;
            case TITLE -> Display.TITLE;
            case BOSS_BAR -> Display.BOSS_BAR;
            case SERVER_LIST -> Display.SERVER_LIST;
            case ACTION_BAR -> Display.ACTION_BAR;
            case HOLOGRAM -> Display.HOLOGRAM;
        };
    }
}
