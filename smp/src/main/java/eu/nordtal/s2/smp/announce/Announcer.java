package eu.nordtal.s2.smp.announce;

import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.announce.AnnounceCommands;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.database.command.NewCommandRequest;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Sends the SMP's lines into the Discord announcement channels, one {@code command_request} row per language.
 *
 * Fire and forget on the async executor: the row keeps for an hour, so a bot that was down posts when it is back.
 */
public final class Announcer {

    /** The languages a line is rendered in. */
    public static final List<String> LANGUAGES = Languages.NETWORK.tags();

    /** How long a row waits for the bot before it is abandoned: a restart, not an outage. */
    public static final Duration KEEP = Duration.ofHours(1);

    /** {@code command_request.requested_by} for a row no person asked for. */
    public static final String SENDER = "smp";

    private final CommandRequests requests;
    private final Messages messages;
    private final Executor async;
    private final BiConsumer<String, Throwable> warn;

    private final Clock clock;

    public Announcer(
            final CommandRequests requests,
            final Messages messages,
            final Executor async,
            final BiConsumer<String, Throwable> warn,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.async = Objects.requireNonNull(async, "async");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /**
     * Renders {@code message} in every language and sends one row per language.
     * @param message a message from this module's spec; the announcement keys carry no glyph, which Discord would draw
     * as a box
     */
    public void announce(final MessageRef message) {
        Objects.requireNonNull(message, "message");
        announce(locale -> message);
    }

    /**
     * Renders a message whose values depend on the language, such as a milestone's name, and sends it.
     * @param message what to send, asked once per language
     */
    public void announce(final java.util.function.Function<Locale, MessageRef> message) {
        Objects.requireNonNull(message, "message");
        async.execute(() -> {
            for (final String tag : LANGUAGES) {
                final Locale locale = Locales.parse(tag);
                final MessageRef filled = message.apply(locale);
                final String text = PlainTextComponentSerializer.plainText()
                        .serialize(MessageRenderer.of(messages).format(locale, filled));
                try {
                    requests.submit(row(tag, text, clock.instant()));
                } catch (final RuntimeException failure) {
                    // One language failing must not cost the other its line.
                    warn.accept("could not send the " + tag + " announcement for " + filled.key(), failure);
                }
            }
        });
    }

    /** The row for one language, visible for the test. */
    static NewCommandRequest row(final String tag, final String text, final Instant now) {
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
                now.plus(KEEP));
    }
}
