package eu.nordtal.displaytags.listener;

import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.displaytags.config.NameTagConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

public class PlayerListener implements Listener {
    private final DisplayTags plugin;

    public PlayerListener(DisplayTags plugin) {
        this.plugin = plugin;
    }

    // The name tag is created once the client has loaded into the world. On PlayerJoinEvent the
    // client is not ready yet and the spawn packets would be dropped.
    @EventHandler
    public void onPlayerClientLoadedWorld(PlayerClientLoadedWorldEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            // Tick right away instead of waiting up to one update-interval for the scheduler.
            this.plugin.getNameTagManager().createNameTag(event.getPlayer()).tick();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            this.plugin.getNameTagManager().removeNameTag(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerSneakToggle(PlayerToggleSneakEvent event) {
        NameTagConfiguration config = this.plugin.config().nametag();
        if (config.isEnabled() && config.hasSneakTextOpacity()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            // -1 is the vanilla "fully opaque" value, so it restores the normal look.
            tag.getData().setTextOpacity(event.isSneaking() ? config.getSneakTextOpacity() : -1);
            tag.updateForViewers();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            Player player = event.getPlayer();
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(player);
            if (tag == null) return;

            // The event fires before the player is moved, so the destination has to be taken from
            // the event - the player's own location is still the one they are leaving.
            tag.teleportForViewers(event.getTo());

            // Distance and world can only be re-evaluated once the move has actually happened.
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
                if (current != null) current.tick();
            });
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            // The display lives in the world it was spawned in, so it has to be despawned for
            // everyone and re-evaluated against the new world.
            tag.despawnForViewers();
            tag.tick();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            Player player = event.getPlayer();
            if (this.plugin.getNameTagManager().getByPlayer(player) == null) return;

            // The game mode is only applied once every listener has run, so Player#getGameMode()
            // still reports the mode the player is leaving. Ticking here would evaluate visibility
            // against the old mode - a player leaving spectator would stay hidden. Re-evaluate on
            // the next tick instead, when the new mode is actually in effect.
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
                if (current != null) current.tick();
            });
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            tag.despawnForViewers();
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            tag.tick();
        }
    }
}
