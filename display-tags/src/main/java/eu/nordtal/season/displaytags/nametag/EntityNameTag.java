package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.wrapper.EntityWrapper;
import eu.nordtal.season.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
import eu.nordtal.season.displaytags.wrapper.display.TextDisplayWrapper;
import eu.nordtal.season.papercommon.hud.PillHologram;
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
import org.bukkit.util.Vector;

/**
 * The name tag above an entity that is not a player, with a text of its own for each viewer, such as a drawn pill.
 * It has no background or shadow and takes its offset and scale from the settings. Like a {@link PillHologram}, it
 * is four displays: the panel and the ink, which blocks hide, and a faint see-through copy of each.
 */
public final class EntityNameTag {
    /** A transparent background: the text draws its own. */
    private static final int NO_BACKGROUND = 0;

    /** Wide enough that no name wraps. */
    private static final int LINE_WIDTH = 10000;

    private static final int FULL_BRIGHTNESS = 15;

    private final Entity entity;
    private final Function<UUID, PillHologram.Layers> text;
    private final TextDisplayWrapper display = new TextDisplayWrapper();
    private final TextDisplayWrapper ink = new TextDisplayWrapper();
    private final TextDisplayWrapper faintPanel = new TextDisplayWrapper();
    private final TextDisplayWrapper faintInk = new TextDisplayWrapper();
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    EntityNameTag(final Entity entity, final Function<UUID, PillHologram.Layers> text) {
        this.entity = entity;
        this.text = text;
    }

    public Entity getEntity() {
        return this.entity;
    }

    /** Spawns, despawns or updates the display of every online player as needed. */
    void tick() {
        this.display.setLocation(this.entity.getLocation().setRotation(0, 0));
        this.ink.setLocation(this.display.getLocation());
        this.faintPanel.setLocation(this.display.getLocation());
        this.faintInk.setLocation(this.display.getLocation());
        this.viewers.removeIf(viewerId -> Bukkit.getPlayer(viewerId) == null);
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            final UUID viewerId = viewer.getUniqueId();
            if (!this.shouldBeVisibleTo(viewer)) {
                this.despawnFor(viewerId);
                continue;
            }
            if (this.viewers.add(viewerId)) {
                this.display.spawnFor(viewerId);
                this.ink.spawnFor(viewerId);
                this.faintPanel.spawnFor(viewerId);
                this.faintInk.spawnFor(viewerId);
            }
            this.updateFor(viewerId);
        }
    }

    // The text is set right before the metadata goes to one viewer, so each viewer gets their own.
    private void updateFor(final UUID viewerId) {
        final NameTagConfiguration config = DisplayTags.get().config();
        final PillHologram.Layers pill = this.text.apply(viewerId);
        this.apply(this.display, pill.panel(), config.getOffset());
        final Vector lifted = config.getOffset().clone().add(new Vector(0, 0, PillHologram.INK_LIFT));
        this.apply(this.ink, pill.ink(), lifted);
        this.apply(this.faintPanel, pill.panel(), config.getOffset());
        this.faintPanel.setSeeThrough(true);
        this.faintPanel.setTextOpacity(PillHologram.SEE_THROUGH_OPACITY);
        this.apply(this.faintInk, pill.ink(), lifted);
        this.faintInk.setSeeThrough(true);
        this.faintInk.setTextOpacity(PillHologram.SEE_THROUGH_INK_OPACITY);
        // A SetPassengers packet is absolute, so the displays go out together.
        EntityWrapper.mountAllFor(
                viewerId,
                this.entity.getEntityId(),
                this.display.getEntityId(),
                this.ink.getEntityId(),
                this.faintPanel.getEntityId(),
                this.faintInk.getEntityId());
        this.display.updateFor(viewerId);
        this.ink.updateFor(viewerId);
        this.faintPanel.updateFor(viewerId);
        this.faintInk.updateFor(viewerId);
    }

    private void apply(final TextDisplayWrapper wrapper, final Component text, final Vector offset) {
        final NameTagConfiguration config = DisplayTags.get().config();
        wrapper.setTextAlignment(TextAlignment.CENTER);
        wrapper.setBillboard(DisplayBillboard.CENTER);
        wrapper.setTextShadow(false);
        wrapper.setSeeThrough(false);
        wrapper.setBackground(NO_BACKGROUND);
        wrapper.setLineWidth(LINE_WIDTH);
        // As every pill hologram: the same grey by day and by night.
        wrapper.setBrightness(FULL_BRIGHTNESS);
        wrapper.setTranslation(offset);
        wrapper.setScale(config.getScale());
        wrapper.setText(text);
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
            this.ink.despawnFor(viewerId);
            this.faintPanel.despawnFor(viewerId);
            this.faintInk.despawnFor(viewerId);
        }
    }

    void despawnForViewers() {
        List.copyOf(this.viewers).forEach(this::despawnFor);
    }
}
