package eu.nordtal.season.papercommon.chat;

import static eu.nordtal.season.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.papercommon.command.Answer;
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
 * Shows an admin's player a text they are trying for a key, in its language and as this server renders the key.
 * A title, a subtitle or an action bar is shown there; every other place a key is shown, a menu or a sidebar, only
 * has the text as a chat line, since drawing it would mean opening that place.
 */
public final class Previews {

    private final Function<UUID, @Nullable Player> online;
    private final MessageRenderer renderer;

    /**
     * @param online   the player of a UUID on this server, or {@code null} for one who is not here
     * @param renderer the plugin's, so a name and a tone are drawn as this server draws them
     */
    public Previews(final Function<UUID, @Nullable Player> online, final MessageRenderer renderer) {
        this.online = Objects.requireNonNull(online, "online");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Shows the preview to the player, refused while they are not on this server. */
    public Answer show(final PlayerId player, final MessagePreview preview) {
        final Player reader = online.apply(player.value());
        if (reader == null) {
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        final Viewer viewer = Viewer.of(Locales.parse(preview.language()));
        // Painted as the key's own service paints it, which need not be this server.
        final Component text = preview.colours().isEmpty()
                ? renderer.format(viewer, preview.message(), preview.text())
                : renderer.format(viewer, preview.message(), preview.text(), paletteOf(preview.colours()));
        switch (preview.shown()) {
            case TITLE -> reader.showTitle(Title.title(text, Component.empty()));
            case SUBTITLE -> reader.showTitle(Title.title(Component.empty(), text));
            case ACTION_BAR -> reader.sendActionBar(text);
            default -> reader.sendMessage(text);
        }
        return Answer.done(MESSAGES.admin().previewShown());
    }

    /** The tones by their tags as a palette; a tone missing or unreadable keeps its default. */
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
