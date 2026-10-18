package eu.nordtal.season.smp.navigate;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class NavigateListener implements Listener {

    private final Plugin plugin;
    private final PlaceDao dao;
    private final Navigation navigation;
    private final Identities identities;

    public NavigateListener(
            final Plugin plugin, final PlaceDao dao, final Navigation navigation, final Identities identities) {
        this.plugin = plugin;
        this.dao = dao;
        this.navigation = navigation;
        this.identities = identities;
    }

    /** Stores the death place of the player, replacing the previous one. */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final Location at = Objects.requireNonNull(player.getLocation());
        final DiscordId discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        PaperScheduler.of(plugin)
                .execute(() -> dao.rememberDeath(
                        discordId, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        navigation.clear(event.getPlayer().getUniqueId());
    }
}
