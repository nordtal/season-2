package eu.nordtal.displaytags.nametag;

import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.api.events.NameTagDespawnEvent;
import eu.nordtal.displaytags.api.events.NameTagSpawnEvent;
import eu.nordtal.displaytags.api.nametag.PlayerNameTag;
import eu.nordtal.displaytags.api.nametag.SeeThroughMode;
import eu.nordtal.displaytags.config.NameTagConfiguration;
import eu.nordtal.displaytags.util.ComponentUtil;
import eu.nordtal.displaytags.util.Constants;
import eu.nordtal.displaytags.util.DependencyUtil;
import eu.nordtal.displaytags.util.VanillaNameTagUtil;
import eu.nordtal.displaytags.wrapper.EntityWrapper;
import eu.nordtal.displaytags.wrapper.display.DisplayBillboard;
import eu.nordtal.displaytags.wrapper.display.TextAlignment;
import eu.nordtal.displaytags.wrapper.display.TextDisplayWrapper;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

public class PlayerNameTagImpl extends PlayerNameTag {
    /**
     * The name as it is seen with nothing in the way: depth-tested, fully opaque.
     */
    private final TextDisplayWrapper display;

    /**
     * The faint copy that is drawn through blocks, and the whole of what
     * {@link SeeThroughMode#VANILLA} adds.
     * <p>
     * Vanilla's name tag is two draws of the same text, not one: a see-through pass in alpha 32
     * that carries the background, and an opaque pass on top of it that does not. Where nothing
     * blocks the view the opaque pass covers the faint one - both carry the same text in the same
     * colours, so the result looks exactly like a single opaque name - and behind a wall only the
     * faint one survives. A text display cannot do that alone: its {@code see_through} flag is
     * either off or full brightness. Two displays can, and this is the second one.
     * <p>
     * It is spawned only while it is actually needed (see {@link #shouldDrawGhost()}), so the
     * other two modes cost exactly what they did before.
     */
    private final TextDisplayWrapper ghost;

    // The viewers the ghost display is currently spawned for. It comes and goes with the mode and
    // with sneaking, while the display above exists for every viewer, so the two cannot share a
    // viewer set.
    private final Set<UUID> ghostViewers = ConcurrentHashMap.newKeySet();

    // The viewers whose vanilla name tag for this player is currently suppressed. This is not the
    // same set as the viewers of the display: the vanilla name has to stay hidden even where the
    // display is not shown (out of range, spectator, show-to-self), otherwise the vanilla name
    // would reappear exactly where DisplayTags decided not to render one.
    private final Set<UUID> vanillaHidden = ConcurrentHashMap.newKeySet();

    // The name tag text is cached temporarily, and it is only changed when the name tag ticks.
    private Component cachedText;

    // The line list the resolved lines were derived from, kept to detect API-side changes.
    private List<String> sourceLines;
    private List<String> resolvedLines;

    public PlayerNameTagImpl(Player player) {
        super(player);
        this.display = new TextDisplayWrapper();
        this.ghost = new TextDisplayWrapper();

        NameTagConfiguration config = DisplayTags.get().config().nametag();
        TextDisplay.TextAlignment alignment =
                TextDisplay.TextAlignment.valueOf(config.getTextAlignment().name());
        Display.Billboard billboard =
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

        // A tag created while its player is already sneaking (rejoin, reload, world change) has to
        // start out dimmed, because no PlayerToggleSneakEvent is going to arrive for that state.
        this.data.setSneaking(player.isSneaking());
        if (config.hasSneakTextOpacity() && player.isSneaking()) {
            this.data.setTextOpacity(config.getSneakTextOpacity());
        }

        this.cachedText = getText();
    }

    @Override
    public void spawnFor(UUID viewerId) {
        // Suppress the vanilla name tag for this viewer, otherwise they would see two names.
        this.hideVanillaNameTagFor(viewerId);

        // "show-to-self: false" only concerns the tag's own owner; every other viewer still sees it.
        if (!this.data.shouldShowToSelf() && this.isOwner(viewerId)) return;

        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;

        NameTagSpawnEvent event = new NameTagSpawnEvent(this, viewer);
        if (!event.callEvent()) return;

        this.viewers.add(viewerId);
        this.display.spawnFor(viewerId);
        this.updateFor(viewerId);
    }

