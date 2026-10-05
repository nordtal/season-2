package eu.nordtal.season.smp.travel;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Gates Nether portals on the milestone, sends every farm world portal to spawn and keeps End portals dark.
 *
 * The worlds are named {@code nordtal} and {@code nordtal_nether} so Bukkit pairs them for the 1:8 mapping.
 */
public final class PortalGate implements Listener {

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

    /** A Nether portal frame only lights once the milestone has been finished. */
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
                    identities.languageOf(player.getUniqueId()),
                    MESSAGES.smp().portal().netherLocked()));
            sounds.play(player, Feedback.REFUSED);
        }
    }

    /** Clears the fire a refused ignition left standing, next tick, since this tick's call stack would undo it. */
    private void putOutTheFire(final PortalCreateEvent event) {
        final World world = event.getWorld();
        final java.util.List<org.bukkit.block.BlockState> blocks = java.util.List.copyOf(event.getBlocks());
        PaperScheduler.of(plugin)
                .onMain(() -> blocks.stream()
                        .map(state -> world.getBlockAt(state.getX(), state.getY(), state.getZ()))
                        .filter(block -> block.getType() == Material.FIRE)
                        .forEach(block -> block.setType(Material.AIR, false)));
    }

    /** An End portal frame never takes an eye. */
    @EventHandler(ignoreCancelled = true)
    public void onEyeOfEnder(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        if (event.getClickedBlock().getType() != Material.END_PORTAL_FRAME) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer()
                .sendMessage(renderer.format(
                        identities.languageOf(event.getPlayer().getUniqueId()),
                        MESSAGES.smp().portal().endInactive()));
        sounds.play(event.getPlayer(), Feedback.REFUSED);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        final WorldRole from = worlds.roleOf(event.getFrom().getWorld()).orElse(null);
        if (from == null) {
            return;
        }

        if (!season.isUnlocked(Unlock.NETHER) && from.hasVanillaPortalLinking()) {
            // Belt and braces: a pre-existing portal must not become a way past the milestone.
            event.setCancelled(true);
            event.getPlayer()
                    .sendMessage(renderer.format(
                            identities.languageOf(event.getPlayer().getUniqueId()),
                            MESSAGES.smp().portal().netherLocked()));
            sounds.play(event.getPlayer(), Feedback.REFUSED);
        }
    }
}
