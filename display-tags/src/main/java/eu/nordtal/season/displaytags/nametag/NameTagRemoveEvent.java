package eu.nordtal.season.displaytags.nametag;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a name tag was removed and the viewers got the vanilla name tag back. */
public class NameTagRemoveEvent extends Event {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final PlayerNameTag tag;

    public NameTagRemoveEvent(final PlayerNameTag tag) {
        this.tag = tag;
    }

    public PlayerNameTag getNameTag() {
        return this.tag;
    }

    public Player getPlayer() {
        return this.tag.getPlayer();
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }
}
