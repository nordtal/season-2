package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.smp.config.SmpSpec;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

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

    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    public PresenceListener(final Plugin plugin, final Identities identities,
                            final PlayerSurfaces surfaces, final PlayerComposition composition,
                            final SmpSpec config) {
        this.plugin = plugin;
        this.identities = identities;
        this.surfaces = surfaces;
        this.composition = composition;
        this.config = config;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        attachAdminPermissions(player);
        surfaces.refresh(player);

        // Everybody else's ordering depends on who is online, and this player is new to that set.
        Bukkit.getScheduler().runTask(plugin, surfaces::refreshAll);
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
     * Renders the line in front of a chat message.
     *
     * <p>The renderer runs per recipient, but the composition is the <em>sender's</em> - their flag,
     * their crest - so the same component is produced for everyone. That is deliberate: a flag
     * exists to say what to greet somebody in.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Identity identity = identities.of(event.getPlayer().getUniqueId());
        final Component prefix = composition.chatPrefix(event.getPlayer().getName(), identity);
        event.renderer((source, displayName, message, viewer) ->
                prefix.append(Component.text(": ")).append(message));
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
