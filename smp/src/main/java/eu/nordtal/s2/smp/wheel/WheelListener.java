package eu.nordtal.s2.smp.wheel;

import eu.nordtal.s2.smp.region.Boxes;

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
 * Right-clicking the wheel in the tavern spins it.
 *
 * <p>The wheel is inside a spawn-protected region, where block interaction is otherwise refused, so
 * this handler runs at the default priority and cancels the event itself - the protection listener
 * sits at {@code LOW} and will have already stopped anything else the click could have done.
 *
 * <p>It also owns the two ends of the spin window that {@link WheelGui} opened: a click inside the
 * wheel, which is refused, and a close, which pays out whatever the animation had not reached yet.
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

    /**
     * Nothing in the wheel is takeable.
     *
     * <p>The strip is prize icons being drawn and redrawn twenty-two times, so a player who managed
     * to pick one up would be holding a real item the server never decided to give them - and the
     * markers are decoration. Every click in this window is refused, including a shift-click from
     * the player's own inventory, which would otherwise push an item into a slot the next frame
     * overwrites.
     */
    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof WheelGui) {
            event.setCancelled(true);
        }
    }

    /**
     * A window closed before the wheel stopped still owes a prize.
     *
     * <p>{@code MONITOR}, because this changes nothing about the event and only reacts to a close
     * that has actually happened - and {@code WheelGui#finish} is a latch, so arriving here after
     * the animation already paid is a no-op rather than a second prize.
     *
     * <p>No strike is played: the player has walked away from the wheel, and a fanfare into a
     * screen nobody is looking at is worse than silence.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof WheelGui gui
                && event.getPlayer() instanceof Player player) {
            gui.finish(player, false);
        }
    }
}
