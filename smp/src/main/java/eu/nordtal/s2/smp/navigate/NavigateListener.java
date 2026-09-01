package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Clicks in the {@code /navigate} list, and remembering where somebody died.
 *
 * <p>The last death location is one of the two built-in navigation targets, and it is the one that
 * matters most: with no {@code /back} and no teleport commands of any kind, walking to your grave is
 * the whole of dying, and an arrow pointing the way is the difference between a walk and a search.
 */
public final class NavigateListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Navigation navigation;
    private final Identities identities;
    private final PlayerLocales locales;

    public NavigateListener(final Plugin plugin, final SmpDao dao, final Navigation navigation,
                            final Identities identities, final PlayerLocales locales) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
        this.locales = locales;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof NavigateGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (gui.click(player, event.getRawSlot(), locales.of(player.getUniqueId()))) {
            player.closeInventory();
        }
    }

    /**
     * Records where a death happened.
     *
     * <p>Written even for a death in the farm world, which will be gone by tomorrow: the arrow is
     * useful for the rest of today, and the daily reset clears the navigation itself.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final Location at = player.getLocation();
        final String discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dao.rememberDeath(discordId,
                at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        navigation.clear(event.getPlayer().getUniqueId());
    }
}
