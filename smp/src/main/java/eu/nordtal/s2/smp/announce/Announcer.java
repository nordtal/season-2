package eu.nordtal.s2.smp.announce;

import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.announce.AnnounceCommands;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * The SMP's line into the Discord announcement channels.
 *
 * <p>Nothing here talks to Discord. A moment worth announcing is rendered once per language as
 * plain text, and each rendering becomes one {@code command_request} row for the bot, which posts
 * it into that language's channel.</p>
 *
 * <p>Fire and forget: the ceremony in game must not wait for Discord, and a bot that is down for
 * ten minutes posts when it is back because the row keeps for an hour. The database call is on the
 * plugin's async executor, never the server thread.</p>
 */
public final class Announcer {

    /**
     * The languages a line is rendered in: this module's two bundles. A third needs a bundle, a
     * tag here, and an announcement channel on the bot's side.
     */
    public static final List<String> LANGUAGES = List.of("de", "en");

    /** How long a row waits for the bot before it is abandoned: a restart, not an outage. */
    public static final Duration KEEP = Duration.ofHours(1);

    /** {@code command_request.requested_by} for a row no person asked for. */
    public static final String SENDER = "smp";

    private final CommandRequests requests;
    private final Messages messages;
    private final Executor async;
    private final BiConsumer<String, Throwable> warn;

    public Announcer(final CommandRequests requests, final Messages messages, final Executor async,
                     final BiConsumer<String, Throwable> warn) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.async = Objects.requireNonNull(async, "async");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Renders {@code message} in every language and sends one row per language.
     *
     * @param message a message from this module's spec, plain text once rendered - a glyph in it
     *                would reach Discord as a box, so the announcement keys carry none
     */
    public void announce(final MessageRef message) {
        Objects.requireNonNull(message, "message");
        announce(locale -> message);
    }

    /**
     * The same, with a message whose values depend on the language - a milestone's name is one.
     *
     * @param message what to send, asked once per language
     */
    public void announce(final java.util.function.Function<Locale, MessageRef> message) {
        Objects.requireNonNull(message, "message");
        async.execute(() -> {
            for (final String tag : LANGUAGES) {
                final Locale locale = Locales.parse(tag);
                final MessageRef filled = message.apply(locale);
                final String text = PlainTextComponentSerializer.plainText().serialize(
                        MessageRenderer.of(messages).format(locale, filled));
                try {
                    requests.submit(row(tag, text));
                } catch (final RuntimeException failure) {
                    // One language failing must not cost the other its line, and the ceremony in
                    // game has already happened either way.
                    warn.accept("could not send the " + tag + " announcement for " + filled.key(), failure);
                }
            }
        });
    }

    /** The row for one language, visible for the test that pins its shape. */
    static NewCommandRequest row(final String tag, final String text) {
        final Values values = new Values(AnnounceCommands.ANNOUNCE, Map.of("language", tag, "text", text));
        return new NewCommandRequest(
                AnnounceCommands.ANNOUNCE.target().name(),
                String.join(" ", AnnounceCommands.ANNOUNCE.path()),
                RequestArguments.encode(AnnounceCommands.ANNOUNCE, values),
                "CONSOLE",
                SENDER,
                Optional.empty(),
                Optional.empty(),
                tag,
                Instant.now().plus(KEEP));
    }
}
