package eu.nordtal.season.database;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import java.util.List;

/** Renders this module's two bundles in English, for the places that answer an admin or write a log. */
public final class DatabaseText {

    private static final Messages BUNDLE =
            Messages.load(DatabaseText.class.getClassLoader(), List.of("messages/database", "messages/admin"));

    private DatabaseText() {}

    /** Returns {@code message} as English plain text. */
    public static String english(final MessageRef message) {
        return BUNDLE.format(Locales.DEFAULT, message);
    }
}
