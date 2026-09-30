package eu.nordtal.s2.proxy.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * The proxy console, as {@code :commands} sees it.
 *
 * It is an admin without asking, so it works while the database is broken, and it reads English.
 */
public final class ConsoleUser implements NordtalUser {

    private final MessageRenderer renderer;
    private final @Nullable Audience audience;

    public ConsoleUser(final Messages messages) {
        this(messages, null);
    }

    /** Prints to {@code audience}, or to standard output when it is {@code null}. */
    public ConsoleUser(final Messages messages, final @Nullable Audience audience) {
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
    public void reply(final MessageRef message) {
        send(renderer.format(Locale.ENGLISH, message));
    }

    @Override
    public String phrase(final MessageRef message) {
        return PlainTextComponentSerializer.plainText().serialize(renderer.format(Locale.ENGLISH, message));
    }

    @Override
    public void replyLiteral(final String text) {
        send(Component.text(text));
    }

    /** Plain text, never MiniMessage, since the fallback is {@code System.out}. */
    private void send(final Component component) {
        if (audience != null) {
            audience.sendMessage(component);
            return;
        }
        System.out.println(PlainTextComponentSerializer.plainText().serialize(component));
    }
}
