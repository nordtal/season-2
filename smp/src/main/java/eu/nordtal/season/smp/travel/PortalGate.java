package eu.nordtal.season.smp.travel;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.milestone.Unlock;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.world.WorldRole;
import eu.nordtal.season.smp.world.Worlds;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.Plugin;

/**
 * Refuses Nether portals until {@code Unlock.NETHER} is unlocked, and End portals until {@code Unlock.END} is.
 */
public final class PortalGate implements Listener {

    /** {@code world} is the one that is still closed. */
    record Locked(WorldRole world) {}

    static final DocumentSpec<Locked> LOCKED =
            DocumentSpec.game("smp", "portal-locked", "Portals", DocumentSpec.Shape.CHAT, Locked.class);

    private static final int REFUSAL_COOLDOWN_TICKS = 100;

    private final Plugin plugin;
    private final Worlds worlds;
    private final SeasonState season;
    private final MessageRenderer renderer;
    private final Identities identities;
    private final SmpSounds sounds;

    public PortalGate(
            final Plugin plugin,
            final Worlds worlds,
            final SeasonState season,
            final MessageRenderer renderer,
            final Identities identities,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.season = season;
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPortalCreate(final PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE) {
            return;
        }
        if (season.isUnlocked(Unlock.NETHER)) {
            return;
        }
        final World world = event.getWorld();
        if (worlds.roleOf(world).filter(WorldRole::hasVanillaPortalLinking).isEmpty()) {
            return;
        }

        event.setCancelled(true);
        putOutTheFire(event);
        if (event.getEntity() instanceof Player player) {
            player.sendMessage(renderer.format(
                    identities.languageOf(player.getUniqueId()), LOCKED.with(new Locked(WorldRole.NETHER))));
            sounds.play(player, Feedback.REFUSED);
        }
    }

    /** Removes the fire of a refused ignition on the next tick. */
    private void putOutTheFire(final PortalCreateEvent event) {
        final World world = event.getWorld();
        final java.util.List<org.bukkit.block.BlockState> blocks = java.util.List.copyOf(event.getBlocks());
        PaperScheduler.of(plugin)
                .onMain(() -> blocks.stream()
                        .map(state -> world.getBlockAt(state.getX(), state.getY(), state.getZ()))
                        .filter(block -> block.getType() == Material.FIRE)
                        .forEach(block -> block.setType(Material.AIR, false)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        final WorldRole from = worlds.roleOf(event.getFrom().getWorld()).orElse(null);
        if (from == null) {
            return;
        }

        final boolean endPortal = event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL;
        if (endPortal && from != WorldRole.END && !season.isUnlocked(Unlock.END)) {
            refuse(event, WorldRole.END);
        } else if (!endPortal && !season.isUnlocked(Unlock.NETHER) && from.hasVanillaPortalLinking()) {
            refuse(event, WorldRole.NETHER);
        }
    }

    private void refuse(final PlayerPortalEvent event, final WorldRole closed) {
        event.setCancelled(true);
        final Player player = event.getPlayer();
        // A player standing in a refused portal tries it again every tick; the cooldown spaces the refusals.
        player.setPortalCooldown(REFUSAL_COOLDOWN_TICKS);
        player.sendMessage(
                renderer.format(identities.languageOf(player.getUniqueId()), LOCKED.with(new Locked(closed))));
        sounds.play(player, Feedback.REFUSED);
    }
}
