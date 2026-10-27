package eu.nordtal.season.smp.grave;

import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/** The window of a grave. Any viewer may take the content items. {@link Graves} handles the close of the window. */
final class GraveWindow extends Menu {

    record Window(@Example("30") long experience) {}

    /** @param player the dead player, {@code null} when the server does not know the name */
    record Owner(
            boolean known,
            @Nullable PlayerContext player,
            @Example("2026-10-23T18:40:00Z") Instant at,
            @Example("-120") int x,
            @Example("64") int y,
            @Example("355") int z) {}

    /** The content rows of the grave the declaration lays out as its example. */
    private static final int EXAMPLE_ROWS = 2;

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "grave",
                    "Menus",
                    Window.class,
                    GravePanel.rows(EXAMPLE_ROWS),
                    Slot.item("owner", Owner.class).at(List.of(GravePanel.headSlot(EXAMPLE_ROWS))),
                    Slot.item("experience").at(GravePanel.experienceSlots(EXAMPLE_ROWS)),
                    Slot.label("experience-line"),
                    Slot.item("take-all").at(GravePanel.takeAllSlots(EXAMPLE_ROWS)),
                    Slot.label("take-all-button"))
            .titled("Grave");

    private final Graves graves;
    private final UUID graveId;
    private final int contentRows;
    private final Inventory inventory;

    /** @param experience the text beside the head, blank for none */
    GraveWindow(
            final Graves graves,
            final UUID graveId,
            final int contentRows,
            final Component title,
            final String experience,
            final String takeAll) {
        this.graves = graves;
        this.graveId = graveId;
        this.contentRows = contentRows;
        this.inventory = frame(GravePanel.rows(contentRows), GravePanel.title(title, contentRows, experience, takeAll));
    }

    UUID graveId() {
        return graveId;
    }

    int contentRows() {
        return contentRows;
    }

    /** Leaves the content slots and the player's own inventory free to move items. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return rawSlot < 0 || rawSlot >= inventory.getSize() || GravePanel.isContent(rawSlot, contentRows);
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!GravePanel.takeAllSlots(contentRows).contains(slot)) {
            return MenuClick.nothing();
        }
        graves.takeAll(player, this);
        // Closing the window lets Graves settle the grave, also if it is empty.
        return MenuClick.closing();
    }

    @Override
    protected void closed(final Player player) {
        graves.onClosed(player, this);
    }
}
