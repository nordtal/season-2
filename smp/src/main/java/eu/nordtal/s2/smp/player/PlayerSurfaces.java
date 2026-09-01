package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.DisplayTagsPlugin;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Draws a player onto the two surfaces that are not chat: the tab list and the nametag above their
 * head.
 *
 * <h2>The nametag is not ours to render</h2>
 * It belongs to <a href="https://github.com/nordtal/papermc-display-tags">DisplayTags</a>, our own
 * fork, and reaches it through {@code NameTagManager}. Writing our own Text Display nametags was
 * considered and rejected: it would reimplement what that repository already does and leave two
 * implementations to keep in step.
 *
 * <p><b>One content for every viewer, and that is correct rather than a limitation.</b>
 * {@code NameTagData} holds a single set of lines per player, and every element of the composition -
 * the language flag, the admin letter, the donor star, the crest - is a property of the person being
 * looked at, not of the person looking. The flag says what to greet somebody in; it would be
 * meaningless rendered in the reader's own language.
 *
 * <p><b>The nametag deliberately carries no aura.</b> Aura changes on every death, every hand-in and
 * every duel, and a nametag that carried it would send a packet to everyone in range each time.
 */
public final class PlayerSurfaces {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerComposition composition;

    public PlayerSurfaces(final Plugin plugin, final Identities identities,
                          final PlayerComposition composition) {
        this.plugin = plugin;
        this.identities = identities;
        this.composition = composition;
    }

    /** Redraws one player everywhere they appear. Main thread. */
    public void refresh(final Player player) {
        final Identity identity = identities.of(player.getUniqueId());
        player.playerListName(composition.tabList(player.getName(), identity));

        // Longest online first. The tab list's own sort is alphabetical, which says nothing about
        // anybody; minutes played is the one number this server already has for everyone.
        player.setPlayerListOrder((int) Math.min(Integer.MAX_VALUE, identity.playtimeSeconds() / 60L));

        applyNameTag(player, identity);
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    private void applyNameTag(final Player player, final Identity identity) {
        final DisplayTagsPlugin displayTags = DisplayTagsPlugin.get();
        if (displayTags == null) {
            // paper-plugin.yml declares DisplayTags required, so this is a plugin that failed its
            // own enable rather than one that is absent. Say so once per attempt and carry on: a
            // plain nametag is a cosmetic loss, and taking the server down for it is not.
            plugin.getLogger().warning("DisplayTags is not available - " + player.getName()
                    + " keeps the vanilla nametag");
            return;
        }

        final PlayerNameTag tag = displayTags.getNameTagManager().getByPlayer(player) != null
                ? displayTags.getNameTagManager().getByPlayer(player)
                : displayTags.getNameTagManager().createNameTag(player);

        final Component line = composition.nameTag(player.getName(), identity);
        tag.getData().setLines(List.of(LegacyComponentSerializer.legacySection().serialize(line)));
        tag.updateForViewers();
    }
}
