package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

/**
 * {@code SURFACE_OPEN} and {@code SURFACE_CLOSE} for every menu this plugin opens.
 *
 * Only a {@link Surface} chimes, never a player's own chest, and it cancels nothing.
 */
public final class SurfaceListener implements Listener {

    private final SmpSounds sounds;
    private final Predicate<Inventory> alsoASurface;

    /**
     * Creates the listener.
     *
     * @param alsoASurface recognises the surfaces that cannot carry {@link Surface}, such as the grave inventory
     */
    public SurfaceListener(final SmpSounds sounds, final Predicate<Inventory> alsoASurface) {
        this.sounds = sounds;
        this.alsoASurface = alsoASurface;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isSurface(event.getInventory())) {
            sounds.play(player, Feedback.SURFACE_OPEN);
        }
    }

    /** Plays the close sound before any other listener handles the close. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && isSurface(event.getInventory())) {
            sounds.play(player, Feedback.SURFACE_CLOSE);
        }
    }

    private boolean isSurface(final Inventory inventory) {
        return inventory.getHolder() instanceof Surface || alsoASurface.test(inventory);
    }
}
