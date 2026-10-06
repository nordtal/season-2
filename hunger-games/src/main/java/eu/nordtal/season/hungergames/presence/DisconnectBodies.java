package eu.nordtal.season.hungergames.presence;

import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.tab.TabList;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.util.Objects;
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
 * Puts a body in place of a player who quits mid-game and hands the gear back on their return.
 *
 * The tab list's player count is redrawn as people come and go.
 */
public final class DisconnectBodies implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectBodies.class);

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerBodies bodies;
    private final GameState state;
    private final TabList tabList;

    /** The shared system lines, held for the join line. */
    private final SystemLines lines;

    /** The network's limit, which the footer shows: no server has one of its own. */
    private final PlayersSpec network;

    public DisconnectBodies(
            final Plugin plugin,
            final Identities identities,
            final PlayerBodies bodies,
            final GameState state,
            final MessageRenderer renderer,
            final SystemLines lines,
            final PlayersSpec network) {
        this.network = network;
        this.plugin = plugin;
        this.identities = identities;
        this.bodies = bodies;
        this.state = state;
        this.tabList = new TabList(renderer);
        this.lines = lines;
    }

    /** Rewrites the tab list header and footer, which carries the player count, for everybody online. */
    private void refreshTabList() {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final java.util.Locale locale = identities.languageOf(online.getUniqueId());
            tabList.drawCounted(online, locale, network);
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (state.isRunning() && bodies.hasBody(player.getUniqueId())) {
            final var marker = Bukkit.getEntity(bodies.markerOf(player.getUniqueId()));
            if (marker instanceof ArmorStand armorStand) {
                final Location at = armorStand.getLocation();
                returnEquipment(player, armorStand);
                armorStand.remove();
                // Fire-and-forget: nothing here depends on the teleport landing before the handler returns.
                final var _ = player.teleportAsync(at);
            }
            bodies.remove(player.getUniqueId());
        }
    }

    /** Draws what a joined player reads once every join handler ran, and says they arrived. */
    public void languageKnown(final Player player) {
        refreshTabList();
        // Said once, here rather than in a join handler, after Paper's own join handling.
        lines.announceJoin(player);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();

        // A tick later: during PlayerQuitEvent the leaver is still counted in getOnlinePlayers().
        PaperScheduler.of(plugin).onMain(this::refreshTabList);

        // Only a RUNNING-game disconnect gets a body here; the countdown already placed one for offline players.
        if (state.isRunning()) {
            LOGGER.info("Player {} disconnected mid-game - spawning a body to take their place", player.getName());
            bodies.spawn(player, Objects.requireNonNull(player.getLocation()));
        }
    }

    /** Returns the marker's remaining gear to the reconnecting player, the inverse of {@code PlayerBodies#spawn}. */
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
