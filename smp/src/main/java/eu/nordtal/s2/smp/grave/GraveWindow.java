package eu.nordtal.s2.smp.grave;

import eu.nordtal.s2.papercommon.menu.Menu;
import eu.nordtal.s2.papercommon.menu.MenuClick;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The one window a grave is shown in, however many people look: content rows anyone may take from, and a footer.
 *
 * The footer is furniture and one button; {@link Graves} settles the grave once its last viewer closes it.
 */
final class GraveWindow extends Menu {

    private final Graves graves;
    private final UUID graveId;
    private final int contentRows;
    private final Inventory inventory;

    /**
     * Frames the window for one grave.
     *
     * @param experience what stands beside the head, blank for none
     */
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

    /** The content rows are free, and so is the player's own inventory below them. */
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
        // Even an empty grave is not a refusal: the close settles it and gives the head back.
        return MenuClick.closing();
    }

    @Override
    protected void closed(final Player player) {
        graves.onClosed(player, this);
    }
}
