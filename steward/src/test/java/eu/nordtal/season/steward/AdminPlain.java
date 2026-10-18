package eu.nordtal.season.steward;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;

/** The admin bundle in plain English. */
public final class AdminPlain {

    private static final Messages ADMIN = Messages.load(AdminPlain.class.getClassLoader(), "messages/admin");

    private AdminPlain() {}

    public static Messages bundle() {
        return ADMIN;
    }

    public static String of(final MessageRef message) {
        return ADMIN.format(Locale.ENGLISH, message);
    }

    public static List<String> of(final List<MessageRef> messages) {
        return messages.stream().map(AdminPlain::of).toList();
    }
}
