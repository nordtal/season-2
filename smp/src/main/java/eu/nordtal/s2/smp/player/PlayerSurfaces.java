package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.DisplayTagsPlugin;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final MessageRenderer messages;

    public PlayerSurfaces(final Plugin plugin, final Identities identities,
                          final PlayerComposition composition, final MessageRenderer messages) {
        this.plugin = plugin;
        this.identities = identities;
        this.composition = composition;
        this.messages = messages;
    }

    /** Redraws one player everywhere they appear. Main thread. */
    public void refresh(final Player player) {
        final Identity identity = identities.of(player.getUniqueId());
        player.playerListName(composition.tabList(player.getName(), identity));

        // Longest online first. The tab list's own sort is alphabetical, which says nothing about
        // anybody; minutes played is the one number this server already has for everyone.
        player.setPlayerListOrder((int) Math.min(Integer.MAX_VALUE, identity.playtimeSeconds() / 60L));

        sendTabListFrame(player, identity);

        applyNameTag(player, identity);
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    /**
     * Writes our composition onto a nametag DisplayTags has <em>already</em> created.
     *
     * <p><b>It never creates one, and that is the whole point.</b> DisplayTags creates the tag on
     * {@code PlayerClientLoadedWorldEvent} - after {@code PlayerJoinEvent}, because on join the
     * client is not ready and the spawn packets are dropped - and {@code createNameTag} removes any
     * existing tag and constructs a fresh one whose constructor calls
     * {@code data.setLines(config.getLines())}. So a tag created here at join was thrown away
     * moments later and replaced by DisplayTags' own configured lines, which is why a real client
     * showed the stock {@code <gray>{player}</gray>} format on 2026-09-04. {@code onNameTagCreate}
     * below is the seam that works: it fires from inside {@code createNameTag}, so there is no
     * ordering left to lose.</p>
     */
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

        final PlayerNameTag tag = displayTags.getNameTagManager().getByPlayer(player);
        if (tag == null) {
            // Between join and the client loading its world there is nothing to write to. The
            // create event fills it in.
            return;
        }
        write(tag, player, identity);
    }

    /**
     * Applies the composition to a tag DisplayTags has just created - call this from a
     * {@code NameTagCreateEvent} handler.
     */
    public void applyTo(final PlayerNameTag tag) {
        final Player player = tag.getPlayer();
        write(tag, player, identities.of(player.getUniqueId()));
    }

    /**
     * Hands the composition to DisplayTags as <b>MiniMessage</b>, which is the format it parses.
     *
     * <p>{@code PlayerNameTagImpl} resolves its lines through {@code ComponentUtil.render}, which is
     * {@code MiniMessage.deserialize}. Serialising with {@code LegacyComponentSerializer} - which
     * this method did until 2026-09-04 - produces section codes that a MiniMessage parser has no
     * meaning for, so every colour in the composition was lost and the section codes themselves
     * travelled to the client as text.</p>
     */
    private void write(final PlayerNameTag tag, final Player player, final Identity identity) {
        final Component line = composition.nameTag(player.getName(), identity);
        tag.getData().setLines(List.of(MiniMessage.miniMessage().serialize(line)));
        tag.updateForViewers();
    }

    /**
     * Draws the tab list's header and footer, in the reader's own language.
     *
     * <p><b>This one is the reader's language, unlike the nametag.</b> The header is a caption on
     * the list rather than a label on a person: nobody's identity is in it, so there is no argument
     * for showing it in somebody else's language. The flag beside a name stays the wearer's, for
     * the reason the class comment gives.</p>
     *
     * <p>The logo arrives as a placeholder rather than as a literal character in the properties
     * file. A private-use code point pasted into a {@code .properties} file survives exactly as long
     * as nobody opens it in an editor that helpfully normalises it, and the failure mode is a
     * missing-glyph box that looks like a pack problem. {@link Glyphs} is where that code point is
     * allowed to live.</p>
     */
    private void sendTabListFrame(final Player player, final Identity identity) {
        player.sendPlayerListHeaderAndFooter(
                TabList.header(messages, identity.locale()),
                TabList.footer(messages, identity.locale(),
                        Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()));
    }
}
