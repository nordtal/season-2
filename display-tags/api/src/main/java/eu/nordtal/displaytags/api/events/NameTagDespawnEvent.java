package eu.nordtal.displaytags.api.events;

import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a name tag's display despawns for a single viewer.
 */
public class NameTagDespawnEvent extends Event implements Cancellable {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled;

    private final PlayerNameTag tag;
    private final Player viewer;

    /**
     * @param tag    the name tag being despawned
     * @param viewer the viewer it is despawned for
     */
    public NameTagDespawnEvent(final PlayerNameTag tag, final Player viewer) {
        this.tag = tag;
        this.viewer = viewer;
    }

    /**
     * @return the name tag being despawned
     */
    public PlayerNameTag getNameTag() {
        return this.tag;
    }

    /**
     * @return the viewer the name tag is despawned for
     */
    public Player getViewer() {
        return this.viewer;
    }

    /**
     * @return the handler list for this event, as required by Bukkit's event system
     */
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
