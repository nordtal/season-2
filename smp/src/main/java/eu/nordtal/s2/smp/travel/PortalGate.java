package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.farm.LandingSite;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * The three portal rules, each of them deliberate.
 *
 * <ul>
 *   <li><b>Nether portals are gated by the milestone, not disabled.</b> Until the Nether milestone
 *       is unlocked a portal built in Nordtal does not ignite - without that, one player with
 *       obsidian and a flint and steel walks straight past the milestone that is supposed to open
 *       the Nether. Afterwards they behave exactly like vanilla, in both directions, with the usual
 *       1:8 mapping. Nether highways are therefore possible and that is accepted: a highway is
 *       infrastructure the community digs, not a command it is handed.</li>
 *   <li><b>Every portal in the farm world leads to the Nordtal spawn</b>, wherever it stands. The
 *       farm world is thrown away every day and must not become a permanent address, so it gets no
 *       portal network of its own.</li>
 *   <li><b>A stronghold's End portal never activates.</b> The End is unlocked by a milestone and
 *       entered by balloon, so that the community goes in together - and the way back is the
 *       vanilla exit portal, which does not work until the dragon is dead.</li>
 * </ul>
 *
 * <p>The vanilla 1:8 linking needs no code: the worlds are named {@code nordtal} and
 * {@code nordtal_nether} precisely so Bukkit's own convention pairs them.
 */
public final class PortalGate implements Listener {

    /** How long a player stands in a portal before it takes them - vanilla's own four seconds. */
    private static final long PORTAL_TICKS = 80L;

    /** Players the farm world's exit is already counting down for, so it counts once. */
    private final java.util.Set<java.util.UUID> leaving = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Plugin plugin;
    private final Worlds worlds;
    private final SeasonState season;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public PortalGate(final Plugin plugin, final Worlds worlds, final SeasonState season,
                      final Messages messages, final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.season = season;
        this.messages = messages;
        this.locales = locales;
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
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()), "smp.portal.nether-locked"));
            sounds.play(player, Feedback.REFUSED);
        }
    }

    /**
     * Clears the fire the refused ignition left standing.
     *
     * <p>Cancelling {@code PortalCreateEvent} stops the portal and nothing else: the flint and
     * steel has already placed a fire block, so a refused ignition otherwise burns the player who
     * just read the refusal.
     *
     * <p>Next tick, because the block is placed by the same call stack this event is raised from and
     * setting it to air here is undone. The blocks are the ones that would have become portal, which
     * is the column the fire is in whichever face was clicked.
     */
    private void putOutTheFire(final PortalCreateEvent event) {
        final World world = event.getWorld();
        final java.util.List<org.bukkit.block.BlockState> blocks =
                java.util.List.copyOf(event.getBlocks());
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> blocks.stream()
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
        event.getPlayer().sendMessage(MessageRenderer.of(messages).get(locales.of(event.getPlayer().getUniqueId()), "smp.portal.end-inactive"));
        sounds.play(event.getPlayer(), Feedback.REFUSED);
    }

    /**
     * Carries a player out of the farm world, because nothing else will.
     *
     * <p><b>The farm world is a custom dimension.</b> Vanilla's portal travel links
     * {@code minecraft:overworld} to {@code minecraft:the_nether} and nothing else, so a lit portal
     * there never attempts a transfer and never raises {@code PlayerPortalEvent} - the player just
     * stands in a purple screen.
     *
     * <p>{@code EntityPortalEnterEvent} does arrive, because it is raised from the block rather than
     * from the travel logic. It fires every tick, hence {@link #leaving}: the countdown is armed
     * once and the delayed task re-checks. Four seconds is vanilla's own dwell time.
     *
     * <p>The arrival goes through {@link LandingSite#safeAt}: a world's spawn location is a
     * coordinate, not a promise that anybody survives it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEnterPortal(final EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getPortalType() != org.bukkit.PortalType.NETHER) {
            return;
        }
        if (worlds.roleOf(player.getWorld()).orElse(null) != WorldRole.FARM) {
            return;
        }
        if (!leaving.add(player.getUniqueId())) {
            return;
        }
        final java.util.UUID id = player.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            leaving.remove(id);
            takeTheFarmWorldExit(org.bukkit.Bukkit.getPlayer(id));
        }, PORTAL_TICKS);
    }

    private void takeTheFarmWorldExit(final Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (worlds.roleOf(player.getWorld()).orElse(null) != WorldRole.FARM) {
            return;
        }
        if (player.getLocation().getBlock().getType() != Material.NETHER_PORTAL) {
            // They stepped out during the four seconds, which is what the four seconds are for.
            return;
        }
        final World nordtal = worlds.world(WorldRole.NORDTAL).orElse(null);
        if (nordtal == null) {
            return;
        }
        player.teleport(LandingSite.safeAt(nordtal, nordtal.getSpawnLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        final WorldRole from = worlds.roleOf(event.getFrom().getWorld()).orElse(null);
        if (from == null) {
            return;
        }

        if (from == WorldRole.FARM) {
            // Belt and braces: this event never arrives for a portal in the farm world, because
            // the farm world is a custom dimension - takeTheFarmWorldExit is what carries a player
            // out. Kept so a future Paper that did fire it cannot send anybody to a nether that
            // does not exist.
            final World nordtal = worlds.world(WorldRole.NORDTAL).orElse(null);
            if (nordtal == null) {
                event.setCancelled(true);
                return;
            }
            event.setCanCreatePortal(false);
            event.setTo(LandingSite.safeAt(nordtal, nordtal.getSpawnLocation()));
            return;
        }

        if (!season.isUnlocked(Unlock.NETHER) && from.hasVanillaPortalLinking()) {
            // Belt and braces: the frame should never have lit, but a portal that predates the
            // plugin, or an admin's, must not become a way past the milestone either.
            event.setCancelled(true);
            event.getPlayer().sendMessage(MessageRenderer.of(messages).get(locales.of(event.getPlayer().getUniqueId()), "smp.portal.nether-locked"));
            sounds.play(event.getPlayer(), Feedback.REFUSED);
        }
    }
}
