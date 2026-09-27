package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;

/**
 * An admin who ran a slash command, as {@code :commands} sees them, with no Minecraft UUID.
 *
 * Every reply appends and re-sends the whole text, since an interaction has exactly one message.
 */
public final class DiscordUser implements NordtalUser {

    private final User user;
    private final Locale locale;
    private final boolean admin;
    private final InteractionHook hook;
    private final Messages messages;

    private final List<String> lines = new ArrayList<>();

    public DiscordUser(
            final User user,
            final Locale locale,
            final boolean admin,
            final InteractionHook hook,
            final Messages messages) {
        this.user = Objects.requireNonNull(user, "user");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.admin = admin;
        this.hook = Objects.requireNonNull(hook, "hook");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public Optional<String> discordId() {
        return Optional.of(user.getId());
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return Optional.empty();
    }

    @Override
    public String name() {
        return user.getName();
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
        return Origin.DISCORD;
    }

    @Override
    public void reply(final MessageRef message) {
        say(render(message));
    }

    @Override
    public String phrase(final MessageRef message) {
        return render(message);
    }

    @Override
    public void replyLiteral(final String text) {
        say(text);
    }

    /** Returns the interaction being answered, for a caller that attaches components. */
    public InteractionHook hook() {
        return hook;
    }

    /** Returns everything said so far. */
    public String text() {
        synchronized (lines) {
            return String.join("\n\n", lines);
        }
    }

    /** Appends one line and resends the whole answer, under a lock since {@code Outbox} answers from other threads. */
    private void say(final String line) {
        final String all;
        synchronized (lines) {
            lines.add(line);
            all = String.join("\n\n", lines);
        }
        // Clears components, so a used confirmation button cannot run twice.
        hook.editOriginal(all).setComponents(List.of()).queue();
    }

    private String render(final MessageRef message) {
        // Messages' own substitution, which reports a placeholder mismatch.
        return messages.format(locale, message);
    }
}