    @Override
    public void updateFor(UUID viewerId) {
        boolean ghost = this.shouldDrawGhost();

        this.apply(this.display);
        this.display.setSeeThrough(this.data.getSeeThrough() == SeeThroughMode.ALWAYS);
        this.display.setTextOpacity(this.data.getTextOpacity());
        // With the ghost present the background belongs to it and to it alone, which is how vanilla
        // draws it: the box rides the see-through pass, so it is visible through a wall as well,
        // and a second box on this display would sit on top of the first and darken it twice.
        this.display.setBackground(ghost ? Constants.TRANSPARENT_TEXT_DISPLAY_BACKGROUND : this.data.getBackground());

        if (ghost) {
            this.apply(this.ghost);
            this.ghost.setSeeThrough(true);
            this.ghost.setTextOpacity(Constants.VANILLA_OCCLUDED_TEXT_OPACITY);
            this.ghost.setBackground(this.data.getBackground());
        }

        // Spawning and despawning the ghost happens here rather than in spawnFor/despawnFor,
        // because it does not follow the viewer: it follows the mode and the player's sneaking.
        if (ghost && this.ghostViewers.add(viewerId)) {
            this.ghost.setLocation(this.display.getLocation());
            this.ghost.spawnFor(viewerId);
        } else if (!ghost && this.ghostViewers.remove(viewerId)) {
            this.ghost.despawnFor(viewerId);
        }

        // The mount is re-sent on every update, not only once at spawn time. A SetPassengers packet
        // is absolute - it replaces the vehicle's whole passenger list - so resending it is both
        // idempotent and self-healing if a client ever drops or overwrites the list. For the same
        // reason both displays have to go out in one packet: sent one after the other, the second
        // would throw the first off the player.
        if (ghost) {
            EntityWrapper.mountAllFor(
                    viewerId, this.player.getEntityId(), this.display.getEntityId(), this.ghost.getEntityId());
        } else {
            this.display.mountFor(viewerId, this.player.getEntityId());
        }

        this.display.updateFor(viewerId);
        if (ghost) this.ghost.updateFor(viewerId);
    }

    /**
     * Whether the faint see-through copy is drawn at all.
     * <p>
     * Sneaking takes it away on purpose: vanilla drops its see-through pass for a sneaking player,
     * so the name is dimmed in plain view and gone behind a wall.
     */
    private boolean shouldDrawGhost() {
        return this.data.getSeeThrough() == SeeThroughMode.VANILLA && !this.data.isSneaking();
    }

    /**
     * Everything both copies share. What they must <em>not</em> share is see-through, opacity and
     * background - those three are what makes one of them the faint one.
     */
    private void apply(TextDisplayWrapper display) {
        display.setTextAlignment(
                TextAlignment.valueOf(this.data.getTextAlignment().name()));
        display.setBillboard(DisplayBillboard.valueOf(this.data.getBillboard().name()));
        display.setTextShadow(this.data.hasTextShadow());
        display.setTranslation(this.data.getTranslation());
        display.setScale(this.data.getScale());
        display.setText(this.cachedText);
    }

    @Override
    public void teleportFor(UUID viewerId) {
        this.display.teleportFor(viewerId);
        if (this.ghostViewers.contains(viewerId)) this.ghost.teleportFor(viewerId);
    }

    @Override
    public void teleportFor(UUID viewerId, Location location) {
        // setRotation(0, 0) keeps the display upright; clone() so the caller's Location - which is
        // usually the live PlayerTeleportEvent destination - is left alone.
        this.display.setLocation(location.clone().setRotation(0, 0));
        this.ghost.setLocation(this.display.getLocation());
        this.display.teleportFor(viewerId);
        if (this.ghostViewers.contains(viewerId)) this.ghost.teleportFor(viewerId);
    }

    @Override
    public void despawnFor(UUID viewerId) {
        if (!this.viewers.contains(viewerId)) return;

        // A viewer that has already logged out cannot be handed to event listeners, but they still
        // have to leave the viewer set - otherwise the tag would consider them a viewer forever.
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer != null) {
            NameTagDespawnEvent event = new NameTagDespawnEvent(this, viewer);
            if (!event.callEvent()) return;
        }

