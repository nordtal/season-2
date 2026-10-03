package eu.nordtal.s2.limbo.listener;

import eu.nordtal.s2.limbo.LimboMessages;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.papercommon.PaperCommonMessages;
import eu.nordtal.s2.papercommon.player.Identities;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Everything that has to be true for the waiting room to be a waiting room.
 *
 * Nobody here can see, hear, speak to or hurt anybody; each handler covers an event a gamerule does not stop.
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final WaitingWorld world;
    private final WaitingRoom room;
    private final LimboChannel channel;
    private final MessageRenderer messages;

    /** Who everybody here is, held since pre-login; read here, never queried. */
    private final Identities identities;

    public PresenceListener(
            final Plugin plugin,
            final WaitingWorld world,
            final WaitingRoom room,
            final LimboChannel channel,
            final Messages messages,
            final Identities identities) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.room = Objects.requireNonNull(room, "room");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.messages = new MessageRenderer(Objects.requireNonNull(messages, "messages"));
    }

    /** Draws this player's tab list; the footer shows no count, since the list holds only their own name. */
    public void sendTabList(final Player player) {
        final java.util.Locale locale = identities.languageOf(player.getUniqueId());
        player.sendPlayerListHeaderAndFooter(
                messages.format(locale, PaperCommonMessages.MESSAGES.tab().header()),
                messages.format(locale, LimboMessages.MESSAGES.tab().footer()));
    }

    /**
     * Puts the player in the empty world <b>before</b> they are spawned anywhere.
     *
     * Teleporting in {@link #onJoin} would show the server's own world for a frame or two.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        event.setSpawnLocation(world.spawn());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        // Nobody is here to read a join message, and the screen has exactly one line on it.
        event.joinMessage(null);

        room.receive(player);
        hideEverybodyFromEachOther(player);
        sendTabList(player);

        // Repeated every second until the proxy moves the player on; see LimboChannel#sendReady.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                channel.sendReady(player);
            }
        }.runTaskTimer(plugin, 1L, LimboChannel.READY_REPEAT_TICKS);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        room.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        event.setCancelled(true);
    }

    /**
     * Cancels every command for non-admins, because {@code /msg} is chat with a different prefix.
     *
     * Silently, like {@link #onChat}, and as a class rather than a list the next Minecraft version would outgrow.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (!mutes(identities.of(event.getPlayer()).admin())) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Whether this player's commands are swallowed.
     *
     * A method so a test can hold the rule without a server.
     */
    public static boolean mutes(final boolean admin) {
        return !admin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(final FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(final PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    /** Hides the joining player and everybody here from each other, both ways, since {@code hidePlayer} is one-way. */
    private void hideEverybodyFromEachOther(final Player joining) {
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(joining)) {
                continue;
            }
            joining.hidePlayer(plugin, other);
            other.hidePlayer(plugin, joining);
        }
    }
}
