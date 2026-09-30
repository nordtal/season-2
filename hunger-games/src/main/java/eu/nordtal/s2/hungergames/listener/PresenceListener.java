package eu.nordtal.s2.hungergames.listener;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.database.access.FullServerAdmission;
import eu.nordtal.s2.hungergames.GameState;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.packrendering.hud.TabList;
import eu.nordtal.s2.papercommon.chat.SystemLines;
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

/** Wires {@link PlayerLocales} and the bodies that replace players who quit mid-game. */
public final class PresenceListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PresenceListener.class);

    private final Plugin plugin;
    private final PlayerLocales locales;
    private final PlayerBodies bodies;
    private final GameState state;
    private final MessageRenderer messages;
    private final AdminOperators operators;

    /** The admin flag cached at pre-login by {@link FullServerGate}, read here since this is the main thread. */
    private final FullServerAdmission admission;

    /** The shared system lines, held for the join line once the locale has landed. */
    private final SystemLines lines;

    public PresenceListener(
            final Plugin plugin,
            final PlayerLocales locales,
            final PlayerBodies bodies,
            final GameState state,
            final Messages messages,
            final AdminOperators operators,
            final FullServerAdmission admission,
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

    /** Rewrites the tab list header and footer, which carries the player count, for everybody online. */
    private void refreshTabList() {
        for (final Player online : Bukkit.getOnlinePlayers()) {
            final java.util.Locale locale = locales.of(online.getUniqueId());
            online.sendPlayerListHeaderAndFooter(
                    TabList.header(messages, locale, MESSAGES.tab()::header),
                    messages.format(
                            locale,
                            MESSAGES.tab().footer(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers())));
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        operators.onJoin(player.getUniqueId(), admission.admits(player.getUniqueId()));

        // Async and fire-and-forget: PlayerLocales#of answers English until the lookup lands, or if it fails.
        final var _ = locales.joinAsync(
                        player.getUniqueId(),
                        task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task))
                .thenRun(() -> {
                    if (!player.isOnline()) {
                        locales.quit(player.getUniqueId());
                        return;
                    }
                    // Only now: a tab list drawn earlier would stay English until the player relogs.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        refreshTabList();
                        // Asked again: a quick join-then-leave must not be announced as arriving after leaving.
                        if (!player.isOnline()) {
                            return;
                        }
                        // Said once, here rather than in the join handler, so it never renders in English.
                        lines.announceJoin(player);
                    });
                });

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

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        operators.onQuit(player.getUniqueId());
        locales.quit(player.getUniqueId());

        // A tick later: during PlayerQuitEvent the leaver is still counted in getOnlinePlayers().
        Bukkit.getScheduler().runTask(plugin, this::refreshTabList);

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
