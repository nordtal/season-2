package me.skyyiscool.displaytags.nametag;

import me.clip.placeholderapi.PlaceholderAPI;
import me.skyyiscool.displaytags.DisplayTags;
import me.skyyiscool.displaytags.api.nametag.PlayerNameTag;
import me.skyyiscool.displaytags.api.events.NameTagDespawnEvent;
import me.skyyiscool.displaytags.api.events.NameTagSpawnEvent;
import me.skyyiscool.displaytags.config.NameTagConfiguration;
import me.skyyiscool.displaytags.util.ComponentUtil;
import me.skyyiscool.displaytags.util.DependencyUtil;
import me.skyyiscool.displaytags.util.VanillaNameTagUtil;
import me.skyyiscool.displaytags.wrapper.display.DisplayBillboard;
import me.skyyiscool.displaytags.wrapper.display.TextAlignment;
import me.skyyiscool.displaytags.wrapper.display.TextDisplayWrapper;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerNameTagImpl extends PlayerNameTag {
    private final TextDisplayWrapper display;

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

        NameTagConfiguration config = DisplayTags.get().config().nametag();
        TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.valueOf(config.getTextAlignment().name());
        Display.Billboard billboard = Display.Billboard.valueOf(config.getBillboard().name());

        this.data.setShowToSelf(config.showToSelf());
        this.data.setVisibilityDistance(config.getVisibilityDistance());
        this.data.setLines(config.getLines());
        this.data.setTextAlignment(alignment);
        this.data.setBillboard(billboard);
        this.data.setTextShadow(config.hasTextShadow());
        this.data.setSeeThrough(config.isSeeThrough());
        this.data.setBackground(config.getBackground());
        this.data.setTranslation(config.getOffset());
        this.data.setScale(config.getScale());

        // A tag created while its player is already sneaking (rejoin, reload, world change) has to
        // start out dimmed, because no PlayerToggleSneakEvent is going to arrive for that state.
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
        TextAlignment alignment = TextAlignment.valueOf(this.data.getTextAlignment().name());
        DisplayBillboard billboard = DisplayBillboard.valueOf(this.data.getBillboard().name());

        this.display.setTextAlignment(alignment);
        this.display.setBillboard(billboard);
        this.display.setTextShadow(this.data.hasTextShadow());
        this.display.setSeeThrough(this.data.isSeeThrough());
        this.display.setBackground(this.data.getBackground());
        this.display.setTextOpacity(this.data.getTextOpacity());
        this.display.setTranslation(this.data.getTranslation());
        this.display.setScale(this.data.getScale());
        this.display.setText(this.cachedText);

        // The mount is re-sent on every update, not only once at spawn time. A SetPassengers packet
        // is absolute - it replaces the vehicle's whole passenger list - so resending it is both
        // idempotent and self-healing if a client ever drops or overwrites the list.
        this.display.mountFor(viewerId, this.player.getEntityId());
        this.display.updateFor(viewerId);
    }

    @Override
    public void teleportFor(UUID viewerId) {
        this.display.teleportFor(viewerId);
    }

    @Override
    public void teleportFor(UUID viewerId, Location location) {
        // setRotation(0, 0) keeps the display upright; clone() so the caller's Location - which is
        // usually the live PlayerTeleportEvent destination - is left alone.
        this.display.setLocation(location.clone().setRotation(0, 0));
        this.display.teleportFor(viewerId);
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
    }

    @Override
    public void tick() {
        this.cachedText = getText();
        this.display.setLocation(this.player.getLocation().setRotation(0, 0));

        this.viewers.removeIf(PlayerNameTagImpl::isOffline);

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
        List<String> lines = this.getResolvedLines()
                .stream()
                .map((line) -> {
                    String modified = line
                            .replace("{health}", String.valueOf(new DecimalFormat("#.##").format(player.getHealth())));
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
     * in {@link me.skyyiscool.displaytags.api.nametag.NameTagData} so that API consumers still read
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
