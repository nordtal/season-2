package eu.nordtal.season.limbo.presence;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.limbo.LimboMessages;
import eu.nordtal.season.limbo.net.LimboChannel;
import eu.nordtal.season.limbo.waiting.WaitingRoom;
import eu.nordtal.season.limbo.world.WaitingWorld;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.tab.TabList;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * Everything that has to be true for the waiting room to be a waiting room.
 *
 * Nobody here can see, hear, speak to or hurt anybody; each handler covers an event a gamerule does not stop.
 */
public final class WaitingRoomRules implements Listener {

    private final Plugin plugin;
    private final WaitingWorld world;
    private final WaitingRoom room;
    private final LimboChannel channel;
    private final TabList tabList;

    /** Who everybody here is, held since pre-login; read here, never queried. */
    private final Identities identities;

    public WaitingRoomRules(
            final Plugin plugin,
            final WaitingWorld world,
            final WaitingRoom room,
            final LimboChannel channel,
            final MessageRenderer renderer,
            final Identities identities) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.room = Objects.requireNonNull(room, "room");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.tabList = new TabList(renderer);
    }

    /** Draws this player's tab list; the footer shows no count, since the list holds only their own name. */
    public void sendTabList(final Player player) {
        final java.util.Locale locale = identities.languageOf(player.getUniqueId());
        tabList.draw(player, locale, LimboMessages.MESSAGES.tab().footer());
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
        final AtomicReference<Scheduler.Task> repeat = new AtomicReference<>();
        repeat.set(PaperScheduler.of(plugin).onMainEvery(PaperScheduler.TICK, LimboChannel.READY_REPEAT, () -> {
            if (player.isOnline()) {
                channel.sendReady(player);
            } else {
                final Scheduler.Task running = repeat.get();
                if (running != null) {
                    running.cancel();
                }
            }
        }));
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
