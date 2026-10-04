package eu.nordtal.s2.papercommon.menu;

import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.command.PaperUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

/**
 * The one listener every {@link Menu} goes through: its sounds on open and close, and its clicks.
 *
 * A player's own chest never reaches a menu, because only a menu is its inventory's holder.
 */
public final class Menus implements Listener {

    private final PaperUser.Chime chime;

    public Menus(final PaperUser.Chime chime) {
        this.chime = chime;
    }

    /** Refuses the click unless the menu leaves that slot free, then lets it answer a click on its own slots. */
    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        final int slot = event.getRawSlot();
        if (!menu.leavesFree(slot)) {
            event.setCancelled(true);
        }
        if (!(event.getWhoClicked() instanceof Player player)
                || slot < 0
                || slot >= event.getInventory().getSize()) {
            return;
        }
        final MenuClick click = menu.click(player, slot);
        final Feedback sound = click.sound();
        if (sound != null) {
            chime.play(player, sound);
        }
        // A next menu is an open, not a redraw, and the open closes this one.
        final Menu next = click.open();
        if (next != null) {
            next.open(player);
        } else if (click.close()) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof Menu) {
            chime.play(player, Feedback.SURFACE_OPEN);
        }
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof Menu menu) {
            chime.play(player, Feedback.SURFACE_CLOSE);
            menu.closed(player);
        }
    }

    /** Tells every menu still open that the plugin stops, on the main thread, since Paper disconnects players later. */
    public void stopAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu) {
                menu.stopped(player);
            }
        }
    }
}
