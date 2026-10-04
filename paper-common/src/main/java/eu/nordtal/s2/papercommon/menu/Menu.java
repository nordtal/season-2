package eu.nordtal.s2.papercommon.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * A window a plugin opens: it holds its own chest inventory, and {@link Menus} hands it every click and close.
 *
 * A title cannot be redrawn, so a page turn or a next screen is a new menu, opened through {@link MenuClick#opening}.
 */
public abstract class Menu implements InventoryHolder {

    private @Nullable Inventory inventory;

    /**
     * Creates this menu's window, once, from the subclass's constructor.
     *
     * @param title the panel {@link MenuTitle} drew for this many rows, with the text on it
     */
    protected final Inventory frame(final int rows, final Component title) {
        if (inventory != null) {
            throw new IllegalStateException(getClass().getSimpleName() + " framed its window twice");
        }
        final Inventory window = Bukkit.createInventory(this, rows * SlotGeometry.COLUMNS, title);
        inventory = window;
        return window;
    }

    @Override
    public final Inventory getInventory() {
        final Inventory window = inventory;
        if (window == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " never framed its window");
        }
        return window;
    }

    /** Shows this menu to {@code player}, on the main thread. */
    public final void open(final Player player) {
        player.openInventory(getInventory());
    }

    /** Returns whether a click on a raw slot stays the player's; every slot is refused unless a menu says so. */
    protected boolean leavesFree(final int rawSlot) {
        return false;
    }

    /** Answers a click on one of this window's own slots, after the refusal {@link #leavesFree} decided. */
    protected MenuClick click(final Player player, final int slot) {
        return MenuClick.nothing();
    }

    /** Runs when {@code player} closed this window, after its close sound. */
    protected void closed(final Player player) {}

    /** Runs at the plugin's stop for each player still looking at this window, before the pool closes. */
    protected void stopped(final Player player) {}
}
