package eu.nordtal.s2.database;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;

/** Renders this module's messages in English, for the places that answer an admin or write a log. */
public final class DatabaseText {

    private static final Messages BUNDLE = Messages.load(DatabaseText.class.getClassLoader(), "messages/database");

    private DatabaseText() {}

    /** Returns {@code message} as English plain text. */
    public static String english(final MessageRef message) {
        return BUNDLE.format(Locales.DEFAULT, message);
    }
}
