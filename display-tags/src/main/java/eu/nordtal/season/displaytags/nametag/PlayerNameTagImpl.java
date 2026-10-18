package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.ComponentUtil;
import eu.nordtal.season.displaytags.Constants;
import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.SeeThroughMode;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.wrapper.EntityWrapper;
import eu.nordtal.season.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
import eu.nordtal.season.displaytags.wrapper.display.TextDisplayWrapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

public class PlayerNameTagImpl extends PlayerNameTag {
    /** The name with nothing in the way: depth-tested, fully opaque. */
    private final TextDisplayWrapper display;

    /**
     * The faint copy drawn through blocks under the opaque one.
     * It is spawned only when {@link #shouldDrawGhost()} says so.
     */
    private final TextDisplayWrapper ghost;

    // The ghost's viewers follow the mode and sneaking.
    private final Set<UUID> ghostViewers = ConcurrentHashMap.newKeySet();

    // A vanilla name stays hidden while the display is not shown, so this set differs from the viewers.
    private final Set<UUID> vanillaHidden = ConcurrentHashMap.newKeySet();

    // The cached text changes only when the name tag ticks.
    private Component cachedText;

    public PlayerNameTagImpl(final Player player) {
        super(player);
        this.display = new TextDisplayWrapper();
        this.ghost = new TextDisplayWrapper();

        final NameTagConfiguration config = DisplayTags.get().config();
        final TextDisplay.TextAlignment alignment =
                TextDisplay.TextAlignment.valueOf(config.getTextAlignment().name());
        final Display.Billboard billboard =
                Display.Billboard.valueOf(config.getBillboard().name());

        this.data.setShowToSelf(config.showToSelf());
        this.data.setVisibilityDistance(config.getVisibilityDistance());
        this.data.setTextAlignment(alignment);
        this.data.setBillboard(billboard);
        this.data.setTextShadow(config.hasTextShadow());
        this.data.setSeeThrough(config.getSeeThrough());
        this.data.setBackground(config.getBackground());
        this.data.setTranslation(config.getOffset());
        this.data.setScale(config.getScale());

        // A tag created while the player sneaks starts dimmed: no sneak event arrives for that state.
        this.data.setSneaking(player.isSneaking());
        if (config.hasSneakTextOpacity() && player.isSneaking()) {
            this.data.setTextOpacity(config.getSneakTextOpacity());
        }

        this.cachedText = this.getText();
    }

    @Override
    public void spawnFor(final UUID viewerId) {
        this.hideVanillaNameTagFor(viewerId);

        // show-to-self: false concerns the owner only.
        if (!this.data.shouldShowToSelf() && this.isOwner(viewerId)) {
            return;
        }

        final Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) {
            return;
        }

        final NameTagSpawnEvent event = new NameTagSpawnEvent(this, viewer);
        if (!event.callEvent()) {
            return;
        }