        this.viewers.remove(viewerId);
        this.display.despawnFor(viewerId);
        if (this.ghostViewers.remove(viewerId)) this.ghost.despawnFor(viewerId);
    }

    @Override
    public void tick() {
        this.cachedText = getText();
        this.display.setLocation(this.player.getLocation().setRotation(0, 0));
        this.ghost.setLocation(this.display.getLocation());

        this.viewers.removeIf(PlayerNameTagImpl::isOffline);
        this.ghostViewers.removeIf(PlayerNameTagImpl::isOffline);

        // A reconnecting client starts with an empty scoreboard, so the team packet has to be sent
        // again - forget who was hidden from as soon as they go offline.
        this.vanillaHidden.removeIf(PlayerNameTagImpl::isOffline);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            this.hideVanillaNameTagFor(viewer.getUniqueId());

            boolean visible = this.viewers.contains(viewer.getUniqueId());
            boolean shouldBeVisible = this.shouldBeVisibleTo(viewer);

            if (shouldBeVisible && !visible) {
                this.spawnFor(viewer);
            } else if (!shouldBeVisible && visible) {
                this.despawnFor(viewer);
            } else if (shouldBeVisible) {
                this.updateFor(viewer);
            }
        }
    }

    private boolean shouldBeVisibleTo(Player viewer) {
        // A tag whose player has left must never spawn again, even if it is still registered - for
        // instance when something ticks it between the quit event and the tag being removed.
        if (!this.player.isOnline()) return false;
        if (viewer == null || !viewer.isOnline() || viewer.isDead()) return false;
        if (!this.data.shouldShowToSelf() && this.isOwner(viewer.getUniqueId())) return false;
        if (!viewer.getWorld().getName().equals(this.player.getWorld().getName())) return false;
        if (this.player.isInvisible() || !viewer.canSee(this.player)) return false;
        if (this.player.isDead() || this.player.getGameMode().equals(GameMode.SPECTATOR)) return false;

        int visibilityDistance = this.data.getVisibilityDistance();
        return viewer.getLocation().distanceSquared(player.getLocation()) < visibilityDistance * visibilityDistance;
    }

    /**
     * Suppresses the player's vanilla name tag for a viewer, once. Re-sending the team packet on
     * every tick would work, but it makes the client log a warning about a team it already knows.
     */
    private void hideVanillaNameTagFor(UUID viewerId) {
        if (this.vanillaHidden.contains(viewerId)) return;

        // Only remember the viewer when a packet really went out: with TAB present nothing is sent,
        // and marking them anyway would suppress the packet for good if TAB ever stops handling it.
        if (VanillaNameTagUtil.hide(this.player, viewerId)) this.vanillaHidden.add(viewerId);
    }

    /**
     * Hands the vanilla name tag back to every viewer it was hidden from. Called when the name tag
     * is removed - after that point DisplayTags no longer renders a name for this player, so the
     * vanilla one has to come back.
     */
    void restoreVanillaNameTags() {
        for (UUID viewerId : List.copyOf(this.vanillaHidden)) {
            VanillaNameTagUtil.show(this.player, viewerId);
        }

        this.vanillaHidden.clear();
    }

    private static boolean isOffline(UUID viewerId) {
        Player viewer = Bukkit.getPlayer(viewerId);
        return viewer == null || !viewer.isOnline();
    }

    /**
     * Whether {@code viewerId} is the player this name tag belongs to.
     */
    private boolean isOwner(UUID viewerId) {
        return this.player.getUniqueId().equals(viewerId);
    }

    private Component getText() {
        List<String> lines = this.getResolvedLines().stream()
                .map((line) -> {
                    String modified = line.replace(
                            "{health}", String.valueOf(new DecimalFormat("#.##").format(player.getHealth())));
                    if (DependencyUtil.enabledPlaceholderAPI())
                        modified = PlaceholderAPI.setPlaceholders(this.player, modified);

                    return modified;
                })
                .toList();

        return ComponentUtil.render(lines);
    }

    /**
     * The configured lines with {@code {player}} already substituted.
     * <p>
     * A player's name is static, so it is resolved once instead of on every tick. The raw lines stay
     * in {@link eu.nordtal.displaytags.api.nametag.NameTagData} so that API consumers still read
     * back what was configured; the substitution is redone whenever they replace the line list.
     */
    private List<String> getResolvedLines() {
        List<String> lines = this.data.getLines();
        if (lines != this.sourceLines) {
            this.sourceLines = lines;
            this.resolvedLines = lines.stream()
                    .map((line) -> line.replace("{player}", this.player.getName()))
                    .toList();
        }

        return this.resolvedLines;
    }
}
