package me.skyyiscool.displaytags.listener;

import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import me.skyyiscool.displaytags.DisplayTags;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
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
            this.plugin.getNameTagManager().createNameTag(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            this.plugin.getNameTagManager().removeNameTag(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerSneakToggle(PlayerToggleSneakEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            if (event.isSneaking()) {
                tag.getData().setTextOpacity(50);
            } else {
                tag.getData().setTextOpacity(-1);
            }

            tag.updateForViewers();
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (plugin.config().nametag().isEnabled()) {
            PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            tag.teleportForViewers();
            tag.tick();
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
