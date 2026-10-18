package eu.nordtal.season.papercommon.tab;

import static eu.nordtal.season.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The tab list: the header of paper-common over a footer that the server passes. The header is written once, here. */
public final class TabList {

    private final MessageRenderer renderer;

    public TabList(final MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public void draw(final Player player, final Locale locale, final MessageRef footer) {
        player.sendPlayerListHeaderAndFooter(
                renderer.format(locale, MESSAGES.tab().header()), renderer.format(locale, footer));
    }

    public void drawCounted(final Player player, final Locale locale, final PlayersSpec network) {
        draw(player, locale, MESSAGES.tab().footer(Bukkit.getOnlinePlayers().size(), network.maxPlayers()));
    }
}
