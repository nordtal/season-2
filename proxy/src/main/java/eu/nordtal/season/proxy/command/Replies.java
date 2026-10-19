package eu.nordtal.season.proxy.command;

import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import org.jspecify.annotations.Nullable;

/** The replies of the proxy's commands and its private messages. */
public final class Replies {

    /** The command whose description follows the usage. */
    public enum Described {
        MSG,
        WHISPER,
        R
    }

    /** @param command the command to describe, {@code null} for the usage alone */
    public record Help(
            @Example("/msg <player> <message>") String usage,
            @Nullable Described command) {}

    /**
     * @param flag the other player's language flag
     * @param admin the other player's admin tag with a leading space, or empty
     */
    public record PrivateLine(
            @Example("🇬🇧") String flag,
            PlayerContext partner,
            String admin,
            @Example("See you at spawn") String message) {}

    public static final DocumentSpec<DocumentSpec.None> NOT_FROM_CONSOLE =
            reply("command-not-from-console", "Commands", DocumentSpec.None.class);
    public static final DocumentSpec<DocumentSpec.None> PLAYER_OFFLINE =
            reply("command-player-offline", "Commands", DocumentSpec.None.class);
    /** The usage of a command typed without its arguments, and what it does. */
    public static final DocumentSpec<Help> HELP = reply("command-help", "Commands", Help.class);

    /** The sender's copy of a private message. */
    public static final DocumentSpec<PrivateLine> SENT = reply("msg-sent", "Private messages", PrivateLine.class);
    /** The recipient's copy of a private message. */
    public static final DocumentSpec<PrivateLine> RECEIVED =
            reply("msg-received", "Private messages", PrivateLine.class);

    public static final DocumentSpec<DocumentSpec.None> SELF =
            reply("msg-self", "Private messages", DocumentSpec.None.class);
    public static final DocumentSpec<DocumentSpec.None> NO_PARTNER =
            reply("msg-no-partner", "Private messages", DocumentSpec.None.class);
    public static final DocumentSpec<DocumentSpec.None> FAILED =
            reply("msg-failed", "Private messages", DocumentSpec.None.class);

    private Replies() {}

    private static <V extends Record> DocumentSpec<V> reply(
            final String name, final String area, final Class<V> values) {
        return DocumentSpec.game("proxy", name, area, DocumentSpec.Shape.CHAT, values);
    }
}
