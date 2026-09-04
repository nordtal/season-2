package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;

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

    private final Worlds worlds;
    private final SeasonState season;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public PortalGate(final Worlds worlds, final SeasonState season, final Messages messages,
                      final PlayerLocales locales, final SmpSounds sounds) {
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
        if (event.getEntity() instanceof Player player) {
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()), "smp.portal.nether-locked"));
            sounds.play(player, Feedback.REFUSED);
        }
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

    @EventHandler(ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        final WorldRole from = worlds.roleOf(event.getFrom().getWorld()).orElse(null);
        if (from == null) {
            return;
        }

        if (from == WorldRole.FARM) {
            // One way out, to one place, and no linked nether of its own is ever created.
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
