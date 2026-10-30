package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.command.PaperUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

/** The listener for every {@link Menu}: sounds on open and close, and clicks. A replacement plays neither sound. */
public final class Menus implements Listener {

    private final PaperUser.Chime chime;

    public Menus(final PaperUser.Chime chime) {
        this.chime = chime;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        final int slot = event.getRawSlot();
        if (!menu.leavesFree(slot, event.getClick())) {
            event.setCancelled(true);
        }
        if (!(event.getWhoClicked() instanceof Player player)
                || slot < 0
                || slot >= event.getInventory().getSize()) {
            return;
        }
        final MenuClick click = menu.click(player, slot, event.getClick());
        final Feedback sound = click.sound();
        if (sound != null) {
            chime.play(player, sound);
        }
        final Menu next = click.open();
        if (next != null) {
            next.open(player);
        } else if (click.close()) {
            player.closeInventory();
        }
    }

    /** Refuses a drag that would spread stacks over a slot of the window that is not free. */
    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && event.getRawSlots().stream()
                        .anyMatch(slot -> slot < event.getInventory().getSize() && !menu.leavesFree(slot))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && event.getInventory().getHolder() instanceof Menu menu
                && !menu.openedQuietly()) {
            chime.play(player, Feedback.SURFACE_OPEN);
        }
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
                && event.getInventory().getHolder() instanceof Menu menu
                && !menu.replaced()) {
            chime.play(player, Feedback.SURFACE_CLOSE);
            menu.closed(player);
        }
    }

    /** Tells every open menu on the main thread that the plugin stops. */
    public void stopAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu) {
                menu.stopped(player);
            }
        }
    }
}
