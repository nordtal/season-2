package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.smp.region.Boxes;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Right-clicking the wheel in the tavern spins it.
 *
 * <p>The wheel is inside a spawn-protected region, where block interaction is otherwise refused, so
 * this handler runs at the default priority and cancels the event itself - the protection listener
 * sits at {@code LOW} and will have already stopped anything else the click could have done.
 */
public final class WheelListener implements Listener {

    private final Boxes regions;
    private final Wheel wheel;

    public WheelListener(final Boxes regions, final Wheel wheel) {
        this.regions = regions;
        this.wheel = wheel;
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        final Location at = event.getClickedBlock().getLocation();
        if (!regions.contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return;
        }
        event.setCancelled(true);
        wheel.spin(event.getPlayer());
    }
}
