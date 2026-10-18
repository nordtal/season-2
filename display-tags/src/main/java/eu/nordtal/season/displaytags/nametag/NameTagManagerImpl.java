package eu.nordtal.season.displaytags.nametag;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

public class NameTagManagerImpl implements NameTagManager {
    private final Map<UUID, PlayerNameTag> tags = new ConcurrentHashMap<>();
    private final Map<UUID, EntityNameTag> entityTags = new ConcurrentHashMap<>();

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

    @Override
    public EntityNameTag createEntityNameTag(final Entity entity, final Function<UUID, Component> text) {
        this.removeEntityNameTag(entity);
        final EntityNameTag tag = new EntityNameTag(entity, text);
        this.entityTags.put(entity.getUniqueId(), tag);
        return tag;
    }

    @Override
    public void removeEntityNameTag(final Entity entity) {
        final EntityNameTag tag = this.entityTags.remove(entity.getUniqueId());
        if (tag != null) {
            tag.despawnForViewers();
        }
    }

    @Override
    public Collection<EntityNameTag> getAllEntityNameTags() {
        return Collections.unmodifiableCollection(this.entityTags.values());
    }
}
