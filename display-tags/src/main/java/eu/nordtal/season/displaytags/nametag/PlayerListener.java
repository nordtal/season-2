package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.papercommon.time.PaperScheduler;
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

    // On PlayerJoinEvent the client has not loaded the world, so the spawn packets would be dropped.
    @EventHandler
    public void onPlayerClientLoadedWorld(final PlayerClientLoadedWorldEvent event) {
        this.plugin.getNameTagManager().createNameTag(event.getPlayer()).tick();
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        this.plugin.getNameTagManager().removeNameTag(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerSneakToggle(final PlayerToggleSneakEvent event) {
        final NameTagConfiguration config = this.plugin.config();
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
        if (tag == null) return;

        // With see-through: vanilla, sneaking also decides whether the name draws through blocks.
        tag.getData().setSneaking(event.isSneaking());

        if (config.hasSneakTextOpacity()) {
            tag.getData().setTextOpacity(event.isSneaking() ? config.getSneakTextOpacity() : -1);
        }

        tag.updateForViewers();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(player);
        if (tag == null) return;

        // The event fires before the player moves.
        tag.teleportForViewers(event.getTo());

        // The distance and world can be evaluated only after the move.
        PaperScheduler.of(this.plugin.host()).onMain(() -> {
            final PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
            if (current != null) current.tick();
        });
    }

    @EventHandler
    public void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
        if (tag == null) return;

        // The display lives in the world it was spawned in.
        tag.despawnForViewers();
        tag.tick();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerGameModeChange(final PlayerGameModeChangeEvent event) {
        final Player player = event.getPlayer();
        if (this.plugin.getNameTagManager().getByPlayer(player) == null) return;

        // Player#getGameMode() still returns the old mode here.
        PaperScheduler.of(this.plugin.host()).onMain(() -> {
            final PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
            if (current != null) current.tick();
        });
    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent event) {
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
        if (tag == null) return;

        tag.despawnForViewers();
    }

    @EventHandler
    public void onPlayerRespawn(final PlayerRespawnEvent event) {
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(event.getPlayer());
        if (tag == null) return;

        tag.tick();
    }
}
