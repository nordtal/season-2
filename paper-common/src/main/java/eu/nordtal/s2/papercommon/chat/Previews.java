package eu.nordtal.s2.papercommon.chat;

import static eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.inbox.MessagePreview;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Viewer;
import eu.nordtal.s2.papercommon.command.Answer;
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
        final Component text =
                renderer.format(Viewer.of(Locales.parse(preview.language())), preview.message(), preview.text());
        switch (preview.shown()) {
            case TITLE -> reader.showTitle(Title.title(text, Component.empty()));
            case SUBTITLE -> reader.showTitle(Title.title(Component.empty(), text));
            case ACTION_BAR -> reader.sendActionBar(text);
            default -> reader.sendMessage(text);
        }
        return Answer.done(MESSAGES.admin().previewShown());
    }
}
