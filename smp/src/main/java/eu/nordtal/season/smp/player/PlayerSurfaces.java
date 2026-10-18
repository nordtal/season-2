package eu.nordtal.season.smp.player;

import eu.nordtal.season.database.access.PlayerIdentity;
import eu.nordtal.season.displaytags.nametag.NameTagManager;
import eu.nordtal.season.displaytags.nametag.PlayerNameTag;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.tab.TabList;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Writes a player to the tab list and the nametag, which DisplayTags renders.
 *
 * A nametag shows the player looked at, so one content serves every viewer.
 */
public final class PlayerSurfaces {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerComposition composition;
    private final TabList tabList;
    private final NameTagManager nameTags;

    /** The player limit of the network, shown in the footer. */
    private final PlayersSpec network;

    public PlayerSurfaces(
            final Plugin plugin,
            final Identities identities,
            final PlayerComposition composition,
            final MessageRenderer messages,
            final PlayersSpec network,
            final NameTagManager nameTags) {
        this.nameTags = nameTags;
        this.network = network;
        this.plugin = plugin;
        this.identities = identities;
        this.composition = composition;
        this.tabList = new TabList(messages);
    }

    /** Main thread only. */
    public void refresh(final Player player) {
        final PlayerIdentity identity = identities.of(player.getUniqueId());
        player.playerListName(composition.tabList(player.getName(), identity));

        player.setPlayerListOrder((int) Math.min(Integer.MAX_VALUE, identity.playtimeSeconds() / 60L));

        sendTabListFrame(player, identity);

        applyNameTag(player, identity);
    }

    /** Main thread only. */
    public void changed(final PlayerIdentity identity) {
        PaperScheduler.of(plugin).onMain(() -> {
            final Player player = Bukkit.getPlayer(identity.player().value());
            if (player != null) {
                refresh(player);
            }
        });
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    /**
     * Writes the composition onto a nametag that DisplayTags has already created. It never creates one.
     *
     * A tag created later has no text. {@code onNameTagCreate} fills it.
     */
    private void applyNameTag(final Player player, final PlayerIdentity identity) {
        final PlayerNameTag tag = nameTags.getByPlayer(player);
        if (tag == null) {
            return;
        }
        write(tag, player, identity);
    }

    /**
     * Writes the composition onto a tag that DisplayTags has just created.
     * Call it from a {@code NameTagCreateEvent} handler.
     */
    public void applyTo(final PlayerNameTag tag) {
        final Player player = tag.getPlayer();
        write(tag, player, identities.of(player.getUniqueId()));
    }

    private void write(final PlayerNameTag tag, final Player player, final PlayerIdentity identity) {
        final Component line = composition.nameTag(player.getName(), identity);
        tag.getData().setLines(List.of(MiniMessage.miniMessage().serialize(line)));
        tag.updateForViewers();
    }

    /**
     * Writes the header and the footer of the tab list in the language of the reader.
     *
     * The logo is a placeholder from {@link Glyphs}, not a private-use character in a properties file.
     */
    private void sendTabListFrame(final Player player, final PlayerIdentity identity) {
        tabList.drawCounted(player, identity.language(), network);
    }
}
