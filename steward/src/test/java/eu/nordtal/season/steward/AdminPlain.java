package eu.nordtal.season.steward;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;

/** The admin bundle in plain English, for a test that reads what an admin would. */
public final class AdminPlain {

    private static final Messages ADMIN = Messages.load(AdminPlain.class.getClassLoader(), "messages/admin");

    private AdminPlain() {}

    /** Returns the admin bundle itself, for a renderer under test. */
    public static Messages bundle() {
        return ADMIN;
    }

    /** Returns one message as plain English. */
    public static String of(final MessageRef message) {
        return ADMIN.format(Locale.ENGLISH, message);
    }

    /** Returns each message as plain English. */
    public static List<String> of(final List<MessageRef> messages) {
        return messages.stream().map(AdminPlain::of).toList();
    }
}
