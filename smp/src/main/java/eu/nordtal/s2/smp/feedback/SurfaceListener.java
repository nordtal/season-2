package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.common.feedback.Feedback;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import java.util.function.Predicate;

/**
 * {@code SURFACE_OPEN} and {@code SURFACE_CLOSE} for every menu this plugin opens.
 *
 * <p>One listener rather than a call beside each {@code openInventory}: "every GUI makes this sound"
 * is then a property of {@link Surface} instead of a habit ten call sites have to keep.
 *
 * <p>It runs at {@code MONITOR} and cancels nothing - it only reacts to a surface that has actually
 * opened or closed.
 *
 * <p><b>A player's own chest is not a surface.</b> The check is deliberately our marker interface
 * and not "any inventory", because on an SMP whose whole concept is bases and chests, chiming at
 * every barrel would be the single most irritating thing on the server.
 */
public final class SurfaceListener implements Listener {

    private final SmpSounds sounds;
    private final Predicate<Inventory> alsoASurface;

    /**
     * @param alsoASurface for the surfaces that cannot carry {@link Surface} - the grave inventory,
     *                     which has a null holder and is recognised by identity in {@code Graves}
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

    /**
     * {@code LOWEST}, and that is load-bearing rather than tidy.
     *
     * <p>{@code GraveListener} runs at the default priority and hands the close to
     * {@code Graves#onClosed}, which forgets that inventory. Asking afterwards whether it was a
     * grave answers no, so this observer has to run first - the one case where the close sound
     * depends on handler order.
     */
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
