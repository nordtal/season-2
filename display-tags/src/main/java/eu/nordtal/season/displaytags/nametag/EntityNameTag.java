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
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The name tag above an entity that is not a player, with a text of its own for each viewer, such as a drawn pill.
 * It has no background or shadow and takes its offset and scale from the settings. Like a {@link PillHologram}, it
 * is four displays: the panel and the ink, which blocks hide, and a faint see-through copy of each.
 */
public class EntityNameTag {
    /** Visibility distance in blocks, matching vanilla player name tags. */
    static final int VISIBILITY_DISTANCE = 64;

    /**
     * Vertical lift in blocks at scale 1.0 that aligns the 13 px pill's bottom edge with a player name tag.
     * The pill glyph sits 8 text pixels below the baseline (height 13, ascent 5); 8 / 40 = 0.20 blocks.
     */
    static final double PLATE_LIFT = 0.20;

    /** A transparent background: the text draws its own. */
    private static final int NO_BACKGROUND = 0;

    /** Wide enough that no name wraps. */
    private static final int LINE_WIDTH = 10000;

    private static final int FULL_BRIGHTNESS = 15;

    private final UUID entityUuid;
    private final Function<UUID, @Nullable Entity> entityLookup;
    private final Function<UUID, PillHologram.Layers> text;
    private final BiPredicate<Entity, Player> tracked;
    private final TextDisplayWrapper display;
    private final TextDisplayWrapper ink;
    private final TextDisplayWrapper faintPanel;
    private final TextDisplayWrapper faintInk;
    final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    EntityNameTag(final Entity entity, final Function<UUID, PillHologram.Layers> text) {
        this(
                entity.getUniqueId(),
                Bukkit::getEntity,
                text,
                (target, viewer) -> target.getTrackedBy().contains(viewer),
                new TextDisplayWrapper(),
                new TextDisplayWrapper(),
                new TextDisplayWrapper(),
                new TextDisplayWrapper());
    }

    EntityNameTag(
            final UUID entityUuid,
            final Function<UUID, @Nullable Entity> entityLookup,
            final Function<UUID, PillHologram.Layers> text,
            final BiPredicate<Entity, Player> tracked,
            final TextDisplayWrapper display,
            final TextDisplayWrapper ink,
            final TextDisplayWrapper faintPanel,
            final TextDisplayWrapper faintInk) {
        this.entityUuid = entityUuid;
        this.entityLookup = entityLookup;
        this.text = text;
        this.tracked = tracked;
        this.display = display;
        this.ink = ink;
        this.faintPanel = faintPanel;
        this.faintInk = faintInk;
    }

    public UUID getEntityUuid() {
        return this.entityUuid;
    }

    /** Returns the live entity for this tag while its chunk is loaded, or null if unloaded. */
    public @Nullable Entity getEntity() {
        return this.entityLookup.apply(this.entityUuid);
    }

