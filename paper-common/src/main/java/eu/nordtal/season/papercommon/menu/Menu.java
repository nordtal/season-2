package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
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

    /** Set once {@link #replaceWith} put another menu in this one's place. */
    private boolean replaced;

    /** Set until the open of a menu that {@link #replaceWith} showed. */
    private boolean quiet;

    /** The drawn title {@link #retitle} gave this window, which every later open shows. */
    private @Nullable Component title;

    /** Set while {@link #retitle} reopens this window, so its close is no close. */
    private boolean reopening;

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

    /** Returns whether {@code player} has this window open. */
    public final boolean showsTo(final Player player) {
        return inventory != null && player.getOpenInventory().getTopInventory().equals(inventory);
    }

    /** Shows {@code next} in this window's place, without the close and open sounds and without {@link #closed}. */
    public final void replaceWith(final Player player, final Menu next) {
        replaced = true;
        next.quiet = true;
        next.open(player);
        if (!player.getOpenInventory().getTopInventory().equals(next.getInventory())) {
            replaced = false;
            next.quiet = false;
        }
    }

    /**
     * Shows this window to {@code player} again under {@code next}, without sounds and without {@link #closed}.
     * The client then draws the new title; the items stay, and a stack on the cursor goes back to the inventory.
     */
    protected final void retitle(final Player player, final Component next) {
        title = next;
        if (!showsTo(player)) {
            return;
        }
        reopening = true;
        quiet = true;
        try {
            player.openInventory(getInventory());
        } finally {
            reopening = false;
        }
        if (!showsTo(player)) {
            quiet = false;
        }
    }

    /** Returns the title {@link #retitle} gave, or null while the window keeps the one it was framed with. */
    final @Nullable Component retitled() {
        return title;
    }

    /** Returns whether this window's close is part of a replacement or a reopen, which plays nothing. */
    final boolean replaced() {
        return replaced || reopening;
    }

    /** Returns whether this open is quiet, and clears it. */
    final boolean openedQuietly() {
        final boolean was = quiet;
        quiet = false;
        return was;
    }

    /** Returns whether a click on a raw slot is free. Every slot is refused unless a menu says so. */
    protected boolean leavesFree(final int rawSlot) {
        return false;
    }

    /** Returns whether a click of {@code type} on a raw slot is free; a menu that tells types apart overrides it. */
    protected boolean leavesFree(final int rawSlot, final ClickType type) {
        return leavesFree(rawSlot);
    }

    protected MenuClick click(final Player player, final int slot) {
        return MenuClick.nothing();
    }

    /** Runs for a click of {@code type} on a slot of this window; a menu that tells click types apart overrides it. */
    protected MenuClick click(final Player player, final int slot, final ClickType type) {
        return click(player, slot);
    }

    /** Runs for a click of {@code type} on a raw slot of the player's own inventory below this window. */
    protected MenuClick clickBelow(final Player player, final int rawSlot, final ClickType type) {
        return MenuClick.nothing();
    }

    /** Runs when {@code player} closed this window, after its close sound. */
    protected void closed(final Player player) {}

    protected void stopped(final Player player) {}
}
