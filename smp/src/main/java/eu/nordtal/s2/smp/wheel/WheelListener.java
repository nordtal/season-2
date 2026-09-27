package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.smp.region.Boxes;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Right-clicking the wheel in the tavern spins it; closing the spin window pays out.
 *
 * Runs at default priority and cancels the event itself, since spawn protection at {@code LOW} refused it.
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
        final Location at = Objects.requireNonNull(event.getClickedBlock().getLocation());
        if (!regions.contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return;
        }
        event.setCancelled(true);
        wheel.spin(event.getPlayer());
    }

    /**
     * Refuses every click in the wheel, then asks the window whether it was "spin again".
     *
     * Unconditional, because the strip shows real items a player must never pick up.
     */
    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof WheelGui gui) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                gui.click(player, event.getRawSlot());
            }
        }
    }

    /**
     * A window closed before the wheel stopped still pays out, without the strike.
     *
     * At {@code MONITOR}, since it only reacts; {@code WheelGui#finish} is a latch, so a second call is a no-op.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof WheelGui gui && event.getPlayer() instanceof Player player) {
            gui.finish(player, false);
        }
    }
}
