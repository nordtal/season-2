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
 * <h2>Everything comes out of the roster, and nothing out of a query</h2>
 * {@link LoginRoster} was filled by the login gate's own query - the one it makes anyway - and is
 * kept in step with {@code discord_user.admin} by the proxy's notification listener. So the Discord
 * id, the language and the admin flag are all map lookups here.
 *
 * <p>That is not an optimisation. Brigadier evaluates a command's {@code requires} predicate while
 * building the tree it sends to a client, on a thread that must not block, and a JDBC call there
 * would be a database round trip per player per command tree.</p>
 *
 * <h2>The language is the database's, never the client's</h2>
 * docs/i18n.md settles it: a player's language is {@code discord_user.locale}, mirrored from their
 * Discord onboarding role. A Minecraft client's own setting is not consulted anywhere in this
 * repository, and this is not the place to start.
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
        // Plain text, because the result is substituted into another message that will itself be
        // parsed as MiniMessage - and a component serialised back into that string would arrive as
        // tags rather than as styling.
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
