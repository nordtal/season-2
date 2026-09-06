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
 * The three portal rules from docs/smp.md#travel, each of them deliberate.
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
     * <p>Cancelling {@code PortalCreateEvent} stops the portal and nothing else: the flint and steel
     * has already placed a fire block by the time this event is raised, and vanilla leaves it there
     * because to vanilla the frame was simply invalid. Here the frame is valid and the <em>server</em>
     * said no - so leaving the fire burning charges the player for an action that was refused. Seen
     * on the local SMP on 2026-09-06 by lighting a frame from the inside: the message arrived, the
     * portal did not, and the player stood in the flames losing hearts (finding 130). A death there
     * would also have cost five aura.
     *
     * <p>Next tick, because the block is placed by the same call stack this event is raised from and
     * setting it to air here is undone. The blocks are the ones that would have become portal, which
     * is exactly the column the fire is in whichever face was clicked.
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
     * in {@code minecraft:farm} has no destination the server will even look for - it never
     * attempts a transfer and never raises {@code PlayerPortalEvent}. Measured on the local SMP,
     * 2026-09-06: a frame in the farm world lights (correctly - the milestone gate does not apply
     * there), the portal block forms, the screen goes purple, and the player stands in it for as
     * long as they like. The same frame in Nordtal moved them to the Nether and back in seconds.
     * docs/smp.md says every portal in the farm world leads to the Nordtal spawn; until this method
     * existed, none of them led anywhere (finding 131).
     *
     * <p>{@code EntityPortalEnterEvent} is the hook that does arrive, because it is raised from the
     * block the entity is standing in rather than from the travel logic. It fires every tick, hence
     * {@link #leaving}: the countdown is armed once and the delayed task is what re-checks. Four
     * seconds is vanilla's own dwell time, so the portal behaves the way a player already expects.
     *
     * <p>The arrival goes through {@link LandingSite#safeAt} for the same reason the duel's does:
     * a world's spawn location is a coordinate, not a promise.
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
            // They stepped out during the four seconds, which is the whole point of the four
            // seconds.
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
            // Belt and braces, and on Paper 26.2 it is only that: this event never arrives for a
            // portal in the farm world, because the farm world is a custom dimension and vanilla
            // links only overworld to nether. See takeTheFarmWorldExit, which is what actually
            // carries a player out (finding 131). Kept because a future Paper that did fire it
            // must not send anybody to a nether that does not exist.
            final World nordtal = worlds.world(WorldRole.NORDTAL).orElse(null);
            if (nordtal == null) {
                event.setCancelled(true);
                return;
            }
            event.setCanCreatePortal(false);
            event.setTo(nordtal.getSpawnLocation());
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
