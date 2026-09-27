package eu.nordtal.s2.proxy.command;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.message.Tones;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * A connected player, as {@code :commands} sees them.
 *
 * Discord id, language and admin flag all come from {@link LoginRoster}, never a query.
 */
public final class VelocityUser implements NordtalUser {

    private final Player player;
    private final LoginRoster roster;
    private final Messages messages;
    private final Supplier<ToneColours> colours;

    public VelocityUser(
            final Player player,
            final LoginRoster roster,
            final Messages messages,
            final Supplier<ToneColours> colours) {
        this.player = player;
        this.roster = roster;
        this.messages = messages;
        this.colours = colours;
    }

    @Override
    public Optional<String> discordId() {
        return roster.of(player.getUniqueId()).map(LoginRoster.Session::discordId);
    }

    @Override
    public Optional<UUID> minecraftUuid() {
        return Optional.of(player.getUniqueId());
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public Locale locale() {
        return roster.localeOf(player.getUniqueId());
    }

    @Override
    public boolean admin() {
        return roster.isAdmin(player.getUniqueId());
    }

    @Override
    public Origin origin() {
        return Origin.GAME;
    }

    @Override
    public void reply(final MessageRef message) {
        player.sendMessage(render(message));
    }

    @Override
    public void reply(final MessageRef message, final Tone tone) {
        player.sendMessage(Tones.paint(render(message), tone, colours.get()));
    }

    @Override
    public String phrase(final MessageRef message) {
        // Plain text: substituted into a MiniMessage-parsed message, where a component arrives as tags.
        return PlainTextComponentSerializer.plainText().serialize(render(message));
    }

    @Override
    public void replyLiteral(final String text) {
        player.sendMessage(Component.text(text));
    }

    private Component render(final MessageRef message) {
        return MessageRenderer.of(messages).format(locale(), message);
    }
}
