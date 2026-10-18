package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.SeeThroughMode;
import eu.nordtal.season.displaytags.Util;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.wrapper.display.TextDisplayWrapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The name tag above an entity that is not a player, with a text of its own for each viewer.
 * It takes the look of the player tags from the settings, and the entity's own name stays hidden.
 */
public final class EntityNameTag {
    private final Entity entity;
    private final Function<UUID, Component> text;
    private final TextDisplayWrapper display = new TextDisplayWrapper();
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    EntityNameTag(final Entity entity, final Function<UUID, Component> text) {
        this.entity = entity;
        this.text = text;
    }

    public Entity getEntity() {
        return this.entity;
    }

    /** Spawns, despawns or updates the display of every online player as needed. */
    void tick() {
        this.display.setLocation(this.entity.getLocation().setRotation(0, 0));
        this.viewers.removeIf(viewerId -> Bukkit.getPlayer(viewerId) == null);
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            final UUID viewerId = viewer.getUniqueId();
            if (!this.shouldBeVisibleTo(viewer)) {
                this.despawnFor(viewerId);
                continue;
            }
            if (this.viewers.add(viewerId)) {
                this.display.spawnFor(viewerId);
            }
            this.updateFor(viewerId);
        }
    }

    // The text is set right before the metadata goes to one viewer, so each viewer gets their own.
    private void updateFor(final UUID viewerId) {
        final NameTagConfiguration config = DisplayTags.get().config();
        this.display.setTextAlignment(config.getTextAlignment());
        this.display.setBillboard(config.getBillboard());
        this.display.setTextShadow(config.hasTextShadow());
        // shortcut: no faint copy through blocks as player tags draw in VANILLA mode; add one if the NPC needs it.
        this.display.setSeeThrough(config.getSeeThrough() == SeeThroughMode.ALWAYS);
        this.display.setBackground(Util.parseDisplayBackground(config.getBackground()));
        this.display.setTranslation(config.getOffset());
        this.display.setScale(config.getScale());
        this.display.setText(this.text.apply(viewerId));
        this.display.mountFor(viewerId, this.entity.getEntityId());
        this.display.updateFor(viewerId);
    }

    private boolean shouldBeVisibleTo(final Player viewer) {
        if (!this.entity.isValid() || viewer.isDead() || !viewer.getWorld().equals(this.entity.getWorld())) {
            return false;
        }
        final int distance = DisplayTags.get().config().getVisibilityDistance();
        final Location viewerLocation = Objects.requireNonNull(viewer.getLocation(), "an online player has a location");
        return viewerLocation.distanceSquared(this.entity.getLocation()) < (double) distance * distance;
    }

    private void despawnFor(final UUID viewerId) {
        if (this.viewers.remove(viewerId)) {
            this.display.despawnFor(viewerId);
        }
    }

    void despawnForViewers() {
        List.copyOf(this.viewers).forEach(this::despawnFor);
    }
}
