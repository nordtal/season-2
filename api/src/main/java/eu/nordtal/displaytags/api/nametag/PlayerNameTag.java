package eu.nordtal.displaytags.api.nametag;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class PlayerNameTag {
    protected Player player;
    protected NameTagData data;
    protected Set<UUID> viewers;

    public PlayerNameTag(Player player) {
        this.player = player;
        this.data = new NameTagData();
        // Viewers are touched from the name tag scheduler, from Bukkit events and from the API,
        // so the set has to tolerate concurrent reads and writes.
        this.viewers = ConcurrentHashMap.newKeySet();
    }

    public NameTagData getData() {
        return this.data;
    }

    public Player getPlayer() {
        return this.player;
    }

    public abstract void spawnFor(UUID viewerId);
    public abstract void updateFor(UUID viewerId);
    public abstract void teleportFor(UUID viewerId);
    public abstract void despawnFor(UUID viewerId);
    public abstract void tick();

    /**
     * Moves the name tag's display to an explicit position for a single viewer.
     * <p>
     * {@link org.bukkit.event.player.PlayerTeleportEvent} fires <em>before</em> the player is
     * actually moved, so at that point {@link #getPlayer()}'s location is still the origin and a
     * teleport packet derived from it would carry the position the player is leaving. Callers that
     * already know the destination pass it here instead.
     *
     * @param viewerId the viewer to send the teleport to
     * @param location the position the display is moved to; not modified by this call
     */
    public abstract void teleportFor(UUID viewerId, Location location);

    public void spawnFor(Player viewer) {
        this.spawnFor(viewer.getUniqueId());
    }

    public void updateFor(Player viewer) {
        this.updateFor(viewer.getUniqueId());
    }

    public void teleportFor(Player viewer) {
        this.teleportFor(viewer.getUniqueId());
    }

    public void despawnFor(Player viewer) {
        this.despawnFor(viewer.getUniqueId());
    }

    public void teleportForViewers() {
        for (UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid);
        }
    }

    /**
     * Moves the name tag's display to an explicit position for every current viewer.
     *
     * @see #teleportFor(UUID, Location)
     */
    public void teleportForViewers(Location location) {
        for (UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid, location);
        }
    }

    public void updateForViewers() {
        for (UUID uuid : this.currentViewers()) {
            this.updateFor(uuid);
        }
    }

    public void despawnForViewers() {
        for (UUID uuid : this.currentViewers()) {
            this.despawnFor(uuid);
        }
    }

    /**
     * A snapshot of the current viewers.
     * <p>
     * The per-viewer methods remove from {@link #viewers} (and implementations or event listeners
     * may add to it), so the bulk methods above must never iterate the live set.
     */
    private List<UUID> currentViewers() {
        return List.copyOf(this.viewers);
    }
}
