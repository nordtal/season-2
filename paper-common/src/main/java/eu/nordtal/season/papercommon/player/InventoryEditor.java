package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Stack;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.Menu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A window over a copy of a player's Survival inventory or ender chest, which an admin edits.
 * On close, {@link ModeStates} writes each slot the admin changed, if the slot still holds what the window showed.
 */
public final class InventoryEditor extends Menu {

    /** @param count the slots that changed while the window was open and kept their stacks */
    public record Kept(@Example("2") int count) {}

    private static final int INVENTORY_ROWS = 5;
    private static final int ENDER_CHEST_ROWS = 3;

    public static final DocumentSpec<DocumentSpec.None> INVENTORY = DocumentSpec.menu(
                    "paper-common", "inventory-editor", "Admin", DocumentSpec.None.class, INVENTORY_ROWS)
            .titled("Inventory editor");
    public static final DocumentSpec<DocumentSpec.None> ENDER_CHEST = DocumentSpec.menu(
                    "paper-common", "ender-chest-editor", "Admin", DocumentSpec.None.class, ENDER_CHEST_ROWS)
            .titled("Ender chest editor");
    /** The admin reads it after closing an editor whose slots changed meanwhile. */
    public static final DocumentSpec<Kept> KEPT = DocumentSpec.game(
                    "paper-common", "editor-kept", "Admin", DocumentSpec.Shape.CHAT, Kept.class)
            .titled("Edit partly kept");

    private final ModeStates states;
    private final PlayerId admin;
    private final PlayerId player;
    private final Container container;
    // What the window showed, by slot of the container.
    private final List<@Nullable ItemStack> shown;
    // The stacks as the database held them, for a stored state; null while the player holds the state here.
    private final @Nullable List<@Nullable Stack> stored;
    private final IntConsumer kept;
    private final Inventory window;
    private boolean committed;

    InventoryEditor(
            final ModeStates states,
            final PlayerId admin,
            final PlayerId player,
            final Container container,
            final Component title,
            final List<@Nullable ItemStack> shown,
            final @Nullable List<@Nullable Stack> stored,
            final IntConsumer kept) {
        this.states = states;
        this.admin = admin;
        this.player = player;
        this.container = container;
        this.shown = Collections.unmodifiableList(new ArrayList<>(shown));
        this.stored = stored;
        this.kept = kept;
        this.window = frame(rows(container), title);
        for (int view = 0; view < window.getSize(); view++) {
            final int slot = slotOf(container, view);
            final ItemStack stack = slot < 0 ? null : shown.get(slot);
            if (stack != null) {
                window.setItem(view, stack.clone());
            }
        }
    }

    /** Returns the rows of the window over {@code container}. */
    public static int rows(final Container container) {
        return container == Container.INVENTORY ? INVENTORY_ROWS : ENDER_CHEST_ROWS;
    }

    /**
     * Returns the slot of the container that a window slot shows, or -1 for none.
     * The inventory shows the main slots on top, the hotbar under them, then the armour from the helmet down and the
     * offhand, as the player's own screen does.
     */
    static int slotOf(final Container container, final int view) {
        if (container == Container.ENDER_CHEST) {
            return view >= 0 && view < ENDER_CHEST_ROWS * SlotGeometry.COLUMNS ? view : -1;
        }
        final int main = 3 * SlotGeometry.COLUMNS;
        final int hotbar = main + SlotGeometry.COLUMNS;
        if (view < 0) {
            return -1;
        } else if (view < main) {
            return view + SlotGeometry.COLUMNS;
        } else if (view < hotbar) {
            return view - main;
        } else if (view < hotbar + 4) {
            // Slot 39 is the helmet and 36 the boots.
            return 2 * hotbar + 3 - view;
        } else if (view == hotbar + 4) {
            return hotbar + 4;
        }
        return -1;
    }

    PlayerId admin() {
        return admin;
    }

    PlayerId player() {
        return player;
    }

    Container container() {
        return container;
    }

    boolean live() {
        return stored == null;
    }

    /** Returns the stack the database held in {@code slot} when the window opened, for a stored state. */
    @Nullable
    Stack storedAt(final int slot) {
        return stored == null ? null : stored.get(slot);
    }

    void kept(final int count) {
        kept.accept(count);
    }

    /** A slot the admin changed: what the window showed and what it holds now. */
    record Change(
            int slot, @Nullable ItemStack before, @Nullable ItemStack after) {}

    /** Returns the slots the admin changed. */
    List<Change> changes() {
        final List<Change> changes = new ArrayList<>();
        for (int view = 0; view < window.getSize(); view++) {
            final int slot = slotOf(container, view);
            if (slot < 0) {
                continue;
            }
            final ItemStack now = present(window.getItem(view));
            final ItemStack was = shown.get(slot);
            if (!Objects.equals(now, was)) {
                changes.add(new Change(slot, was, now));
            }
        }
        return changes;
    }

    /** Returns the stacks the admin put where the window shows no slot. */
    List<ItemStack> strays() {
        final List<ItemStack> strays = new ArrayList<>();
        for (int view = 0; view < window.getSize(); view++) {
            final ItemStack stack = present(window.getItem(view));
            if (slotOf(container, view) < 0 && stack != null) {
                strays.add(stack);
            }
        }
        return strays;
    }

    static @Nullable ItemStack present(final @Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack;
    }

    /** Leaves every slot of the container and the admin's own inventory free, and the slots that show none closed. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return rawSlot < 0 || rawSlot >= window.getSize() || slotOf(container, rawSlot) >= 0;
    }

    @Override
    protected void closed(final Player viewer) {
        commit();
    }

    @Override
    protected void stopped(final Player viewer) {
        commit();
    }

    /** Hands the changes to {@link ModeStates} once, on the first close. */
    void commit() {
        if (!committed) {
            committed = true;
            states.commit(this);
        }
    }
}
