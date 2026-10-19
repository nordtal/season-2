package eu.nordtal.season.papercommon.tab;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The tab list, paper-common's header over a footer that the server passes.
 * The header is one document every server shows, since the client keeps it across a server change. The footer is a
 * document of each server's own.
 */
public final class TabList {

    /** The players online and the network's limit, which a counting footer shows. */
    public record Counted(long online, long max) {}

    static final DocumentSpec<DocumentSpec.None> HEADER =
            DocumentSpec.game("paper-common", "tab-header", "Tab list", DocumentSpec.Shape.TAB_LIST);

    private final MessageRenderer renderer;

    public TabList(final MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Declares a server's footer that names no value. */
    public static DocumentSpec<DocumentSpec.None> footer(final String namespace) {
        return DocumentSpec.game(namespace, "tab-footer", "Tab list", DocumentSpec.Shape.TAB_LIST);
    }

    /** Declares a server's footer that counts its players. */
    public static DocumentSpec<Counted> countedFooter(final String namespace) {
        return DocumentSpec.game(namespace, "tab-footer", "Tab list", DocumentSpec.Shape.TAB_LIST, Counted.class);
    }

    public void draw(final Player player, final Locale locale, final Component footer) {
        player.sendPlayerListHeaderAndFooter(renderer.format(locale, Sent.of(HEADER)), footer);
    }

    public void drawCounted(
            final Player player, final Locale locale, final DocumentSpec<Counted> footer, final PlayersSpec network) {
        draw(
                player,
                locale,
                renderer.format(
                        locale,
                        footer.with(new Counted(Bukkit.getOnlinePlayers().size(), network.maxPlayers()))));
    }
}
