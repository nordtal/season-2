package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.hud.TabList;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.game.GameState;

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
 * Wires {@link PlayerLocales} (join/quit, per docs/i18n.md) and the disconnected-body mechanism
 * (docs/hunger-games.md#disconnects): on quit mid-game, a body takes the player's place; on
 * reconnect, the body is removed and its gear (if it still has any) is returned.
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

    public PresenceListener(final Plugin plugin, final PlayerLocales locales, final PlayerBodies bodies,
                            final GameState state, final Messages messages,
                            final AdminOperators operators, final FullServerAdmission admission) {
        this.plugin = plugin;
        this.locales = locales;
        this.bodies = bodies;
        this.state = state;
        this.messages = new MessageRenderer(messages);
        this.operators = operators;
        this.admission = admission;
    }

    /**
     * Rewrites the tab list header and footer for everybody online.
     *
     * <p>For everybody, not just the player who moved: the footer carries the player count, so one
     * join changes what every other player's screen should say. The composition itself is
     * {@link TabList}, shared with limbo and the SMP - the tab list is the one surface a player
     * carries unchanged across all three servers.</p>
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

        // Off the main thread, settled 2026-09-01. This used to be a blocking PlayerLocales#join
        // right here, which on a healthy database is a millisecond and on a database that has
        // stopped answering is the pool's whole connection timeout with the server stopped behind
        // it - per join. Nothing renders from it synchronously: the HUD, the boss bar and every
        // message go through PlayerLocales#of, which answers English until the real value lands and
        // never queries. See limbo's PresenceListener for the same change on the login path, where
        // the same freeze would take the network down rather than one backend.
        locales.joinAsync(player.getUniqueId(), task -> plugin.getServer().getScheduler()
                        .runTaskAsynchronously(plugin, task))
                .thenRun(() -> {
                    if (!player.isOnline()) {
                        locales.quit(player.getUniqueId());
                        return;
                    }
                    // Only now: until the language lands, of() answers English, and a tab list
                    // drawn here would be the English one for a German player until they relog.
                    Bukkit.getScheduler().runTask(plugin, this::refreshTabList);
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

        // Only a disconnect once the game is actually RUNNING (state.isRunning() becomes true in
        // HungerGamesManager#release, not at the start of the countdown) gets a body here. A
        // disconnect during REGISTRATION/COUNTDOWN is instead handled by HungerGamesManager#start
        // itself, which reads the roster and places a bare body for anyone not online at that exact
        // moment - spawning a second body here for the same player during COUNTDOWN would double
        // them up.
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
