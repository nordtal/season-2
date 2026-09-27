package eu.nordtal.s2.limbo.listener;

import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.limbo.LimboMessages;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;
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
    private final PlayerLocales locales;
    private final MessageRenderer messages;
    private final AdminOperators operators;

    /** The admin flag {@link FullServerGate} cached at pre-login; read here, never queried. */
    private final FullServerAdmission admission;

    public PresenceListener(
            final Plugin plugin,
            final WaitingWorld world,
            final WaitingRoom room,
            final LimboChannel channel,
            final PlayerLocales locales,
            final Messages messages,
            final AdminOperators operators,
            final FullServerAdmission admission) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.room = Objects.requireNonNull(room, "room");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.messages = new MessageRenderer(Objects.requireNonNull(messages, "messages"));
    }

    /** Draws this player's tab list; the footer shows no count, since the list holds only their own name. */
    private void sendTabList(final Player player) {
        final java.util.Locale locale = locales.of(player.getUniqueId());
        player.sendPlayerListHeaderAndFooter(
                TabList.header(messages, locale, LimboMessages.MESSAGES.tab()::header),
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

        // Read, never queried, on the main thread; FullServerGate filled it at pre-login.
        operators.onJoin(player.getUniqueId(), admission.admits(player.getUniqueId()));

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

        loadLanguage(player);
    }

    /** Reads the language off the main thread and redraws the title; until then the title is English. */
    private void loadLanguage(final Player player) {
        // A failed lookup leaves one English title up rather than being retried, which is not worth chasing.
        final var _ = locales.joinAsync(player.getUniqueId(), async())
                .thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        // onQuit already ran; the entry this just wrote would otherwise stay for the process's life.
                        locales.quit(player.getUniqueId());
                        return;
                    }
                    room.redraw(player);
                    sendTabList(player);
                }));
    }

    private java.util.concurrent.Executor async() {
        return task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        operators.onQuit(event.getPlayer().getUniqueId());
        room.forget(event.getPlayer().getUniqueId());
        locales.quit(event.getPlayer().getUniqueId());
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
        if (!mutes(admission.admits(event.getPlayer().getUniqueId()))) {
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
