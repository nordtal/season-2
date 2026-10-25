package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * A window with a chest inventory of its own.
 * {@link Menus} passes it every click and close.
 * A page turn is a new menu.
 */
public abstract class Menu implements InventoryHolder {

    private @Nullable Inventory inventory;

    /** Creates this menu's window once, from the constructor of the subclass. */
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

    public final void open(final Player player) {
        player.openInventory(getInventory());
    }

    /** Returns whether a click on a raw slot is free. Every slot is refused unless a menu says so. */
    protected boolean leavesFree(final int rawSlot) {
        return false;
    }

    protected MenuClick click(final Player player, final int slot) {
        return MenuClick.nothing();
    }

    /** Runs when {@code player} closed this window, after its close sound. */
    protected void closed(final Player player) {}

    protected void stopped(final Player player) {}
}
