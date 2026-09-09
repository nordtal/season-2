package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever is asking for something - a Minecraft player, a Discord member, or the console.
 *
 * <p>Carries only the platform-independent half of a request: who is asking, whether they may, in
 * which language, and where the answer goes.</p>
 *
 * <p>Both identities are optional. The console has neither; a Discord member who has never linked
 * has no {@link #minecraftUuid()}. A player is linked in practice - the login gate refuses an
 * unlinked one - but that is another process's rule, so a command must not assume
 * {@link #discordId()} is present.</p>
 *
 * <p>Replies are message keys, never text: the adapter renders, because Paper and Velocity want an
 * Adventure {@code Component} and Discord wants a string. {@link #replyLiteral(String)} is the one
 * exception, for text that is already the answer and must not be rendered twice.</p>
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
         * <p>Always an admin ({@link #admin()} answers true) - the one place where something other
         * than {@code discord_user.admin} decides, because console access already implies the
         * ability to edit the database by hand.</p>
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
     * setting.</p>
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
     * Say something, and let the surface show at a glance whether it is good news.
     *
     * <p>Discord cannot - an embed has one colour - so the bot uses the default. See {@link Tone}.</p>
     */
    default void reply(final String messageKey, final Map<String, ?> placeholders,
                       final Tone tone) {
        reply(messageKey, placeholders);
    }

    /**
     * Say something, make a noise about it, and colour it - the overload nearly every command uses.
     *
     * <p>Sound and colour stay separate arguments because they are different facts: {@link Feedback}
     * names actions as well as outcomes, and a command saying four things in a row wants one chime
     * and four colours. The default drops the sound rather than the colour, since a surface that
     * cannot make noise is the common case.</p>
     */
    default void reply(final String messageKey, final Map<String, ?> placeholders,
                       final Feedback feedback, final Tone tone) {
        reply(messageKey, placeholders, tone);
    }

    /**
     * One message key, rendered in their language, as plain text meant to go <em>inside</em> another
     * message.
     *
     * <p>Nothing is sent. It exists because a few replies carry a placeholder that is itself
     * translated - "{what} on 2026-10-01" - and a command holds no bundle to render it with.</p>
     *
     * <p>The rendering is plain: whatever markup a surface uses is stripped or never applied, because
     * the result is substituted into another string that will be rendered again.</p>
     */
    String phrase(String messageKey);

    /**
     * Hand back text that is already the answer, verbatim.
     *
     * <p>Only for output produced elsewhere and passed through unchanged - the updater's report.
     * Anything a command composes itself goes through {@link #reply(String, Map)}.</p>
     */
    void replyLiteral(String text);
}
