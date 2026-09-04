package eu.nordtal.s2.networkcontrol.command;

import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    public VelocityUser(final Player player, final LoginRoster roster, final Messages messages) {
        this.player = player;
        this.roster = roster;
        this.messages = messages;
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
    public void reply(final String messageKey, final Map<String, ?> placeholders) {
        player.sendMessage(render(messageKey, placeholders));
    }

    @Override
    public String phrase(final String messageKey) {
        // Plain text, because the result is substituted into another message that will itself be
        // parsed as MiniMessage - and a component serialised back into that string would arrive as
        // tags rather than as styling.
        return PlainTextComponentSerializer.plainText().serialize(render(messageKey, Map.of()));
    }

    @Override
    public void replyLiteral(final String text) {
        player.sendMessage(Component.text(text));
    }

    private Component render(final String messageKey, final Map<String, ?> placeholders) {
        final Object[] flattened = new Object[placeholders.size() * 2];
        int index = 0;
        for (final Map.Entry<String, ?> entry : placeholders.entrySet()) {
            flattened[index++] = entry.getKey();
            flattened[index++] = String.valueOf(entry.getValue());
        }
        return MessageRenderer.of(messages).format(locale(), messageKey, flattened);
    }
}
