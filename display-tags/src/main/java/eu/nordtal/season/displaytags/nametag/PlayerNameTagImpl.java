package eu.nordtal.season.displaytags.nametag;

import eu.nordtal.season.displaytags.ComponentUtil;
import eu.nordtal.season.displaytags.Constants;
import eu.nordtal.season.displaytags.DependencyUtil;
import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.api.events.NameTagDespawnEvent;
import eu.nordtal.season.displaytags.api.events.NameTagSpawnEvent;
import eu.nordtal.season.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.season.displaytags.api.nametag.SeeThroughMode;
import eu.nordtal.season.displaytags.config.NameTagConfiguration;
import eu.nordtal.season.displaytags.wrapper.EntityWrapper;
import eu.nordtal.season.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
import eu.nordtal.season.displaytags.wrapper.display.TextDisplayWrapper;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jspecify.annotations.Nullable;

public class PlayerNameTagImpl extends PlayerNameTag {
    /**
     * The name as it is seen with nothing in the way: depth-tested, fully opaque.
     */
    private final TextDisplayWrapper display;

    /**
     * The faint copy drawn through blocks under the opaque one: the whole of what {@link SeeThroughMode#VANILLA} adds.
     *
     * It is spawned only when {@link #shouldDrawGhost()} says so.
     */
    private final TextDisplayWrapper ghost;

    // The ghost's viewers come and go with the mode and with sneaking, so they need their own set.
    private final Set<UUID> ghostViewers = ConcurrentHashMap.newKeySet();

    // A vanilla name stays hidden even when the display is not shown, so this differs from the viewers above.
    private final Set<UUID> vanillaHidden = ConcurrentHashMap.newKeySet();

    // The name tag text is cached temporarily, and it is only changed when the name tag ticks.
    private Component cachedText;

    // The line list the resolved lines were derived from, kept to detect API-side changes.
    private @Nullable List<String> sourceLines;

    private @Nullable List<String> resolvedLines;

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
        this.data.setLines(config.getLines());
        this.data.setTextAlignment(alignment);
        this.data.setBillboard(billboard);
        this.data.setTextShadow(config.hasTextShadow());
        this.data.setSeeThrough(config.getSeeThrough());
        this.data.setBackground(config.getBackground());
        this.data.setTranslation(config.getOffset());
        this.data.setScale(config.getScale());

        // A tag created while already sneaking starts dimmed: no PlayerToggleSneakEvent arrives for that state.
        this.data.setSneaking(player.isSneaking());
        if (config.hasSneakTextOpacity() && player.isSneaking()) {
            this.data.setTextOpacity(config.getSneakTextOpacity());
        }

        this.cachedText = this.getText();
    }

    @Override
    public void spawnFor(final UUID viewerId) {
        // Suppress the vanilla name tag for this viewer, otherwise they would see two names.
        this.hideVanillaNameTagFor(viewerId);

        // "show-to-self: false" only concerns the tag's own owner; every other viewer still sees it.
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
        // With the ghost present, the background belongs to it alone and stays visible through a wall.
        this.display.setBackground(ghost ? Constants.TRANSPARENT_TEXT_DISPLAY_BACKGROUND : this.data.getBackground());

        if (ghost) {
            this.apply(this.ghost);
            this.ghost.setSeeThrough(true);
            this.ghost.setTextOpacity(Constants.VANILLA_OCCLUDED_TEXT_OPACITY);
            this.ghost.setBackground(this.data.getBackground());
        }

        // The ghost spawns and despawns here, not in spawnFor/despawnFor, because it follows the mode, not the viewer.
        if (ghost && this.ghostViewers.add(viewerId)) {
            this.ghost.setLocation(this.display.getLocation());
            this.ghost.spawnFor(viewerId);
        } else if (!ghost && this.ghostViewers.remove(viewerId)) {
            this.ghost.despawnFor(viewerId);
        }

        // A SetPassengers packet is absolute, so both displays go out together or the second unseats the first.
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

    /** Whether the faint see-through copy is drawn: like vanilla, not for a sneaking player. */
    private boolean shouldDrawGhost() {
        return this.data.getSeeThrough() == SeeThroughMode.VANILLA && !this.data.isSneaking();
    }

    /** Everything both copies share; see-through, opacity and background are what make one of them the faint one. */
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
        // clone() leaves the caller's Location alone; it is usually the live PlayerTeleportEvent destination.
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

        // A viewer that already logged out cannot be handed to event listeners but still has to leave the viewer set.
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

        // A reconnecting client starts with an empty scoreboard, so it forgets who was hidden from as soon as it drops.
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
        // A tag whose player has left must never spawn again, even between the quit event and the tag being removed.
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
     * Suppresses the player's vanilla name tag for a viewer, once.
     *
     * Sending the team packet again on every tick makes the client log a warning about a team it knows.
     */
    private void hideVanillaNameTagFor(final UUID viewerId) {
        if (this.vanillaHidden.contains(viewerId)) {
            return;
        }

        // Only remember the viewer when a packet really went out, so a missing packet is never suppressed for good.
        if (VanillaNameTagUtil.hide(this.player, viewerId)) {
            this.vanillaHidden.add(viewerId);
        }
    }

    /** Hands the vanilla name tag back to every viewer it was hidden from, once the tag is removed. */
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

    /**
     * Whether {@code viewerId} is the player this name tag belongs to.
     */
    private boolean isOwner(final UUID viewerId) {
        return this.player.getUniqueId().equals(viewerId);
    }

    private Component getText() {
        final List<String> lines = this.getResolvedLines().stream()
                .map((line) -> {
                    String modified = line.replace(
                            "{health}", String.valueOf(new DecimalFormat("#.##").format(this.player.getHealth())));
                    if (DependencyUtil.enabledPlaceholderAPI()) {
                        modified = PlaceholderAPI.setPlaceholders(this.player, modified);
                    }

                    return modified;
                })
                .toList();

        return ComponentUtil.render(lines);
    }

    /**
     * The configured lines with {@code {player}} substituted once, since a player's name does not change.
     *
     * The raw lines stay in {@link eu.nordtal.season.displaytags.api.nametag.NameTagData}; replacing them redoes this.
     */
    private List<String> getResolvedLines() {
        final List<String> lines = this.data.getLines();
        List<String> resolved = this.resolvedLines;
        if (resolved == null || !lines.equals(this.sourceLines)) {
            this.sourceLines = lines;
            resolved = lines.stream()
                    .map((line) -> line.replace("{player}", this.player.getName()))
                    .toList();
            this.resolvedLines = resolved;
        }

        return resolved;
    }
}
