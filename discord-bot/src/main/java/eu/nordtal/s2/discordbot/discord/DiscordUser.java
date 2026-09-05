package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.Messages;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An admin who ran a slash command, as {@code :commands} sees them.
 *
 * <h2>Why the replies accumulate</h2>
 * A shared command may say more than one thing - {@code /phase smp-start} says what the date is now
 * and then how much paid access moved with it - and a Discord interaction has exactly one message.
 * {@code editOriginal} replaces it, so sending each line on its own would leave the admin looking at
 * the last one and never seeing the first. Every reply therefore appends and re-sends the whole
 * text, which is one REST call per line and two or three per command.
 *
 * <p>The alternative, follow-up messages, was rejected: three ephemeral messages for one command is
 * how season 1's bot read, and the second and third of them are indistinguishable from a bug.</p>
 *
 * <h2>What this class does not carry</h2>
 * No Minecraft UUID. The command layer is written not to assume one exists - and a Discord admin who
 * has never linked an account is an ordinary case here, unlike on the proxy where the login gate
 * refuses one. {@code /phase} needs neither, and asks for neither.
 */
public final class DiscordUser implements NordtalUser {

    private final User user;
    private final Locale locale;
    private final boolean admin;
    private final InteractionHook hook;
    private final Messages messages;

    private final List<String> lines = new ArrayList<>();

    public DiscordUser(final User user, final Locale locale, final boolean admin,
                       final InteractionHook hook, final Messages messages) {
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
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        say(render(messageKey, placeholders));
    }

    @Override
    public String phrase(final String messageKey) {
        return render(messageKey, Map.of());
    }

    @Override
    public void replyLiteral(final String text) {
        say(text);
    }

    /** The interaction being answered, for a caller that wants to attach components. */
    public InteractionHook hook() {
        return hook;
    }

    /** Everything said so far, for a caller that wants to send it with components attached. */
    public String text() {
        synchronized (lines) {
            return String.join("\n\n", lines);
        }
    }

    /**
     * One more line, and the whole answer resent.
     *
     * <h2>Why the list is locked</h2>
     * More than one thread reaches it. This user is built on a JDA worker thread, and a command
     * whose target is another process is then answered by {@code Outbox} - from its own scheduler,
     * and again from the task that gives up waiting. Two of those three can overlap, and an
     * unsynchronised {@link ArrayList} written from two threads loses a line, sends a stale one, or
     * fails inside the list itself.
     *
     * <p>The join happens under the same lock, so the text sent is the text the list held at the
     * moment this line was added rather than whatever it holds by the time the edit is built.</p>
     */
    private void say(final String line) {
        final String all;
        synchronized (lines) {
            lines.add(line);
            all = String.join("\n\n", lines);
        }
        // Components are cleared: by the time a command is replying, any confirmation buttons that
        // led here have been used and a button that still works would run it a second time.
        hook.editOriginal(all).setComponents(List.of()).queue();
    }

    private String render(final String messageKey, final Map<String, ?> placeholders) {
        // Messages' own named substitution, not a hand-rolled replace: it is what reports a
        // placeholder the template does not carry, and a template placeholder nothing supplied.
        return messages.format(locale, messageKey, placeholders);
    }
}
