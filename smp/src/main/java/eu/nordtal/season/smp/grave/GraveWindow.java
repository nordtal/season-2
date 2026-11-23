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
import org.bukkit.entity.Player;
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
    static final int EXAMPLE_ROWS = 2;

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "grave",
                    "Menus",
                    Window.class,
                    GravePanel.rows(EXAMPLE_ROWS),
                    Slot.item("owner", Owner.class).at(List.of(GravePanel.headSlot(EXAMPLE_ROWS))),
                    Slot.label("experience-line"),
                    Slot.label("take-all-button"))
            .titled("Grave");

    private final Graves graves;
    private final UUID graveId;
    private final int contentRows;
    private final OpenGrave grave;
    private @Nullable Player closer;

    /** @param experience the header's mark, null for none */
    GraveWindow(
            final Graves graves,
            final UUID graveId,
            final int contentRows,
            final OpenGrave grave,
            final String name,
            final @Nullable String experience,
            final String takeAll) {
        this.graves = graves;
        this.graveId = graveId;
        this.contentRows = contentRows;
        this.grave = grave;
        final var _ = frame(GravePanel.rows(contentRows), GravePanel.title(name, contentRows, experience, takeAll));
    }

    UUID graveId() {
        return graveId;
    }

    int contentRows() {
        return contentRows;
    }

    /** Returns the player who closed the window last, or null while no one has. */
    @Nullable
    Player closer() {
        return closer;
    }

    /** Allows items to move while the grave holds loot, so none moves once it is emptied or expires. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return GravePanel.leavesFree(grave.holdsLoot(), rawSlot, contentRows);
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!GravePanel.takeAllSlots(contentRows).contains(slot)) {
            return MenuClick.nothing();
        }
        if (grave.holdsLoot()) {
            graves.takeAll(player, this);
        }
        // Closing the window lets Graves settle the grave, also if it is empty.
        return MenuClick.closing();
    }

    @Override
    protected void closed(final Player player) {
        closer = player;
        graves.onClosed(player, this);
    }
}
