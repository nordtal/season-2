package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.game.GameState;
import eu.nordtal.s2.papercommon.chat.SystemLines;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires {@link PlayerLocales} and the disconnected-body mechanism: on quit mid-game a body takes
 * the player's place; on reconnect the body is removed and whatever gear it still has is returned.
 */
public final class PresenceListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PresenceListener.class);

    private final Plugin plugin;
    private final PlayerLocales locales;
    private final PlayerBodies bodies;
    private final GameState state;
    private final MessageRenderer messages;
    private final AdminOperators operators;

    /**
     * The admin flag, cached at pre-login by {@link FullServerGate} on the thread that is allowed to
     * wait. Read here, never queried: this is the main thread.
     */
    private final FullServerAdmission admission;

    /** The five shared system lines. Held for one call: the join line, once the locale has landed. */
    private final SystemLines lines;

    public PresenceListener(final Plugin plugin, final PlayerLocales locales, final PlayerBodies bodies,
                            final GameState state, final Messages messages,
                            final AdminOperators operators, final FullServerAdmission admission,
                            final SystemLines lines) {
        this.plugin = plugin;
        this.locales = locales;
        this.bodies = bodies;
        this.state = state;
        this.messages = new MessageRenderer(messages);
        this.operators = operators;
        this.admission = admission;
        this.lines = lines;
    }

    /**
     * Rewrites the tab list header and footer for everybody online, not just the player who moved:
     * the footer carries the player count.
     */
    private void refreshTabList() {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final java.util.Locale locale = locales.of(online.getUniqueId());
            online.sendPlayerListHeaderAndFooter(
                    TabList.header(messages, locale),
                    TabList.footer(messages, locale,
                            Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()));
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        operators.onJoin(player.getUniqueId(), admission.admits(player.getUniqueId()));

        // Off the main thread: a blocking lookup here costs the pool's whole connection timeout,
        // per join, against a database that has stopped answering. Nothing renders from it
        // synchronously - PlayerLocales#of answers English until the real value lands.
        locales.joinAsync(player.getUniqueId(), task -> plugin.getServer().getScheduler()
                        .runTaskAsynchronously(plugin, task))
                .thenRun(() -> {
                    if (!player.isOnline()) {
                        locales.quit(player.getUniqueId());
                        return;
                    }
                    // Only now: until the language lands, of() answers English, and a tab list
                    // drawn earlier would stay English for a German player until they relog.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        refreshTabList();
                        // Asked again: a player who joined and left inside one database round trip
                        // would otherwise be announced as arriving after they had gone. The tab
                        // list is refreshed either way - it is a fact about everybody else.
                        if (!player.isOnline()) {
                            return;
                        }
                        // The join line lands here rather than in the join handler: it is said
                        // once, so rendering it before the language arrives says it in English.
                        lines.announceJoin(player);
                    });
                });

        if (state.isRunning() && bodies.hasBody(player.getUniqueId())) {
            final var marker = Bukkit.getEntity(bodies.markerOf(player.getUniqueId()));
            if (marker instanceof ArmorStand armorStand) {
                final Location at = armorStand.getLocation();
                returnEquipment(player, armorStand);
                armorStand.remove();
                player.teleportAsync(at);
            }
            bodies.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        operators.onQuit(player.getUniqueId());
        locales.quit(player.getUniqueId());

        // A tick later: during PlayerQuitEvent the leaver is still in getOnlinePlayers(), so
        // counting here would tell everyone the number that was true a moment ago.
        Bukkit.getScheduler().runTask(plugin, this::refreshTabList);

        // Only a disconnect once the game is RUNNING gets a body here. During the countdown
        // HungerGamesManager#start already places a bare body for anyone offline, and a second one
        // from here would double them up.
        if (state.isRunning()) {
            LOGGER.info("Player {} disconnected mid-game - spawning a body to take their place", player.getName());
            bodies.spawn(player, player.getLocation());
        }
    }

    /**
     * Copies whatever gear the marker still has (it may have lost pieces to death, looting is not
     * modelled) back onto the reconnecting player - the inverse of {@code PlayerBodies#spawn}.
     */
    private void returnEquipment(final Player player, final ArmorStand marker) {
        final EntityEquipment equipment = marker.getEquipment();
        if (equipment == null) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        inventory.setHelmet(equipment.getHelmet());
        inventory.setChestplate(equipment.getChestplate());
        inventory.setLeggings(equipment.getLeggings());
        inventory.setBoots(equipment.getBoots());
        inventory.setItemInMainHand(equipment.getItem(EquipmentSlot.HAND));
        inventory.setItemInOffHand(equipment.getItem(EquipmentSlot.OFF_HAND));
    }
}
