package eu.nordtal.displaytags.api.events;

import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once a name tag has been created for a player.
 */
public class NameTagCreateEvent extends Event {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final PlayerNameTag tag;

    /**
     * @param tag the name tag that was created
     */
    public NameTagCreateEvent(final PlayerNameTag tag) {
        this.tag = tag;
    }

    /**
     * @return the name tag that was created
     */
    public PlayerNameTag getNameTag() {
        return this.tag;
    }

    /**
     * @return the player the name tag belongs to
     */
    public Player getPlayer() {
        return this.tag.getPlayer();
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
}
