package eu.nordtal.s2.networkcontrol.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The proxy console, as {@code :commands} sees it.
 *
 * <h2>Why the console is an admin without asking anything</h2>
 * It is the operator. Reaching it means a shell on the production host, where the database can be
 * edited by hand anyway - and a console that had to consult the database would be unusable in
 * exactly the situation it exists for, which is the database being the thing that is broken. It is
 * the one place in this repository where something other than {@code discord_user.admin} decides,
 * and {@code PaperUser.console} says the same about the three backends.
 *
 * <h2>English, and not a fallback choice</h2>
 * A console has no account and therefore no {@code discord_user.locale}. English is what every other
 * unattributed line in this network is written in.
 */
public final class ConsoleUser implements NordtalUser {

    private final Messages messages;
    private final MessageRenderer renderer;
    private final net.kyori.adventure.audience.Audience audience;

    public ConsoleUser(final Messages messages) {
        this(messages, null);
    }

    /** @param audience where to print, or {@code null} for the JVM's own standard output */
    public ConsoleUser(final Messages messages,
                       final net.kyori.adventure.audience.Audience audience) {
        this.messages = messages;
        this.renderer = new MessageRenderer(messages);
        this.audience = audience;
    }

    @Override
    public Optional<String> discordId() {
        return Optional.empty();
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return Optional.empty();
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public Locale locale() {
        return Locale.ENGLISH;
    }

    @Override
    public boolean admin() {
        return true;
    }

    @Override
    public Origin origin() {
        return Origin.CONSOLE;
    }

    @Override
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        send(renderer.format(Locale.ENGLISH, messageKey, placeholders));
    }

    @Override
    public String phrase(final String messageKey) {
        return messages.get(Locale.ENGLISH, messageKey);
    }

    @Override
    public void replyLiteral(final String text) {
        send(Component.text(text));
    }

    /**
     * Plain text, never MiniMessage.
     *
     * <p>A console is a log file. Adventure's console audience strips the markup itself, but the
     * fallback path here is {@code System.out}, and a raw {@code <green>} in a container log is the
     * kind of thing somebody greps past.</p>
     */
    private void send(final Component component) {
        if (audience != null) {
            audience.sendMessage(component);
            return;
        }
        System.out.println(PlainTextComponentSerializer.plainText().serialize(component));
    }
}
