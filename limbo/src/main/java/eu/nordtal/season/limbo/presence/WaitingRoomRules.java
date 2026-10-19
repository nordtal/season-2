package eu.nordtal.season.limbo.presence;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.limbo.net.LimboChannel;
import eu.nordtal.season.limbo.waiting.WaitingRoom;
import eu.nordtal.season.limbo.world.WaitingWorld;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
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

/** The event handlers that hide, mute and protect the players in the waiting room. */
public final class WaitingRoomRules implements Listener {

    static final DocumentSpec<DocumentSpec.None> TAB_FOOTER = TabList.footer("limbo");

    private final Plugin plugin;
    private final WaitingWorld world;
    private final WaitingRoom room;
    private final LimboChannel channel;
    private final MessageRenderer renderer;
    private final TabList tabList;

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
        this.renderer = renderer;
        this.tabList = new TabList(renderer);
    }

    /** Sends the tab list to the player. */
    public void sendTabList(final Player player) {
        final java.util.Locale locale = identities.languageOf(player.getUniqueId());
        tabList.draw(player, locale, renderer.format(locale, Sent.of(TAB_FOOTER)));
    }

    /** Sets the spawn location of the player to the waiting world. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        event.setSpawnLocation(world.spawn());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        event.joinMessage(null);

        room.receive(player);
        hideEverybodyFromEachOther(player);
        sendTabList(player);

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

    /** Cancels every command of a player who is not an admin. */
    @EventHandler(ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        if (!mutes(identities.of(event.getPlayer()).admin())) {
            return;
        }
        event.setCancelled(true);
    }

    /** Returns whether the commands of the player are cancelled. */
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

    /** Hides the joining player from every other player and every other player from the joining player. */
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
