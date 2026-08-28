package me.skyyiscool.displaytags.api.nametag;

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
