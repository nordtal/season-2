package eu.nordtal.season.displaytags.nametag;

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
        // Without removing the previous tag first, its display entities remain and the name appears twice.
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

        // The viewer would briefly see both names if the tag were restored first.
        tag.despawnForViewers();
        if (tag instanceof PlayerNameTagImpl impl) {
            impl.restoreVanillaNameTags();
        }
    }
}
