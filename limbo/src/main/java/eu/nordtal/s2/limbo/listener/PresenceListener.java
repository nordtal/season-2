package eu.nordtal.s2.limbo.listener;

import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Everything that has to be true for the waiting room to be a waiting room: players arrive in the
 * empty world, cannot see each other, cannot speak, cannot be hurt, and cannot do anything at all.
 *
 * <h2>Why so many handlers for a server with nothing in it</h2>
 * Because each of them is a thing that would otherwise be visible on a black screen. A chat message
 * from somebody else is text over the title; a hunger bar is a HUD element; a death is a respawn
 * screen. The world's gamerules and the player's own flags cover most of this already - these are
 * the cases where a rule exists but an event can still fire.
 *
 * <h2>No chat, decided 2026-08-31</h2>
 * docs/smp.md: "Chat is per Paper server; {@code limbo} has none, shows nothing and nobody, only a
 * title." A player who types here sees nothing happen, which is the intended outcome and not a
 * fault: there is nobody to talk to, because everybody in here is invisible to everybody else and
 * is about to leave.
 */
public final class PresenceListener implements Listener {

    private final Plugin plugin;
    private final WaitingWorld world;
    private final WaitingRoom room;
    private final LimboChannel channel;
    private final PlayerLocales locales;
    private final MessageRenderer messages;
    private final AdminOperators operators;

    /**
     * The admin flag, cached at pre-login by {@link FullServerGate} on the thread that is allowed to
     * wait. Read here, never queried: this is the main thread.
     */
    private final FullServerAdmission admission;

    public PresenceListener(final Plugin plugin, final WaitingWorld world, final WaitingRoom room,
                            final LimboChannel channel, final PlayerLocales locales,
                            final Messages messages, final AdminOperators operators,
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

    /**
     * Draws the tab list frame this one player sees.
     *
     * <p>Only this player, and no count: {@link #hideEverybodyFromEachOther} means the list above
     * the footer holds exactly one name - their own - so the {@code {online}/{max}} the SMP and the
     * hunger games put there would sit over a list that contradicts it. limbo's {@code tab.footer}
     * therefore says something else, and is the one of the three that is allowed to differ; see
     * {@code TabListTest}. The header is shared, because a player who presses Tab here has just
     * arrived on the network and the logo is the only thing on the screen that says where.</p>
     */
    private void sendTabList(final Player player) {
        final java.util.Locale locale = locales.of(player.getUniqueId());
        player.sendPlayerListHeaderAndFooter(
                TabList.header(messages, locale),
                TabList.footer(messages, locale, 1, 1));
    }

    /**
     * Puts the player in the empty world <b>before</b> they are spawned anywhere.
     * <p>
     * The alternative - teleporting them in {@link #onJoin} - shows the server's own {@code
     * level-name} world for a frame or two: terrain, a sky and a sun, on a server whose whole point
     * is that there is nothing to see. This event fires while the connection is still being
     * configured, so there is no frame to see.
     * </p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        event.setSpawnLocation(world.spawn());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        // Read, never queried - this is the main thread. FullServerGate filled it at pre-login.
        operators.onJoin(player.getUniqueId(), admission.admits(player.getUniqueId()));

        // Nobody is here to read a join message, and the screen has exactly one line on it.
        event.joinMessage(null);

        room.receive(player);
        hideEverybodyFromEachOther(player);
        sendTabList(player);

        // One tick later: by then the join is unambiguously complete, whatever order Bukkit ran the
        // handlers of this event in. See LimboChannel#sendReady.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                channel.sendReady(player);
            }
        }, 1L);

        loadLanguage(player);
    }

    /**
     * Reads the player's language <b>off the main thread</b> and redraws the title once it is known.
     * <p>
     * The lookup is one indexed round trip and it is still one too many to run here. This is the
     * server every single login passes through: a database that has stopped answering would freeze
     * it for the pool's connection timeout <em>per join</em>, and a frozen waiting room is the whole
     * network down rather than one backend hesitating. Until the answer arrives,
     * {@code PlayerLocales#of} returns English - which is the fallback docs/i18n.md builds
     * everything on, so the cost is that a German player may see one English line before the right
     * one replaces it.
     * </p>
     * <p>
     * The redraw is deliberately unconditional rather than "only if the language turned out not to
     * be English": re-showing the same title is free, and a conditional here would be a second place
     * that has to know what {@code of()} would have answered a moment ago.
     * </p>
     */
    private void loadLanguage(final Player player) {
        locales.joinAsync(player.getUniqueId(), async())
                .thenRun(() -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        // They left while the query was in flight. onQuit has already run, so the
                        // entry this just wrote would otherwise stay for the life of the process.
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

    /**
     * Hides the joining player from everybody already here, and everybody already here from them.
     * <p>
     * Both directions, because {@code hidePlayer} is one-way. The proxy can have several people in
     * the waiting room at once - a restarting backend puts everybody in here at the same moment -
     * and docs/architecture.md says they see "no other players".
     * </p>
     */
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
