package eu.nordtal.season.displaytags.nametag;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before a name tag's display despawns for one viewer. */
public class NameTagDespawnEvent extends Event implements Cancellable {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled;

    private final PlayerNameTag tag;
    private final Player viewer;

    public NameTagDespawnEvent(final PlayerNameTag tag, final Player viewer) {
        this.tag = tag;
        this.viewer = viewer;
    }

    public PlayerNameTag getNameTag() {
        return this.tag;
    }

    public Player getViewer() {
        return this.viewer;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }
}
