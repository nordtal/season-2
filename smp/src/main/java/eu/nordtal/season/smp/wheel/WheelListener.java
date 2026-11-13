package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelSpec;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Opens the wheel at its blocks, and closes it when something else takes the screen.
 * The open runs at default priority and cancels the click. Spawn protection at {@code LOW} refuses it.
 */
public final class WheelListener implements Listener {

    private final WheelSpec config;
    private final Wheel wheel;

    public WheelListener(final WheelSpec config, final Wheel wheel) {
        this.config = config;
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
        if (!WheelBlocks.holds(
                config.wheelBlocks(), at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return;
        }
        event.setCancelled(true);
        wheel.open(event.getPlayer());
    }

    /** A death screen closes the wheel, so a turning wheel pays at once. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        wheel.close(event.getPlayer());
    }

    /** A change of world closes the wheel, so a turning wheel pays at once. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final PlayerChangedWorldEvent event) {
        wheel.close(event.getPlayer());
    }

    /** An inventory the server opens replaces the wheel, so a turning wheel pays at once. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof final Player player) {
            wheel.close(player);
        }
    }
}
