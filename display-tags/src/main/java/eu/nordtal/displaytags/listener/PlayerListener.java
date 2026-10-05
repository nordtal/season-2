package eu.nordtal.displaytags.listener;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.displaytags.config.NameTagConfiguration;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public class PlayerListener implements Listener {
    private final DisplayTags plugin;

    public PlayerListener(final DisplayTags plugin) {
        this.plugin = plugin;
    }

    // Waits for the client to finish loading the world; on PlayerJoinEvent the spawn packets would be dropped.
    @EventHandler
    public void onPlayerClientLoadedWorld(final PlayerClientLoadedWorldEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            // Tick right away instead of waiting up to one update-interval for the scheduler.
            this.plugin.getNameTagManager().createNameTag(event.getPlayer()).tick();
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            this.plugin.getNameTagManager().removeNameTag(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerSneakToggle(final PlayerToggleSneakEvent event) {
        final NameTagConfiguration config = this.plugin.config().nametag();
        if (!config.isEnabled()) return;

        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
        if (tag == null) return;

        // With see-through: vanilla, sneaking also decides whether the name draws through blocks at all.
        tag.getData().setSneaking(event.isSneaking());

        if (config.hasSneakTextOpacity()) {
            // -1 is the vanilla "fully opaque" value, so it restores the normal look.
            tag.getData().setTextOpacity(event.isSneaking() ? config.getSneakTextOpacity() : -1);
        }

        tag.updateForViewers();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            final Player player = event.getPlayer();
            final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(player);
            if (tag == null) return;

            // The event fires before the player moves, so the destination comes from the event, not the player.
            tag.teleportForViewers(event.getTo());

            // Distance and world can only be re-evaluated once the move has actually happened.
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                final PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
                if (current != null) current.tick();
            });
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            // The display lives in the world it was spawned in, so it must despawn before re-evaluating.
            tag.despawnForViewers();
            tag.tick();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerGameModeChange(final PlayerGameModeChangeEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            final Player player = event.getPlayer();
            if (this.plugin.getNameTagManager().getByPlayer(player) == null) return;

            // Player#getGameMode() still reports the old mode here, so visibility is re-evaluated next tick instead.
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                final PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
                if (current != null) current.tick();
            });
        }
    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            tag.despawnForViewers();
        }
    }

    @EventHandler
    public void onPlayerRespawn(final PlayerRespawnEvent event) {
        if (this.plugin.config().nametag().isEnabled()) {
            final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
            if (tag == null) return;

            tag.tick();
        }
    }
}
