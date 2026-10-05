package eu.nordtal.season.proxy.command;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messagerendering.Tones;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * A connected player a command answers, in their language with a tone.
 *
 * Discord id, language and admin flag all come from {@link LoginRoster}, never a query.
 */
public final class VelocityUser {

    private final Player player;
    private final LoginRoster roster;
    private final MessageRenderer renderer;
    private final Supplier<ToneColours> colours;

    public VelocityUser(
            final Player player,
            final LoginRoster roster,
            final MessageRenderer renderer,
            final Supplier<ToneColours> colours) {
        this.player = player;
        this.roster = roster;
        this.renderer = renderer;
        this.colours = colours;
    }

    public Optional<DiscordId> discordId() {
        return roster.of(player.getUniqueId()).map(LoginRoster.Session::discordId);
    }

    public Optional<UUID> minecraftUuid() {
        return Optional.of(player.getUniqueId());
    }

    public String name() {
        return player.getUsername();
    }

    public Locale locale() {
        return roster.localeOf(player.getUniqueId());
    }

    public boolean admin() {
        return roster.isAdmin(player.getUniqueId());
    }

    public void reply(final MessageRef message) {
        player.sendMessage(render(message));
    }

    public void reply(final MessageRef message, final Tone tone) {
        player.sendMessage(Tones.paint(render(message), tone, colours.get()));
    }
    /** Replies with a tone; the proxy plays no sound, so the feedback only names what kind of answer it is. */
    public void reply(final MessageRef message, final Feedback feedback, final Tone tone) {
        reply(message, tone);
    }

    public String phrase(final MessageRef message) {
        // Plain text: substituted into a MiniMessage-parsed message, where a component arrives as tags.
        return PlainTextComponentSerializer.plainText().serialize(render(message));
    }

    public void replyLiteral(final String text) {
        player.sendMessage(Component.text(text));
    }

    private Component render(final MessageRef message) {
        return renderer.format(locale(), message);
    }
}
