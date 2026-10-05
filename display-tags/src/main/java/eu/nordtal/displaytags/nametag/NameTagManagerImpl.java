package eu.nordtal.displaytags.nametag;

import eu.nordtal.displaytags.api.events.NameTagCreateEvent;
import eu.nordtal.displaytags.api.events.NameTagRemoveEvent;
import eu.nordtal.displaytags.api.nametag.NameTagManager;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

public class NameTagManagerImpl implements NameTagManager {
    private final Map<UUID, PlayerNameTag> tags = new ConcurrentHashMap<>();

    @Override
    public PlayerNameTag createNameTag(final Player player) {
        // The previous tag has to go first, otherwise its display entities linger and the name tag appears twice.
        this.removeNameTag(player);

        final PlayerNameTag tag = new PlayerNameTagImpl(player);
        this.tags.put(player.getUniqueId(), tag);

        final NameTagCreateEvent event = new NameTagCreateEvent(tag);
        event.callEvent();

        return tag;
    }

    @Override
    public @Nullable PlayerNameTag getByPlayer(final Player player) {
        return this.tags.get(player.getUniqueId());
    }

    @Override
    public Collection<PlayerNameTag> getAll() {
        // ConcurrentHashMap's view iterates weakly, so callers may create or remove tags while walking it.
        return Collections.unmodifiableCollection(this.tags.values());
    }

    @Override
    public void removeNameTag(final Player player) {
        final PlayerNameTag tag = this.tags.remove(player.getUniqueId());
        if (tag == null) {
            return;
        }

        final NameTagRemoveEvent event = new NameTagRemoveEvent(tag);
        event.callEvent();

        // Despawn first, restore afterwards, or the viewer would briefly see both the vanilla name and the display.
        tag.despawnForViewers();
        if (tag instanceof PlayerNameTagImpl impl) {
            impl.restoreVanillaNameTags();
        }
    }
}
