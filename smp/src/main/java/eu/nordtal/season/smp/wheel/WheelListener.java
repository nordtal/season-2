package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.region.Boxes;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** WheelListener runs at default priority and cancels the event. Spawn protection at {@code LOW} refuses it. */
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
        final Location at = Objects.requireNonNull(event.getClickedBlock().getLocation());
        if (!regions.contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return;
        }
        event.setCancelled(true);
        wheel.spin(event.getPlayer());
    }
}
