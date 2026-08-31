package eu.nordtal.s2.hungergames.listener;

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

    public PresenceListener(final Plugin plugin, final PlayerLocales locales, final PlayerBodies bodies,
                            final GameState state) {
        this.plugin = plugin;
        this.locales = locales;
        this.bodies = bodies;
        this.state = state;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        locales.join(player.getUniqueId());

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
        locales.quit(player.getUniqueId());

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
