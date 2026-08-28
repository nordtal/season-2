package me.skyyiscool.displaytags.nametag;

import me.skyyiscool.displaytags.api.nametag.NameTagManager;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
import me.skyyiscool.displaytags.api.events.NameTagCreateEvent;
import me.skyyiscool.displaytags.api.events.NameTagRemoveEvent;
import me.skyyiscool.displaytags.util.VanillaNameTagUtil;
import org.bukkit.Bukkit;
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
        if (this.tags.containsKey(player.getUniqueId())) this.removeNameTag(player);

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
        // Give the player their vanilla name tag back for every viewer that may have had it hidden.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            VanillaNameTagUtil.show(player, viewer.getUniqueId());
        }

        PlayerNameTag tag = tags.remove(player.getUniqueId());
        if (tag == null) return;

        NameTagRemoveEvent event = new NameTagRemoveEvent(tag);
        event.callEvent();

        tag.despawnForViewers();
    }
}
