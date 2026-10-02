package eu.nordtal.s2.papercommon.plugin;

import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Lets everybody the proxy sends join, since the network's limit is the proxy's alone and no server has its own. */
final class Unbounded implements Listener {

    /** Overrides the server's own max-players, which nothing sets and which Paper defaults to 20. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFullCheck(final PlayerServerFullCheckEvent event) {
        event.allow(true);
    }
}
