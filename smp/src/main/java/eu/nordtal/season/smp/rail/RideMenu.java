package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The menu of a rider in a minecart: the stations the cart reaches, a page at a time, favourites first. */
final class RideMenu extends Menu {

    /** @param page the shown page, counted from 1 */
    record Window(@Example("1") long page, @Example("3") long pages) {}

    record Stop(
            @Example("Harbour") String name, @Example("240") long distance, boolean favourite) {}

    /** The station rows the declaration lays out as its example: a full page above the page buttons. */
    private static final int EXAMPLE_ROWS = RidePanel.MOST_PER_PAGE;

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "ride",
                    "Menus",
                    Window.class,
                    EXAMPLE_ROWS + 1,
                    Slot.item("station", Stop.class)
                            .at(IntStream.range(0, EXAMPLE_ROWS)
                                    .boxed()
                                    .flatMap(row -> RidePanel.stationSlots(row).stream())
                                    .toList()),
                    Slot.label("distance", Stop.class),
                    Slot.item("favourite", Stop.class)
                            .at(IntStream.range(0, EXAMPLE_ROWS)
                                    .mapToObj(RidePanel::starSlot)
                                    .toList()),
                    Slot.label("none"),
                    Slot.item("previous-page").at(List.of(RidePanel.previousSlot(EXAMPLE_ROWS))),
                    Slot.label("page"),
                    Slot.item("next-page").at(List.of(RidePanel.nextSlot(EXAMPLE_ROWS))))
            .titled("Where to");

    private final StationMenus menus;
    private final List<RideList.Stop> stops;
    private final int perPage;
    private final int page;
    private final int stationRows;

    RideMenu(
            final StationMenus menus,
            final List<RideList.Stop> stops,
            final int perPage,
            final int page,
            final GameDocument document) {
        this.menus = menus;
        this.stops = stops;
        this.perPage = perPage;
        this.page = page;
        this.stationRows = RidePanel.stationRows(stops.size(), perPage);
        final int pages = RidePanel.pages(stops.size(), perPage);
        final List<RideList.Stop> shown = shown();
        final List<RidePanel.Row> rows = new ArrayList<>();
        for (final RideList.Stop stop : shown) {
            rows.add(new RidePanel.Row(stop.name(), document.label("distance", stop(stop)), stop.favourite()));
        }
        final Inventory inventory = frame(
                RidePanel.rows(stops.size(), perPage),
                RidePanel.title(
                        document.title(),
                        rows,
                        document.label("none"),
                        stationRows,
                        document.label("page"),
                        pages > 1,
                        page > 0,
                        page < pages - 1));
        for (int row = 0; row < shown.size(); row++) {
            final Stop values = stop(shown.get(row));
            final ItemStack station = BlankItem.of(document.item("station", values));
            RidePanel.stationSlots(row).forEach(slot -> inventory.setItem(slot, station));
            inventory.setItem(RidePanel.starSlot(row), BlankItem.of(document.item("favourite", values)));
        }
        if (page > 0) {
            inventory.setItem(RidePanel.previousSlot(stationRows), BlankItem.of(document.item("previous-page")));
        }
        if (page < pages - 1) {
            inventory.setItem(RidePanel.nextSlot(stationRows), BlankItem.of(document.item("next-page")));
        }
    }

    private static Stop stop(final RideList.Stop stop) {
        return new Stop(stop.name(), stop.distance(), stop.favourite());
    }

    private List<RideList.Stop> shown() {
        final int from = Math.min(page * perPage, stops.size());
        return stops.subList(from, Math.min(from + perPage, stops.size()));
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        final int pages = RidePanel.pages(stops.size(), perPage);
        if (pages > 1 && SlotGeometry.row(slot) == stationRows) {
            if (slot == RidePanel.previousSlot(stationRows) || slot == RidePanel.nextSlot(stationRows)) {
                final int wanted = page + (slot == RidePanel.previousSlot(stationRows) ? -1 : 1);
                return wanted < 0 || wanted >= pages
                        ? MenuClick.refused()
                        : menus.ride(player, wanted)
                                .<MenuClick>map(MenuClick::opening)
                                .orElse(MenuClick.closing());
            }
            return MenuClick.nothing();
        }
        final int row = SlotGeometry.row(slot);
        final List<RideList.Stop> shown = shown();
        if (row < 0 || row >= shown.size()) {
            return MenuClick.nothing();
        }
        final RideList.Stop stop = shown.get(row);
        if (slot == RidePanel.starSlot(row)) {
            menus.toggleFavourite(player, stop);
            return menus.ride(player, page).<MenuClick>map(MenuClick::opening).orElse(MenuClick.closing());
        }
        if (RidePanel.stationSlots(row).contains(slot)) {
            // A station no way leads to keeps the menu open, so the rider can pick another.
            return menus.pick(player, stop) ? MenuClick.closing() : MenuClick.refused();
        }
        return MenuClick.nothing();
    }

    @Override
    protected void closed(final Player player) {
        menus.closedRide(player);
    }
}
