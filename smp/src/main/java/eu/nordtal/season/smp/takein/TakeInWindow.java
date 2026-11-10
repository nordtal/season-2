package eu.nordtal.season.smp.takein;

import eu.nordtal.season.database.inbox.TakeInPurpose;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.ChestTitle;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The window in which an admin's player puts in the item that becomes a wheel prize or a piece of a duel kit.
 * A click on its row with a stack, or a shift-click on a stack below, copies that stack; the player keeps it.
 * The window stays open and shows the copy in cell 0, and a later request of the same purpose takes the next item.
 */
final class TakeInWindow extends Menu {

    static final int ROWS = 1;

    /** The cell that shows the copy of the item put in last. */
    static final int COPY_SLOT = 0;

    static final DocumentSpec<DocumentSpec.None> PRIZE = DocumentSpec.menu(
                    "smp",
                    "wheel-take-in",
                    "Wheel",
                    DocumentSpec.None.class,
                    ROWS,
                    Slot.item("put-in").at(SlotGeometry.rows(0, ROWS)),
                    Slot.label("imported"))
            .titled("Wheel prize take-in");

    static final DocumentSpec<DocumentSpec.None> KIT = DocumentSpec.menu(
                    "smp",
                    "duel-kit-take-in",
                    "Duels",
                    DocumentSpec.None.class,
                    ROWS,
                    Slot.item("put-in").at(SlotGeometry.rows(0, ROWS)),
                    Slot.label("imported"))
            .titled("Duel kit take-in");

    /** Returns the window's document, which says what the item becomes. */
    static DocumentSpec<DocumentSpec.None> document(final TakeInPurpose purpose) {
        return switch (purpose) {
            case WHEEL_PRIZE -> PRIZE;
            case DUEL_KIT -> KIT;
        };
    }

    private final TakeInPurpose purpose;
    private final String imported;
    private final Inventory inventory;
    // Handed the copied stack once, or null when the window closed empty; null while no request waits.
    private @Nullable Consumer<@Nullable ItemStack> taken;

    TakeInWindow(final TakeInPurpose purpose, final GameDocument document, final Consumer<@Nullable ItemStack> taken) {
        this.purpose = purpose;
        this.taken = taken;
        this.imported = document.label("imported");
        this.inventory =
                frame(ROWS, title(PlainTextComponentSerializer.plainText().serialize(document.title())));
        final ItemStack plate = BlankItem.styled(document.item("put-in"));
        SlotGeometry.rows(0, ROWS).forEach(slot -> inventory.setItem(slot, plate));
    }

    /** Draws the header and the one plate over the row, both saying {@code name}. */
    static Component title(final String name) {
        return ChestTitle.of(ROWS)
                .header(Symbol.BOX, name, List.of())
                .plate(0, 0, SlotGeometry.COLUMNS, Look.NORMAL)
                .label(0, 0, SlotGeometry.COLUMNS, Symbol.BOX, name, Look.NORMAL)
                .build(BodyLine.silent(name));
    }

    TakeInPurpose purpose() {
        return purpose;
    }

    /** Returns whether a request waits for an item in this window. */
    boolean waiting() {
        return taken != null;
    }

    /** Lets the next item put in answer another request. */
    void await(final Consumer<@Nullable ItemStack> next) {
        taken = next;
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
        taken = null;
        if (player != null && showsTo(player)) {
            player.closeInventory();
        }
    }

    private MenuClick take(final @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return MenuClick.nothing();
        }
        if (taken == null) {
            return MenuClick.refused();
        }
        final ItemStack item = stack.asOne();
        end(item);
        inventory.setItem(
                COPY_SLOT, BlankItem.styled(item.clone(), item.effectiveName(), List.of(Component.text(imported))));
        return new MenuClick(Feedback.SMALL_SUCCESS, false, null);
    }

    private void end(final @Nullable ItemStack stack) {
        final Consumer<@Nullable ItemStack> answer = taken;
        if (answer != null) {
            taken = null;
            answer.accept(stack);
        }
    }
}
