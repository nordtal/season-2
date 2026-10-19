package eu.nordtal.season.hungergames.presence;

import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
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

/** Spawns a stand for a player who quits during a game and returns the equipment when the player joins again. */
public final class DisconnectBodies implements Listener {

    static final DocumentSpec<TabList.Counted> TAB_FOOTER = TabList.countedFooter("hunger-games");

    private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectBodies.class);

    private final Plugin plugin;
    private final Identities identities;
    private final PlayerBodies bodies;
    private final GameState state;
    private final TabList tabList;

    private final SystemLines lines;

    /** The player limit of the network. */
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

    /** Sends the tab list header and footer to every online player. */
    private void refreshTabList() {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final java.util.Locale locale = identities.languageOf(online.getUniqueId());
            tabList.drawCounted(online, locale, TAB_FOOTER, network);
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
                final var _ = player.teleportAsync(at);
            }
            bodies.remove(player.getUniqueId());
        }
    }

    /** Sends the tab list and announces the join. */
    public void languageKnown(final Player player) {
        refreshTabList();
        lines.announceJoin(player);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();

        PaperScheduler.of(plugin).onMain(this::refreshTabList);

        if (state.isRunning()) {
            LOGGER.info("Player {} disconnected mid-game - spawning a body to take their place", player.getName());
            bodies.spawn(player, Objects.requireNonNull(player.getLocation()));
        }
    }

    /** Copies the equipment of the stand to the player. */
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
