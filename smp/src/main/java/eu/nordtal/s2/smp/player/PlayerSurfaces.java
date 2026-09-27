package eu.nordtal.s2.smp.player;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.displaytags.api.DisplayTagsPlugin;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Draws a player onto the tab list and the nametag, which DisplayTags renders.
 *
 * Every nametag element describes the person looked at, so one content serves every viewer.
 */
public final class PlayerSurfaces {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerComposition composition;
    private final MessageRenderer messages;

    public PlayerSurfaces(
            final Plugin plugin,
            final Identities identities,
            final PlayerComposition composition,
            final MessageRenderer messages) {
        this.plugin = plugin;
        this.identities = identities;
        this.composition = composition;
        this.messages = messages;
    }

    /** Redraws one player everywhere they appear, on the main thread. */
    public void refresh(final Player player) {
        final Identity identity = identities.of(player.getUniqueId());
        player.playerListName(composition.tabList(player.getName(), identity));

        // Longest online first: the tab list's own alphabetical sort says nothing about anybody.
        player.setPlayerListOrder((int) Math.min(Integer.MAX_VALUE, identity.playtimeSeconds() / 60L));

        sendTabListFrame(player, identity);

        applyNameTag(player, identity);
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    /**
     * Writes our composition onto a nametag DisplayTags has already created, never creating one.
     *
     * A tag created at join is replaced by DisplayTags' own lines; {@code onNameTagCreate} is the seam that holds.
     */
    private void applyNameTag(final Player player, final Identity identity) {
        final DisplayTagsPlugin displayTags = DisplayTagsPlugin.get();
        if (displayTags == null) {
            // DisplayTags is declared required, so its absence is a failed enable.
            plugin.getLogger()
                    .warning("DisplayTags is not available - " + player.getName() + " keeps the vanilla nametag");
            return;
        }

        final PlayerNameTag tag = displayTags.getNameTagManager().getByPlayer(player);
        if (tag == null) {
            // Between join and the client loading its world there is nothing to write to. The create event fills it in.
            return;
        }
        write(tag, player, identity);
    }

    /**
     * Applies the composition to a tag DisplayTags has just created; call it from a {@code NameTagCreateEvent} handler.
     */
    public void applyTo(final PlayerNameTag tag) {
        final Player player = tag.getPlayer();
        write(tag, player, identities.of(player.getUniqueId()));
    }

    /** Hands the composition to DisplayTags as MiniMessage, which is the format it parses. */
    private void write(final PlayerNameTag tag, final Player player, final Identity identity) {
        final Component line = composition.nameTag(player.getName(), identity);
        tag.getData().setLines(List.of(MiniMessage.miniMessage().serialize(line)));
        tag.updateForViewers();
    }

    /**
     * Draws the tab list's header and footer in the reader's own language.
     *
     * The logo comes in as a placeholder from {@link Glyphs}, never a private-use character in a properties file.
     */
    private void sendTabListFrame(final Player player, final Identity identity) {
        player.sendPlayerListHeaderAndFooter(
                TabList.header(messages, identity.locale(), MESSAGES.tab()::header),
                messages.format(
                        identity.locale(),
                        MESSAGES.tab().footer(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers())));
    }
}
