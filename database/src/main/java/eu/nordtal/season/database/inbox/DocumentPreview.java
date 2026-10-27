package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.spec.Display;
import java.util.Objects;

/**
 * A drafted document shown to the admin alone with example values. Nothing is saved.
 * The bot sends a Discord one as a direct message, a game server shows a game one, each with its own declaration.
 *
 * @param namespace the namespace of the drafted document
 * @param name the name of the drafted document in its namespace
 * @param language the language tag of the draft
 * @param shown the place the document is shown in
 * @param document the draft
 */
public record DocumentPreview(String namespace, String name, String language, Display shown, Document document) {

    public DocumentPreview {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(document, "document");
        if (language.isBlank()) {
            throw new IllegalArgumentException("a preview is in one language, got none");
        }
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
