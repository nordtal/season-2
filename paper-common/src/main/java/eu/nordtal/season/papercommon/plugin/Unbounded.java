package eu.nordtal.season.papercommon.plugin;

import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Lets every player that the proxy sends join. Only the proxy limits the network. */
final class Unbounded implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFullCheck(final PlayerServerFullCheckEvent event) {
        event.allow(true);
    }
}
