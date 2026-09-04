package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.command.CommandRequest;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever asked, as seen from the process that is running their command for them.
 *
 * <h2>Replying is collecting</h2>
 * There is nobody here to talk to - the asker is in another JVM, waiting on a row. So every
 * {@code reply} is rendered in the language the row carries and appended, and the whole text is
 * written back into {@code command_request.result} when the command returns. The asking surface
 * prints it verbatim.
 *
 * <p><b>Rendered here, printed there</b> is a deliberate inversion of the usual rule that a command
 * hands back a key and the adapter renders it. It is only sound because a command that can travel
 * names keys from {@code :commands}' own bundle, which carries no markup at all - MiniMessage and
 * Discord's markdown cannot both live in one string, so the shared bundle has neither and plain text
 * is correct on both sides. {@code MessageBundlesTest} is what keeps that true; if it ever stops
 * being true, this class is where the damage shows up, as literal {@code <green>} in a Discord
 * message.</p>
 *
 * <h2>The admin flag was re-read, and is not the one from the row</h2>
 * It is passed in by {@link CommandInbox} after the row was claimed, because the whole point of
 * checking twice is that {@code discord_user.admin} can change while a request is in flight. Nothing
 * about permission travels on the row.
 *
 * <h2>Sound is dropped, and that is the honest answer</h2>
 * {@link #reply(String, Map, eu.nordtal.s2.common.feedback.Feedback)} falls through to the plain
 * one. A {@code Feedback} is a noise made at somebody standing in a world; the person who typed this
 * command is in Discord, or on another server. Playing it to nobody, or worse to whoever happens to
 * share their UUID's server, is not a better failure than silence.
 */
public final class RemoteUser implements NordtalUser {

    private final CommandRequest request;
    private final Messages messages;
    private final Locale locale;
    private final boolean admin;
    private final List<String> lines = new ArrayList<>();

    public RemoteUser(final CommandRequest request, final Messages messages, final boolean admin) {
        this.request = Objects.requireNonNull(request, "request");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locale = Locales.parse(request.locale());
        this.admin = admin;
    }

    @Override
    public Optional<String> discordId() {
        return request.discordId();
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return request.minecraftId();
    }

    @Override
    public String name() {
        return request.requestedBy();
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public boolean admin() {
        return admin;
    }

    @Override
    public Origin origin() {
        return Origin.valueOf(request.source());
    }

    @Override
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        lines.add(messages.format(locale, messageKey, placeholders));
    }

    @Override
    public String phrase(final String messageKey) {
        return messages.get(locale, messageKey);
    }

    @Override
    public void replyLiteral(final String text) {
        lines.add(text);
    }

    /**
     * Everything the command said, as one block of text.
     *
     * <p>Empty when a command replied nothing at all, which is not a failure - it is a command that
     * did its work silently. {@link CommandInbox} turns that into a key of its own rather than
     * settling the row with an empty answer, because "it worked and said nothing" and "it never ran"
     * look identical to somebody watching a spinner.</p>
     */
    public String text() {
        return String.join("\n", lines);
    }

    /** How many lines came back. For the inbox's "said nothing at all" case, and for tests. */
    public int lineCount() {
        return lines.size();
    }
}
