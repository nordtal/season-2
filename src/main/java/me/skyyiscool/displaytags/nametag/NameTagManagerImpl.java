package me.skyyiscool.displaytags.nametag;

import me.skyyiscool.displaytags.api.nametag.NameTagManager;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
import me.skyyiscool.displaytags.api.events.NameTagCreateEvent;
import me.skyyiscool.displaytags.api.events.NameTagRemoveEvent;
import me.skyyiscool.displaytags.util.VanillaNameTagUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NameTagManagerImpl implements NameTagManager {
    private final Map<UUID, PlayerNameTag> tags = new HashMap<>();

    @Override
    public PlayerNameTag createNameTag(Player player) {
        PlayerNameTag tag = new PlayerNameTagImpl(player);

        NameTagCreateEvent event = new NameTagCreateEvent(tag);
        event.callEvent();

        if (tags.containsKey(player.getUniqueId())) this.removeNameTag(player);
        tags.put(player.getUniqueId(), tag);

        return tag;
    }

    @Override
    public PlayerNameTag getByPlayer(Player player) {
        return this.tags.get(player.getUniqueId());
    }

    @Override
    public Collection<PlayerNameTag> getAll() {
        return this.tags.values();
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
