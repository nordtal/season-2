package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import java.util.Objects;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jspecify.annotations.Nullable;

public class PlayerListener implements Listener {
    private final DisplayTags plugin;

    public PlayerListener(final DisplayTags plugin) {
        this.plugin = plugin;
    }

    // On PlayerJoinEvent the client has not loaded the world, so the spawn packets would be dropped.
    @EventHandler
    public void onPlayerClientLoadedWorld(final PlayerClientLoadedWorldEvent event) {
        final Player player = event.getPlayer();
        this.plugin.getNameTagManager().createNameTag(player).tick();
        for (final EntityNameTag tag : this.plugin.getNameTagManager().getAllEntityNameTags()) {
            tag.respawnFor(player);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerTrackEntity(final PlayerTrackEntityEvent event) {
        final EntityNameTag tag = this.entityNameTag(event.getEntity());
        if (tag == null) return;

        // Track fires right before the entity spawn packet goes out, so mounting runs on the next tick.
        final Player viewer = event.getPlayer();
        PaperScheduler.of(this.plugin.host()).onMain(() -> {
            if (viewer.isOnline()) {
                tag.syncFor(viewer);
            }
        });
    }

    @EventHandler
    public void onPlayerUntrackEntity(final PlayerUntrackEntityEvent event) {
        final EntityNameTag tag = this.entityNameTag(event.getEntity());
        if (tag != null) {
            tag.despawnFor(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        this.plugin.getNameTagManager().removeNameTag(player);
        for (final EntityNameTag tag : this.plugin.getNameTagManager().getAllEntityNameTags()) {
            tag.forgetViewer(player.getUniqueId());
        }
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

        // The event fires before the player moves, and for a rider taken off by a command, before the dismount.
        final boolean riding = followsVehicle(event);
        if (!riding) tag.teleportForViewers(event.getTo());

        // The distance and world can be evaluated only after the move.
        PaperScheduler.of(this.plugin.host()).onMain(() -> {
            final PlayerNameTag current = this.plugin.getNameTagManager().getByPlayer(player);
            if (current == null || (riding && player.isInsideVehicle())) return;
            if (riding)
                current.teleportForViewers(
                        Objects.requireNonNull(player.getLocation(), "an online player has a location"));
            current.tick();
        });
    }

    /**
     * Whether the teleport only carries the player along in their vehicle.
     * A driven cart does so every tick. The tag rides on the player and follows by itself, while a teleport packet
     * would put it ahead of the client's vehicle.
     */
    static boolean followsVehicle(final PlayerTeleportEvent event) {
        return event.getPlayer().isInsideVehicle()
                && Objects.equals(event.getFrom().getWorld(), event.getTo().getWorld());
    }

    @EventHandler
    public void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(player);
        if (tag != null) {
            // The display lives in the world it was spawned in.
            tag.despawnForViewers();
            tag.tick();
        }
        this.respawnEntityTags(player);
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
        final Player player = event.getPlayer();
        final PlayerNameTag tag = this.plugin.getNameTagManager().getByPlayer(player);
        if (tag != null) {
            tag.tick();
        }
        this.respawnEntityTags(player);
    }

    private void respawnEntityTags(final Player viewer) {
        for (final EntityNameTag entityTag : this.plugin.getNameTagManager().getAllEntityNameTags()) {
            entityTag.despawnFor(viewer.getUniqueId());
        }
        PaperScheduler.of(this.plugin.host()).onMain(() -> {
            if (!viewer.isOnline()) return;
            for (final EntityNameTag entityTag : this.plugin.getNameTagManager().getAllEntityNameTags()) {
                entityTag.syncFor(viewer);
            }
        });
    }

    private @Nullable EntityNameTag entityNameTag(final Entity entity) {
        if (this.plugin.getNameTagManager() instanceof final NameTagManagerImpl manager) {
            return manager.getEntityNameTag(entity);
        }
        return null;
    }
}
