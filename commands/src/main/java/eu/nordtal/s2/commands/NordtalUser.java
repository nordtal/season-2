package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever is asking for something - a Minecraft player, a Discord member, or the console.
 *
 * <h2>Why this type exists at all</h2>
 * A command has two halves. The <b>front</b> - who is asking, may they, in which language, what is
 * said back - is the same wherever the command was typed. The <b>back</b> - the effect - is bound to
 * one process, because {@code /smp farmreset} deletes a world and can only run in the JVM that has
 * that world open. Everything this interface carries is the front half, which is exactly the half
 * that can be written once and adapted three times.
 *
 * <p>It is also a type this repository was already missing before commands made it obvious.
 * {@code account_link} <em>is</em> the table that says a Discord member and a Minecraft player are
 * one person; until now nothing named that person.</p>
 *
 * <h2>Both identities are optional, and that is not a shrug</h2>
 * The console has neither. A Discord member who has never linked has no {@link #minecraftUuid()}.
 * A player is always linked in practice - the login gate refuses an unlinked one - but a command
 * must not be written as though {@link #discordId()} were guaranteed, because the gate is a
 * different process's rule and this module cannot enforce it.
 *
 * <p>A command that genuinely needs one of them says so by asking for it, and gets a refusal it can
 * render rather than an {@link Optional} it will forget to check.</p>
 *
 * <h2>The reply is a key, never a sentence</h2>
 * {@link #reply(String, Map)} takes a message key and its placeholders, not text. Rendering happens
 * in the adapter, because the three surfaces do not render alike: Paper and Velocity want an
 * Adventure {@code Component}, and Discord wants a string it can put in an ephemeral message. A
 * command that built a sentence here would have to pick one of those, and would hardcode a language
 * while doing it - which docs/architecture.md already calls a bug rather than a shortcut.
 *
 * <p>{@link #replyLiteral(String)} is the one exception and it is deliberately ugly to type: it
 * exists for text that is <em>already</em> the answer and must not be re-rendered, which today means
 * the updater's report. docs/updater.md's rule is that nothing is rendered twice - a second
 * rendering somewhere is the thing that eventually disagrees with the first.</p>
 */
public interface NordtalUser {

    /** Where the request came from. Recorded for the audit trail, and never used to authorise. */
    enum Origin {

        /** A slash command in the guild. */
        DISCORD,

        /** A chat command on a Paper server or on the proxy. */
        GAME,

        /**
         * The server console, or the container's {@code mc} wrapper.
         *
         * <p>The console is the operator and is always an admin ({@link #admin()} answers true for
         * it) - which is the one place in this repository where something other than
         * {@code discord_user.admin} decides. That is not a second admin list: it is the physical
         * access that would let somebody edit the database by hand anyway.</p>
         */
        CONSOLE
    }

    /** Their Discord id, if this surface knows one. Empty for the console. */
    Optional<String> discordId();

    /** Their Minecraft UUID, if this surface knows one. Empty for the console. */
    Optional<UUID> minecraftUuid();

    /**
     * Something to put in a log line or an audit entry - a Minecraft name, a Discord tag, or
     * {@code "console"}. Never parsed, never compared, only read by people.
     */
    String name();

    /**
     * The language everything said back to them is rendered in.
     *
     * <p>{@code discord_user.locale} through {@code account_link}, the same one
     * {@link eu.nordtal.s2.common.message.PlayerLocales} resolves - never the Minecraft client's own
     * setting, for the reason docs/i18n.md gives.</p>
     */
    Locale locale();

    /**
     * {@code discord_user.admin}, or {@code true} for the console.
     *
     * <p>Read from a cache, never queried here: this is called from Brigadier's {@code requires}
     * predicate, which runs on the main thread while a client's command tree is built.</p>
     */
    boolean admin();

    /** Which surface this is. */
    Origin origin();

    /** Say something, in their language. */
    void reply(String messageKey, Map<String, ?> placeholders);

    /** Say something with no placeholders. */
    default void reply(final String messageKey) {
        reply(messageKey, Map.of());
    }

    /**
     * Say something, and make a noise about it where a noise is possible.
     *
     * <p>Discord has no sound and ignores the {@link Feedback}; Paper plays it. The default
     * forwards, so a surface that cannot make noise implements nothing.</p>
     */
    default void reply(final String messageKey, final Map<String, ?> placeholders,
                       final Feedback feedback) {
        reply(messageKey, placeholders);
    }

    /**
     * Hand back text that is already the answer, verbatim.
     *
     * <p>Only for output produced elsewhere and passed through unchanged - the updater's report is
     * the case this exists for. Anything a command composes itself goes through
     * {@link #reply(String, Map)}, or it is a sentence in one language.</p>
     */
    void replyLiteral(String text);
}
