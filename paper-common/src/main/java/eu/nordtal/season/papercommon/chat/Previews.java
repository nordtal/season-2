package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.Replies;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Shows an admin's player a text for a key, in the player's language and as this server renders the key.
 * A title, a subtitle or an action bar is shown there. Every other place a key appears receives a chat line.
 */
public final class Previews {

    private final Function<UUID, @Nullable Player> online;
    private final MessageRenderer renderer;

    public Previews(final Function<UUID, @Nullable Player> online, final MessageRenderer renderer) {
        this.online = Objects.requireNonNull(online, "online");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public Answer show(final PlayerId player, final MessagePreview preview) {
        final Player reader = online.apply(player.value());
        if (reader == null) {
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        final Viewer viewer = Viewer.of(Locales.parse(preview.language()));
        // Steward sends a document to the bot, never here.
        final MessageRef message = Objects.requireNonNull(preview.message(), "a game server previews a key's text");
        final String drafted = Objects.requireNonNull(preview.text(), "text");
        // The key's own service paints the text, and that need not be this server.
        final Component text = preview.colours().isEmpty()
                ? renderer.format(viewer, message, drafted)
                : renderer.format(viewer, message, drafted, paletteOf(preview.colours()));
        switch (preview.shown()) {
            case TITLE -> reader.showTitle(Title.title(Lines.joined(text), Component.empty()));
            case SUBTITLE -> reader.showTitle(Title.title(Component.empty(), Lines.joined(text)));
            case ACTION_BAR -> reader.sendActionBar(Lines.joined(text));
            default -> reader.sendMessage(text);
        }
        return Answer.done(Sent.of(Replies.PREVIEW_SHOWN));
    }

    /** Returns the tones as a palette. A missing or unreadable tone keeps its default. */
    private static Palette paletteOf(final Map<String, String> colours) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            final String hex = colours.get(tone.tag());
            if (hex != null) {
                declared.put(tone, hex);
            }
        }
        return ToneColours.parse(declared, unreadable -> {});
    }
}
