package eu.nordtal.season.papercommon.menu;

import eu.nordtal.season.packrendering.ui.ChestTitle;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Nameable;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.jspecify.annotations.Nullable;

/**
 * Draws the chest panel into the title of every chest window that is not a {@link Menu}.
 * The pack empties vanilla's chest art, so a placed chest, barrel or ender chest shows its panel only this way.
 */
public final class ContainerTitles implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        final InventoryView view = event.getView();
        final int rows = rows(view.getMenuType());
        final InventoryHolder holder = event.getInventory().getHolder(false);
        if (rows == 0 || holder instanceof Menu) {
            return;
        }
        event.titleOverride(title(rows, view.title(), halves(holder)));
    }

    /** Returns the drawn title of a chest window of {@code rows} rows that vanilla titles {@code shown}. */
    static Component title(final int rows, final Component shown, final List<@Nullable Component> halves) {
        return ChestTitle.container(rows, name(shown, halves));
    }

    /**
     * Returns the name a container shows: vanilla's own, or a half's custom name over a translated default.
     * A large chest's halves are named apart; vanilla already picks one, and this keeps any that it missed.
     */
    static Component name(final Component shown, final List<@Nullable Component> halves) {
        if (!(shown instanceof TranslatableComponent)) {
            return shown;
        }
        for (final Component half : halves) {
            if (half != null) {
                return half;
            }
        }
        return shown;
    }

    private static List<@Nullable Component> halves(final @Nullable InventoryHolder holder) {
        final List<@Nullable Component> names = new ArrayList<>();
        if (holder instanceof final DoubleChest chest) {
            names.add(customName(chest.getLeftSide(false)));
            names.add(customName(chest.getRightSide(false)));
        }
        return names;
    }

    private static @Nullable Component customName(final @Nullable InventoryHolder half) {
        return half instanceof final Nameable named ? named.customName() : null;
    }

    // Compared one by one, since the menu types are registry entries that exist only on a running server.
    private static int rows(final @Nullable MenuType type) {
        final List<MenuType> chests = List.of(
                MenuType.GENERIC_9X1,
                MenuType.GENERIC_9X2,
                MenuType.GENERIC_9X3,
                MenuType.GENERIC_9X4,
                MenuType.GENERIC_9X5,
                MenuType.GENERIC_9X6);
        return type == null ? 0 : chests.indexOf(type) + 1;
    }
}
