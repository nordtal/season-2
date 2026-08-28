package me.skyyiscool.displaytags.nametag;

import me.skyyiscool.displaytags.api.nametag.NameTagManager;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
import me.skyyiscool.displaytags.api.events.NameTagCreateEvent;
import me.skyyiscool.displaytags.api.events.NameTagRemoveEvent;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NameTagManagerImpl implements NameTagManager {
    private final Map<UUID, PlayerNameTag> tags = new ConcurrentHashMap<>();

    @Override
    public PlayerNameTag createNameTag(Player player) {
        // The previous tag has to go first: it owns display entities on the viewers' clients, and
        // leaving it in place while a second one spawns is what makes name tags appear twice.
        this.removeNameTag(player);

        PlayerNameTag tag = new PlayerNameTagImpl(player);
        this.tags.put(player.getUniqueId(), tag);

        NameTagCreateEvent event = new NameTagCreateEvent(tag);
        event.callEvent();

        return tag;
    }

    @Override
    public PlayerNameTag getByPlayer(Player player) {
        return this.tags.get(player.getUniqueId());
    }

    @Override
    public Collection<PlayerNameTag> getAll() {
        // ConcurrentHashMap's view iterates weakly, so callers may create or remove tags while
        // they are walking this collection.
        return Collections.unmodifiableCollection(this.tags.values());
    }

    @Override
    public void removeNameTag(Player player) {
        PlayerNameTag tag = this.tags.remove(player.getUniqueId());
        if (tag == null) return;

        NameTagRemoveEvent event = new NameTagRemoveEvent(tag);
        event.callEvent();

        // Despawn first, restore afterwards: the other way round the viewer would briefly see the
        // vanilla name and the display at the same time.
        tag.despawnForViewers();
        if (tag instanceof PlayerNameTagImpl impl) impl.restoreVanillaNameTags();
    }
}