    /** Spawns, despawns or updates the display of every online player as needed. */
    void tick() {
        final @Nullable Entity live = this.getEntity();
        this.viewers.removeIf(viewerId -> Bukkit.getPlayer(viewerId) == null);
        if (live == null || !live.isValid()) {
            this.despawnForViewers();
            return;
        }
        this.syncLocations(live);
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            this.syncViewer(live, viewer.getUniqueId(), this.shouldBeVisibleTo(live, viewer));
        }
    }

    /** Synchronizes visibility and text for one viewer after tracking or world events. */
    void syncFor(final Player viewer) {
        final @Nullable Entity live = this.getEntity();
        if (live == null || !live.isValid()) {
            this.despawnFor(viewer.getUniqueId());
            return;
        }
        this.syncLocations(live);
        this.syncViewer(live, viewer.getUniqueId(), this.shouldBeVisibleTo(live, viewer));
    }

    /** Forces a fresh spawn and mount for {@code viewer} if the entity is currently tracked and in range. */
    void respawnFor(final Player viewer) {
        this.despawnFor(viewer.getUniqueId());
        this.syncFor(viewer);
    }

    void syncViewer(final Entity live, final UUID viewerId, final boolean shouldBeVisible) {
        if (!shouldBeVisible) {
            this.despawnFor(viewerId);
            return;
        }
        if (this.viewers.add(viewerId)) {
            this.spawnDisplaysFor(viewerId);
        }
        this.updateFor(live, viewerId);
    }

    void spawnDisplaysFor(final UUID viewerId) {
        this.display.spawnFor(viewerId);
        this.ink.spawnFor(viewerId);
        this.faintPanel.spawnFor(viewerId);
        this.faintInk.spawnFor(viewerId);
    }

    private void syncLocations(final Entity live) {
        final Location at = live.getLocation().setRotation(0, 0);
        this.display.setLocation(at);
        this.ink.setLocation(at);
        this.faintPanel.setLocation(at);
        this.faintInk.setLocation(at);
    }

    // The text is set right before the metadata goes to one viewer, so each viewer gets their own.
    void updateFor(final Entity live, final UUID viewerId) {
        final NameTagConfiguration config = DisplayTags.get().config();
        final PillHologram.Layers pill = this.text.apply(viewerId);
        final Vector baseOffset = liftedOffset(config.getOffset(), config.getScale());
        this.apply(this.display, pill.panel(), baseOffset);
        final Vector lifted = baseOffset.clone().add(new Vector(0, 0, PillHologram.INK_LIFT));
        this.apply(this.ink, pill.ink(), lifted);
        this.apply(this.faintPanel, pill.panel(), baseOffset);
        this.faintPanel.setSeeThrough(true);
        this.faintPanel.setTextOpacity(PillHologram.SEE_THROUGH_OPACITY);
        this.apply(this.faintInk, pill.ink(), lifted);
        this.faintInk.setSeeThrough(true);
        this.faintInk.setTextOpacity(PillHologram.SEE_THROUGH_INK_OPACITY);
        // A SetPassengers packet is absolute, so the displays go out together.
        EntityWrapper.mountAllFor(
                viewerId,
                live.getEntityId(),
                this.display.getEntityId(),
                this.ink.getEntityId(),
                this.faintPanel.getEntityId(),
                this.faintInk.getEntityId());
        this.display.updateFor(viewerId);
        this.ink.updateFor(viewerId);
        this.faintPanel.updateFor(viewerId);
        this.faintInk.updateFor(viewerId);
    }

    /** Returns {@code offset} raised by {@link #PLATE_LIFT} scaled by {@code scale}'s vertical factor. */
    static Vector liftedOffset(final Vector offset, final Vector scale) {
        return offset.clone().add(new Vector(0, PLATE_LIFT * scale.getY(), 0));
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

    boolean shouldBeVisibleTo(final Entity live, final Player viewer) {
        if (!live.isValid()
                || !viewer.isOnline()
                || viewer.isDead()
                || !viewer.getWorld().equals(live.getWorld())
                || !this.tracked.test(live, viewer)) {
            return false;
        }
        final Location viewerLocation = Objects.requireNonNull(viewer.getLocation(), "an online player has a location");
        return viewerLocation.distanceSquared(live.getLocation()) < (double) VISIBILITY_DISTANCE * VISIBILITY_DISTANCE;
    }

    void despawnFor(final UUID viewerId) {
        if (this.viewers.remove(viewerId)) {
            this.despawnDisplaysFor(viewerId);
        }
    }

    void despawnDisplaysFor(final UUID viewerId) {
        this.display.despawnFor(viewerId);
        this.ink.despawnFor(viewerId);
        this.faintPanel.despawnFor(viewerId);
        this.faintInk.despawnFor(viewerId);
    }

    /** Drops {@code viewerId} from the viewer set without sending destroy packets, as on quit. */
    void forgetViewer(final UUID viewerId) {
        this.viewers.remove(viewerId);
    }

    void despawnForViewers() {
        List.copyOf(this.viewers).forEach(this::despawnFor);
    }
}
