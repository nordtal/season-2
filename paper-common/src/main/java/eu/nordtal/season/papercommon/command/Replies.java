package eu.nordtal.season.papercommon.command;

import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;

/** The replies every server's commands share: the admin answers, which the console and Steward read, and the help. */
public final class Replies {

    public record Typed(@Example("/poi") String command) {}

    public record Usage(
            @Example("/poi add <name>") String usage,
            @Example("Saves where you stand as a point.") String what) {}

    public static final DocumentSpec<Typed> CONFIRM = reply("admin-confirm", "Admin", Typed.class);
    public static final DocumentSpec<DocumentSpec.None> FAILED =
            reply("admin-failed", "Admin", DocumentSpec.None.class);
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> PREVIEW_SHOWN =
            reply("preview-shown", "Admin", DocumentSpec.None.class);
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> SETTINGS_TAKEN =
            reply("settings-taken", "Admin", DocumentSpec.None.class);

    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> STATE_STORED =
            reply("state-stored", "Admin", DocumentSpec.None.class);
    /** Steward shows it, never the game. */
    public static final DocumentSpec<DocumentSpec.None> STACK_REMOVED =
            reply("stack-removed", "Admin", DocumentSpec.None.class);

    public static final DocumentSpec<Typed> HELP_HEADER = reply("help-header", "Command", Typed.class);
    /** A subcommand under the header. */
    public static final DocumentSpec<Usage> HELP_LINE = reply("help-line", "Command", Usage.class);
    /** The usage of a subcommand typed without its argument, and what it does. */
    public static final DocumentSpec<Usage> HELP_USAGE = reply("help-usage", "Command", Usage.class);

    private Replies() {}

    private static <V extends Record> DocumentSpec<V> reply(
            final String name, final String area, final Class<V> values) {
        return DocumentSpec.game("paper-common", name, area, DocumentSpec.Shape.CHAT, values);
    }
}
