package eu.nordtal.displaytags.api.nametag;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * A player's name tag: the settings behind it, and the display shown to its viewers.
 */
public abstract class PlayerNameTag {
    /**
     * The player this name tag belongs to.
     */
    protected Player player;

    /**
     * The settings that control how the name tag looks and behaves.
     */
    protected NameTagData data;

    /**
     * The viewers the name tag is currently spawned for.
     *
     * Touched from the name tag scheduler, from Bukkit events and from the API, so the set has to
     * tolerate concurrent reads and writes.
     */
    protected Set<UUID> viewers;

    /**
     * @param player the player this name tag belongs to
     */
    public PlayerNameTag(final Player player) {
        this.player = player;
        this.data = new NameTagData();
        this.viewers = ConcurrentHashMap.newKeySet();
    }

    /**
     * @return the settings that control how the name tag looks and behaves
     */
    public NameTagData getData() {
        return this.data;
    }

    /**
     * @return the player this name tag belongs to
     */
    public Player getPlayer() {
        return this.player;
    }

    /**
     * Spawns the name tag's display for a viewer.
     *
     * @param viewerId the viewer to spawn the display for
     */
    public abstract void spawnFor(UUID viewerId);

    /**
     * Resends the name tag's current appearance to a viewer.
     *
     * @param viewerId the viewer to update
     */
    public abstract void updateFor(UUID viewerId);

    /**
     * Moves the name tag's display to the player's current location for a single viewer.
     *
     * @param viewerId the viewer to send the teleport to
     */
    public abstract void teleportFor(UUID viewerId);

    /**
     * Despawns the name tag's display for a viewer.
     *
     * @param viewerId the viewer to despawn the display for
     */
    public abstract void despawnFor(UUID viewerId);

    /**
     * Re-evaluates visibility and appearance for every online player.
     *
     * Spawns, despawns or updates the display as needed.
     */
    public abstract void tick();

    /**
     * Moves the name tag's display to an explicit position for a single viewer.
     *
     * {@link org.bukkit.event.player.PlayerTeleportEvent} fires <em>before</em> the player is
     * actually moved, so at that point {@link #getPlayer()}'s location is still the origin and a
     * teleport packet derived from it would carry the position the player is leaving. Callers that
     * already know the destination pass it here instead.
     *
     * @param viewerId the viewer to send the teleport to
     * @param location the position the display is moved to; not modified by this call
     */
    public abstract void teleportFor(UUID viewerId, Location location);

    /**
     * @param viewer the viewer to spawn the display for
     * @see #spawnFor(UUID)
     */
    public void spawnFor(final Player viewer) {
        this.spawnFor(viewer.getUniqueId());
    }

    /**
     * @param viewer the viewer to update
     * @see #updateFor(UUID)
     */
    public void updateFor(final Player viewer) {
        this.updateFor(viewer.getUniqueId());
    }

    /**
     * @param viewer the viewer to send the teleport to
     * @see #teleportFor(UUID)
     */
    public void teleportFor(final Player viewer) {
        this.teleportFor(viewer.getUniqueId());
    }

    /**
     * @param viewer the viewer to despawn the display for
     * @see #despawnFor(UUID)
     */
    public void despawnFor(final Player viewer) {
        this.despawnFor(viewer.getUniqueId());
    }

    /**
     * Moves the name tag's display to the player's current location for every current viewer.
     *
     * @see #teleportFor(UUID)
     */
    public void teleportForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid);
        }
    }

    /**
     * Moves the name tag's display to an explicit position for every current viewer.
     *
     * @param location the position the display is moved to; not modified by this call
     * @see #teleportFor(UUID, Location)
     */
    public void teleportForViewers(final Location location) {
        for (final UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid, location);
        }
    }

    /**
     * Resends the name tag's current appearance to every current viewer.
     *
     * @see #updateFor(UUID)
     */
    public void updateForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.updateFor(uuid);
        }
    }

    /**
     * Despawns the name tag's display for every current viewer.
     *
     * @see #despawnFor(UUID)
     */
    public void despawnForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.despawnFor(uuid);
        }
    }

    /**
     * A snapshot of the current viewers.
     *
     * The per-viewer methods remove from {@link #viewers} (and implementations or event listeners
     * may add to it), so the bulk methods above must never iterate the live set.
     */
    private List<UUID> currentViewers() {
        return List.copyOf(this.viewers);
    }
}
