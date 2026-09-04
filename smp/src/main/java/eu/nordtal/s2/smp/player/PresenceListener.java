package eu.nordtal.s2.smp.player;

import eu.nordtal.displaytags.api.events.NameTagCreateEvent;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.SmpSpec;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Join, quit, and the third surface a player appears on: chat.
 *
 * <h2>Chat needs no plugin, but the line in front of the message does</h2>
 * Chat itself is per Paper server, which is Minecraft's default: the SMP is one server holding four
 * worlds, so Nordtal, the farm world, the Nether and the End share one chat, and that is what keeps
 * a small community feeling like one place instead of four empty ones. What is rendered here is only
 * the composition in front of the message - flag, name, crest - and never the routing.
 *
 * <h2>Permissions without LuckPerms</h2>
 * An admin gets a {@link PermissionAttachment} with the configured node list at join, and it is
 * removed at quit. The admin flag itself is mirrored from Discord into the database by the bot, so
 * there is one truth, no sync cycle, and nothing to reconcile (docs/smp.md#admins).
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerSurfaces surfaces;
    private final PlayerComposition composition;
    private final SmpSpec config;
    private final Messages messages;
    private final PlayerLocales locales;

    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public PresenceListener(final Plugin plugin, final Identities identities,
                            final PlayerSurfaces surfaces, final PlayerComposition composition,
                            final SmpSpec config, final Messages messages,
                            final PlayerLocales locales) {
        this.plugin = plugin;
        this.identities = identities;
        this.surfaces = surfaces;
        this.composition = composition;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        attachAdminPermissions(player);
        surfaces.refresh(player);

        // Everybody else's ordering depends on who is online, and this player is new to that set.
        Bukkit.getScheduler().runTask(plugin, surfaces::refreshAll);
    }

    /**
     * Fills in a nametag the moment DisplayTags creates one.
     *
     * <p>This is the only place the composition reliably reaches the tag.
     * {@code NameTagManagerImpl#createNameTag} removes the previous tag and constructs a new one
     * whose constructor applies DisplayTags' own configured lines, then fires this event - so a tag
     * written at join is overwritten, and a handler on any later Bukkit event races the tick that
     * has already rendered the stock format. Firing from inside the creation leaves no ordering to
     * get wrong, and it covers every path that creates a tag: join, a world change, a reload.</p>
     */
    @EventHandler
    public void onNameTagCreate(final NameTagCreateEvent event) {
        surfaces.applyTo(event.getNameTag());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final PermissionAttachment attachment = attachments.remove(event.getPlayer().getUniqueId());
        if (attachment != null) {
            event.getPlayer().removeAttachment(attachment);
        }
        // Identities forgets them in JoinGate's quit handler, which owns the cache's lifetime.
    }

    /**
     * Renders a chat line: composition, a glyph rule, the message.
     *
     * <p>The whole line lives in {@code smp.chat.line} rather than in this method, and the two
     * halves that cannot be text - the sender's composition and what they actually typed - go in as
     * MiniMessage component slots. So the separator's colour, the spacing and the order are an
     * operator's edit of a message bundle instead of a release, and a player who types
     * {@code <red>} still cannot colour anybody's line: their message arrives as a component that
     * never meets the parser.
     *
     * <p><b>The composition is the sender's and the language is the reader's.</b> Those are two
     * different people on purpose - the flag says what to greet somebody in, so it belongs to the
     * person being looked at, while the words around it belong to whoever is reading. Paper calls
     * the renderer once per recipient, which is what makes the second half free.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Identity identity = identities.of(event.getPlayer().getUniqueId());
        final var sender = composition.chatPrefix(event.getPlayer().getName(), identity);
        final MessageRenderer renderer = MessageRenderer.of(messages);
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), "smp.chat.line",
                        Map.of("_sender", sender, "_message", message),
                        "separator", Glyphs.SEPARATOR));
    }

    /**
     * The reader's language, or English for an audience that is not a player.
     *
     * <p>The console is such an audience, and so is anything else that has been given a copy of
     * chat; neither has a row in {@code discord_user}, so there is nothing to look up rather than
     * something missing.
     */
    private Locale localeOf(final Audience viewer) {
        return viewer instanceof Player player
                ? locales.of(player.getUniqueId())
                : Locales.DEFAULT;
    }

    private void attachAdminPermissions(final Player player) {
        if (!identities.of(player.getUniqueId()).admin()) {
            return;
        }
        final PermissionAttachment attachment = player.addAttachment(plugin);
        for (final String node : config.adminPermissions()) {
            attachment.setPermission(node, true);
        }
        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }
}
