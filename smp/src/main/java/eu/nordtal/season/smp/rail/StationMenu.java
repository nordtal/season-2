package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The menu of a station rail: its name, and the buttons that name it and hide it from the map.
 * Only its placer and admins see the buttons. The spawn is named by the translations, and nobody changes it.
 */
final class StationMenu extends Menu {

    /**
     * @param named whether the station has a name
     * @param hidden whether the station is hidden from the map
     * @param typed the name the player typed last, for a refusal
     * @param most  the most characters a name has
     */
    record Window(
            boolean named,
            boolean hidden,
            @Example("Spawn") String name,
            @Example("Spawn") String typed,
            @Example("32") long most) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "station",
                    "Menus",
                    Window.class,
                    StationPanel.rows(true),
                    Slot.item("heading").at(StationPanel.HEADING_SLOTS),
                    Slot.label("unnamed"),
                    Slot.item("name").at(StationPanel.NAME_SLOTS),
                    Slot.label("name-button"),
                    Slot.label("rename-button"),
                    Slot.label("spawn"),
                    Slot.item("visibility").at(StationPanel.VISIBILITY_SLOTS),
                    Slot.label("hide-button"),
                    Slot.label("show-button"),
                    Slot.label("dialog-title"),
                    Slot.label("dialog-rules"),
                    Slot.label("dialog-field"),
                    Slot.label("dialog-save"),
                    Slot.label("dialog-cancel"),
                    Slot.label("name-empty"),
                    Slot.label("name-too-long"),
                    Slot.label("name-taken"))
            .titled("Station");

    private final StationMenus menus;
    private final StationRow station;
    private final boolean mayChange;

    StationMenu(
            final StationMenus menus,
            final StationRow station,
            final boolean mayChange,
            final boolean hidden,
            final GameDocument document) {
        this.menus = menus;
        this.station = station;
        this.mayChange = mayChange;
        final @Nullable String name = station.spawn() ? document.label("spawn") : station.name();
        final Inventory inventory = frame(
                StationPanel.rows(mayChange),
                StationPanel.title(
                        document.title(),
                        name == null ? document.label("unnamed") : name,
                        mayChange
                                ? new StationPanel.Buttons(
                                        document.label(name == null ? "name-button" : "rename-button"),
                                        document.label(hidden ? "show-button" : "hide-button"))
                                : null));
        final ItemStack heading = BlankItem.of(document.item("heading"));
        StationPanel.HEADING_SLOTS.forEach(slot -> inventory.setItem(slot, heading));
        if (mayChange) {
            final ItemStack button = BlankItem.of(document.item("name"));
            StationPanel.NAME_SLOTS.forEach(slot -> inventory.setItem(slot, button));
            final ItemStack visibility = BlankItem.of(document.item("visibility"));
            StationPanel.VISIBILITY_SLOTS.forEach(slot -> inventory.setItem(slot, visibility));
        }
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (mayChange && StationPanel.NAME_SLOTS.contains(slot)) {
            menus.askName(player, station, null, "");
            return MenuClick.closing();
        }
        if (mayChange && StationPanel.VISIBILITY_SLOTS.contains(slot)) {
            menus.toggleHidden(player, station);
            return MenuClick.closing();
        }
        return MenuClick.nothing();
    }
}
