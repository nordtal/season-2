package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever is asking: a Minecraft player, a Discord member, or the console.
 *
 * Both identities are optional, and replies are message keys the adapter renders.
 */
public interface NordtalUser {

    /** Where the request came from, recorded for the audit trail and never used to authorise. */
    enum Origin {

        /** A slash command in the guild. */
        DISCORD,

        /** A chat command on a Paper server or on the proxy. */
        GAME,

        /** The server console, or the container's {@code mc} wrapper; always an admin. */
        CONSOLE
    }

    /** Returns their Discord id, if this surface knows one. */
    Optional<String> discordId();

    /** Returns their Minecraft UUID, if this surface knows one. */
    Optional<UUID> minecraftUuid();

    /** Returns a name for a log line or an audit entry; never parsed or compared. */
    String name();

    /** Returns the language replies are rendered in, from {@code discord_user.locale}, never the client's setting. */
    Locale locale();

    /** Returns {@code discord_user.admin} from a cache, or {@code true} for the console; never queries. */
    boolean admin();

    /** Returns which surface this is. */
    Origin origin();

    /** Says something, in their language. */
    void reply(MessageRef message);

    /** Says something with a sound where the surface can play one. */
    default void reply(final MessageRef message, final Feedback feedback) {
        reply(message);
    }

    /** Says something with a {@link Tone}, which Discord ignores. */
    default void reply(final MessageRef message, final Tone tone) {
        reply(message);
    }

    /** Says something with a sound and a {@link Tone}, the overload nearly every command uses. */
    default void reply(final MessageRef message, final Feedback feedback, final Tone tone) {
        reply(message, tone);
    }

    /** Returns one message rendered as plain text in their language, to go inside another; nothing is sent. */
    String phrase(MessageRef message);

    /** Hands back text produced elsewhere, verbatim; a command's own text goes through {@link #reply(MessageRef)}. */
    void replyLiteral(String text);
}
