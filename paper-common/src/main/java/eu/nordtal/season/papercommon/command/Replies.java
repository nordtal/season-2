package eu.nordtal.season.papercommon.command;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import java.sql.SQLException;

/** The replies every server's commands share: the admin answers, which the console and Steward read. */
public final class Replies {

    public record Typed(
            @Example("/smp milestone unlock nether") String command) {}

    /** What an admin action failed on, as far as the text can name it. */
    public enum Cause {
        DATABASE,
        OTHER
    }

    /** @param cause what the action failed on */
    public record Failure(@Example("database") Cause cause) {

        /** Returns the failure of {@code failure}: the database when any exception in its chain is the database's. */
        public static Failure of(final Throwable failure) {
            for (Throwable link = failure; link != null; link = link.getCause()) {
                if (link instanceof SQLException || link.getClass().getName().startsWith("org.jdbi.")) {
                    return new Failure(Cause.DATABASE);
                }
            }
            return new Failure(Cause.OTHER);
        }
    }

    public static final DocumentSpec<Typed> CONFIRM =
            reply("admin-confirm", "Admin", Typed.class).titled("Confirmation needed");
    public static final DocumentSpec<Failure> FAILED =
            reply("admin-failed", "Admin", Failure.class).titled("Admin command failed");
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> PREVIEW_SHOWN =
            reply("preview-shown", "Admin", DocumentSpec.None.class).titled("Preview shown");
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> SETTINGS_TAKEN =
            reply("settings-taken", "Admin", DocumentSpec.None.class).titled("Settings change accepted");

    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> STATE_STORED =
            reply("state-stored", "Admin", DocumentSpec.None.class).titled("Inventory stored");
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> STACK_REMOVED =
            reply("stack-removed", "Admin", DocumentSpec.None.class).titled("Stack removed");
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> EDITOR_OPENED =
            reply("editor-opened", "Admin", DocumentSpec.None.class).titled("Inventory editor opened");

    private Replies() {}

    private static <V extends Record> DocumentSpec<V> reply(
            final String name, final String area, final Class<V> values) {
        return DocumentSpec.game("paper-common", name, area, DocumentSpec.Shape.CHAT, values);
    }
}
