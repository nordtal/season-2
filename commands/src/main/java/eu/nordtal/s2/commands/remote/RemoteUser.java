package eu.nordtal.s2.commands.remote;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.command.CommandRequest;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever asked, as seen from the process that runs their command for them.
 *
 * Replies are rendered here in the row's language and collected into {@code command_request.result}; sound is dropped.
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
    public Optional<DiscordId> discordId() {
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
    public void reply(final MessageRef message) {
        lines.add(messages.format(locale, message));
    }

    @Override
    public String phrase(final MessageRef message) {
        return messages.format(locale, message);
    }

    @Override
    public void replyLiteral(final String text) {
        lines.add(text);
    }

    /** Returns everything the command said as one block of text, empty when it replied nothing. */
    public String text() {
        return String.join("\n", lines);
    }

    /** Returns how many lines came back. */
    public int lineCount() {
        return lines.size();
    }
}
