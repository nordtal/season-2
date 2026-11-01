package eu.nordtal.season.smp.takein;

import eu.nordtal.season.database.inbox.TakeInPurpose;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The window in which an admin's player puts in the item that becomes a wheel prize or a piece of a duel kit.
 * A click on its row with a stack, or a shift-click on a stack below, copies that stack; the player keeps it.
 */
final class TakeInWindow extends Menu {

    static final int ROWS = 1;

    static final DocumentSpec<DocumentSpec.None> PRIZE = DocumentSpec.menu(
                    "smp",
                    "wheel-take-in",
                    "Wheel",
                    DocumentSpec.None.class,
                    ROWS,
                    Slot.item("put-in").at(SlotGeometry.rows(0, ROWS)))
            .titled("Wheel prize take-in");

    static final DocumentSpec<DocumentSpec.None> KIT = DocumentSpec.menu(
                    "smp",
                    "duel-kit-take-in",
                    "Duels",
                    DocumentSpec.None.class,
                    ROWS,
                    Slot.item("put-in").at(SlotGeometry.rows(0, ROWS)))
            .titled("Duel kit take-in");

    /** Returns the window's document, which says what the item becomes. */
    static DocumentSpec<DocumentSpec.None> document(final TakeInPurpose purpose) {
        return switch (purpose) {
            case WHEEL_PRIZE -> PRIZE;
            case DUEL_KIT -> KIT;
        };
    }

    // Handed the copied stack once, or null when the window closed empty.
    private final Consumer<@Nullable ItemStack> taken;
    private boolean ended;

    TakeInWindow(final GameDocument document, final Consumer<@Nullable ItemStack> taken) {
        this.taken = Objects.requireNonNull(taken, "taken");
        final Inventory inventory = frame(ROWS, MenuTitle.of(ROWS, document.title()));
        final ItemStack hint = BlankItem.of(document.item("put-in"));
        SlotGeometry.rows(0, ROWS).forEach(slot -> inventory.setItem(slot, hint));
    }

    /** The player's own inventory stays free, so a stack can be picked up there. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return rawSlot >= ROWS * SlotGeometry.COLUMNS;
    }

    /** A shift-click below would move the stack into the window, so it is refused and copies the stack instead. */
    @Override
    protected boolean leavesFree(final int rawSlot, final ClickType type) {
        return leavesFree(rawSlot) && !type.isShiftClick();
    }

    @Override
    protected MenuClick click(final Player player, final int slot, final ClickType type) {
        return take(player.getItemOnCursor());
    }

    @Override
    protected MenuClick clickBelow(final Player player, final int rawSlot, final ClickType type) {
        return type.isShiftClick() ? take(player.getOpenInventory().getItem(rawSlot)) : MenuClick.nothing();
    }

    @Override
    protected void closed(final Player player) {
        end(null);
    }

    /** Closes the window without an answer, because its request was withdrawn. */
    void withdraw(final @Nullable Player player) {
        ended = true;
        if (player != null && showsTo(player)) {
            player.closeInventory();
        }
    }

    private MenuClick take(final @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MenuClick.nothing();
        }
        end(stack.asOne());
        return MenuClick.closing();
    }

    private void end(final @Nullable ItemStack stack) {
        if (!ended) {
            ended = true;
            taken.accept(stack);
        }
    }
}
