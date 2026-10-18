package eu.nordtal.season.displaytags.nametag;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** A player's name tag: its settings and the display shown to its viewers. */
public abstract class PlayerNameTag {
    protected Player player;

    protected NameTagData data;

    /** The viewers the name tag is spawned for. The scheduler, events and the API touch them at once. */
    protected Set<UUID> viewers;

    public PlayerNameTag(final Player player) {
        this.player = player;
        this.data = new NameTagData();
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

    /** Spawns, despawns or updates the display of every online player as needed. */
    public abstract void tick();

    /** Moves the display to {@code location} for one viewer, for a teleport that the player has not made yet. */
    public abstract void teleportFor(UUID viewerId, Location location);

    public void spawnFor(final Player viewer) {
        this.spawnFor(viewer.getUniqueId());
    }

    public void updateFor(final Player viewer) {
        this.updateFor(viewer.getUniqueId());
    }

    public void teleportFor(final Player viewer) {
        this.teleportFor(viewer.getUniqueId());
    }

    public void despawnFor(final Player viewer) {
        this.despawnFor(viewer.getUniqueId());
    }

    public void teleportForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid);
        }
    }

    public void teleportForViewers(final Location location) {
        for (final UUID uuid : this.currentViewers()) {
            this.teleportFor(uuid, location);
        }
    }

    public void updateForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.updateFor(uuid);
        }
    }

    public void despawnForViewers() {
        for (final UUID uuid : this.currentViewers()) {
            this.despawnFor(uuid);
        }
    }

    /** Returns a snapshot of the viewers, because the per-viewer methods change the live set. */
    private List<UUID> currentViewers() {
        return List.copyOf(this.viewers);
    }
}