        this.viewers.add(viewerId);
        this.display.spawnFor(viewerId);
        this.updateFor(viewerId);
    }

    @Override
    public void updateFor(final UUID viewerId) {
        final boolean ghost = this.shouldDrawGhost();

        this.apply(this.display);
        this.display.setSeeThrough(this.data.getSeeThrough() == SeeThroughMode.ALWAYS);
        this.display.setTextOpacity(this.data.getTextOpacity());
        // With a ghost, the background belongs to the ghost alone.
        this.display.setBackground(ghost ? Constants.TRANSPARENT_TEXT_DISPLAY_BACKGROUND : this.data.getBackground());

        if (ghost) {
            this.apply(this.ghost);
            this.ghost.setSeeThrough(true);
            this.ghost.setTextOpacity(Constants.VANILLA_OCCLUDED_TEXT_OPACITY);
            this.ghost.setBackground(this.data.getBackground());
        }

        // The ghost follows the mode, not the viewer, so it spawns and despawns here.
        if (ghost && this.ghostViewers.add(viewerId)) {
            this.ghost.setLocation(this.display.getLocation());
            this.ghost.spawnFor(viewerId);
        } else if (!ghost && this.ghostViewers.remove(viewerId)) {
            this.ghost.despawnFor(viewerId);
        }

        // A SetPassengers packet is absolute, so both displays go out together.
        if (ghost) {
            EntityWrapper.mountAllFor(
                    viewerId, this.player.getEntityId(), this.display.getEntityId(), this.ghost.getEntityId());
        } else {
            this.display.mountFor(viewerId, this.player.getEntityId());
        }

        this.display.updateFor(viewerId);
        if (ghost) {
            this.ghost.updateFor(viewerId);
        }
    }

    /** Whether the faint see-through copy is drawn: not for a sneaking player, as in vanilla. */
    private boolean shouldDrawGhost() {
        return this.data.getSeeThrough() == SeeThroughMode.VANILLA && !this.data.isSneaking();
    }

    /** Applies the settings both copies share. See-through, opacity and background differ for the ghost. */
    private void apply(final TextDisplayWrapper display) {
        display.setTextAlignment(
                TextAlignment.valueOf(this.data.getTextAlignment().name()));
        display.setBillboard(DisplayBillboard.valueOf(this.data.getBillboard().name()));
        display.setTextShadow(this.data.hasTextShadow());
        display.setTranslation(this.data.getTranslation());
        display.setScale(this.data.getScale());
        display.setText(this.cachedText);
    }

    @Override
    public void teleportFor(final UUID viewerId) {
        this.display.teleportFor(viewerId);
        if (this.ghostViewers.contains(viewerId)) {
            this.ghost.teleportFor(viewerId);
        }
    }

    @Override
    public void teleportFor(final UUID viewerId, final Location location) {
        // clone() leaves the caller's location alone.
        this.display.setLocation(location.clone().setRotation(0, 0));
        this.ghost.setLocation(this.display.getLocation());
        this.display.teleportFor(viewerId);
        if (this.ghostViewers.contains(viewerId)) {
            this.ghost.teleportFor(viewerId);
        }
    }

    @Override
    public void despawnFor(final UUID viewerId) {
        if (!this.viewers.contains(viewerId)) {
            return;
        }

        // A viewer who logged out cannot be passed to listeners but still has to leave the viewer set.
        final Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer != null) {
            final NameTagDespawnEvent event = new NameTagDespawnEvent(this, viewer);
            if (!event.callEvent()) {
                return;
            }
        }

        this.viewers.remove(viewerId);
        this.display.despawnFor(viewerId);
        if (this.ghostViewers.remove(viewerId)) {
            this.ghost.despawnFor(viewerId);
        }
    }

    @Override
    public void tick() {
        this.cachedText = this.getText();
        final Location location = Objects.requireNonNull(this.player.getLocation(), "an online player has a location");
        this.display.setLocation(location.setRotation(0, 0));
        this.ghost.setLocation(this.display.getLocation());

        this.viewers.removeIf(PlayerNameTagImpl::isOffline);
        this.ghostViewers.removeIf(PlayerNameTagImpl::isOffline);

        // A reconnecting client starts with an empty scoreboard.
        this.vanillaHidden.removeIf(PlayerNameTagImpl::isOffline);

        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            this.hideVanillaNameTagFor(viewer.getUniqueId());

            final boolean visible = this.viewers.contains(viewer.getUniqueId());
            final boolean shouldBeVisible = this.shouldBeVisibleTo(viewer);

            if (shouldBeVisible && !visible) {
                this.spawnFor(viewer);
            } else if (!shouldBeVisible && visible) {
                this.despawnFor(viewer);
            } else if (shouldBeVisible) {
                this.updateFor(viewer);
            }
        }
    }

    private boolean shouldBeVisibleTo(final Player viewer) {
        // A tag whose player left never spawns again, even between the quit event and its removal.
        if (!this.player.isOnline()) {
            return false;
        }
        if (!viewer.isOnline() || viewer.isDead()) {
            return false;
        }
        if (!this.data.shouldShowToSelf() && this.isOwner(viewer.getUniqueId())) {
            return false;
        }
        if (!viewer.getWorld().getName().equals(this.player.getWorld().getName())) {
            return false;
        }
        if (this.player.isInvisible() || !viewer.canSee(this.player)) {
            return false;
        }
        if (this.player.isDead() || this.player.getGameMode().equals(GameMode.SPECTATOR)) {
            return false;
        }

        final int visibilityDistance = this.data.getVisibilityDistance();
        final Location playerLocation =
                Objects.requireNonNull(this.player.getLocation(), "an online player has a location");
        final Location viewerLocation = Objects.requireNonNull(viewer.getLocation(), "an online player has a location");
        return viewerLocation.distanceSquared(playerLocation) < visibilityDistance * visibilityDistance;
    }

    /**
     * Hides the player's vanilla name tag from a viewer once.
     * A repeated team packet makes the client log a warning.
     */
    private void hideVanillaNameTagFor(final UUID viewerId) {
        if (this.vanillaHidden.contains(viewerId)) {
            return;
        }

        // A viewer is remembered only after a packet went out.
        if (VanillaNameTagUtil.hide(this.player, viewerId)) {
            this.vanillaHidden.add(viewerId);
        }
    }

    /** Restores the vanilla name tag for every viewer it was hidden from. */
    void restoreVanillaNameTags() {
        for (final UUID viewerId : List.copyOf(this.vanillaHidden)) {
            VanillaNameTagUtil.show(this.player, viewerId);
        }

        this.vanillaHidden.clear();
    }

    private static boolean isOffline(final UUID viewerId) {
        final Player viewer = Bukkit.getPlayer(viewerId);
        return viewer == null || !viewer.isOnline();
    }

    private boolean isOwner(final UUID viewerId) {
        return this.player.getUniqueId().equals(viewerId);
    }

    private Component getText() {
        return ComponentUtil.render(this.data.getLines());
    }
}
