package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
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
 * The name tag above an entity that is not a player, with a text of its own for each viewer, such as a drawn pill.
 * It has no background or shadow and is seen through blocks; it takes its offset and scale from the settings.
 */
public final class EntityNameTag {
    /** A transparent background: the text draws its own. */
    private static final int NO_BACKGROUND = 0;

    /** Wide enough that no name wraps. */
    private static final int LINE_WIDTH = 10000;

    private static final int FULL_BRIGHTNESS = 15;

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
        this.display.setTextAlignment(TextAlignment.CENTER);
        this.display.setBillboard(DisplayBillboard.CENTER);
        this.display.setTextShadow(false);
        // A see-through display draws its letters after its pill without a depth test, so they never z-fight.
        this.display.setSeeThrough(true);
        this.display.setBackground(NO_BACKGROUND);
        this.display.setLineWidth(LINE_WIDTH);
        // As every pill hologram: the same grey by day, by night and behind a wall.
        this.display.setBrightness(FULL_BRIGHTNESS);
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
