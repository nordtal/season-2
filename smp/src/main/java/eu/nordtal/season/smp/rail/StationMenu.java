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

/** The menu of a station rail: its name, and for its placer and admins the button that opens the naming dialog. */
final class StationMenu extends Menu {

    /**
     * @param named whether the station has a name
     * @param typed the name the player typed last, for a refusal
     * @param most  the most characters a name has
     */
    record Window(
            boolean named,
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
    private final boolean mayName;

    StationMenu(
            final StationMenus menus, final StationRow station, final boolean mayName, final GameDocument document) {
        this.menus = menus;
        this.station = station;
        this.mayName = mayName;
        final String name = station.name();
        final Inventory inventory = frame(
                StationPanel.rows(mayName),
                StationPanel.title(
                        document.title(),
                        name == null ? document.label("unnamed") : name,
                        mayName ? document.label(name == null ? "name-button" : "rename-button") : null));
        final ItemStack heading = BlankItem.of(document.item("heading"));
        StationPanel.HEADING_SLOTS.forEach(slot -> inventory.setItem(slot, heading));
        if (mayName) {
            final ItemStack button = BlankItem.of(document.item("name"));
            StationPanel.NAME_SLOTS.forEach(slot -> inventory.setItem(slot, button));
        }
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!mayName || !StationPanel.NAME_SLOTS.contains(slot)) {
            return MenuClick.nothing();
        }
        menus.askName(player, station, null, "");
        return MenuClick.closing();
    }
}
