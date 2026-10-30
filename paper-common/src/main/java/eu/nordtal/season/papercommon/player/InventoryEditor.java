package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Stack;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A window over a player's Survival inventory or ender chest, which an admin edits.
 * Over a player here, each click moves stacks in the live inventory on the next tick; over a stored state, the window
 * is a copy, and on close {@link ModeStates} writes the admin's changes if every slot still holds what it showed.
 */
public final class InventoryEditor extends Menu {

    /** @param count the slots that changed while the window was open, so that none of the edit was written */
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
            .titled("Edit not written");

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
    // Set once a click moved a stack of the live inventory.
    private boolean moved;
    private Scheduler.@Nullable Task refresh;

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

    /**
     * Leaves the admin's own inventory free, and over a stored state every slot of the container too.
     * Over a player here, no click fills the window: each click on a slot is made in the live inventory instead.
     */
    @Override
    protected boolean leavesFree(final int rawSlot, final ClickType type) {
        if (rawSlot < 0 || rawSlot >= window.getSize()) {
            // Over a stored state, nothing taken from the copy leaves the admin's inventory before the edit is written.
            return live() ? !(type.isShiftClick() || type == ClickType.DOUBLE_CLICK) : rawSlot >= 0 && !drops(type);
        }
        return leavesFree(rawSlot) && !drops(type);
    }

    private static boolean drops(final ClickType type) {
        return type == ClickType.DROP || type == ClickType.CONTROL_DROP;
    }

    @Override
    protected boolean leavesFree(final int rawSlot) {
        return rawSlot < 0 || rawSlot >= window.getSize() || (!live() && slotOf(container, rawSlot) >= 0);
    }

    @Override
    protected MenuClick click(final Player viewer, final int view, final ClickType type) {
        final int slot = slotOf(container, view);
        if (live() && slot >= 0 && (type == ClickType.LEFT || type == ClickType.RIGHT)) {
            states.move(this, viewer, slot, type == ClickType.RIGHT);
        }
        return MenuClick.nothing();
    }

    /** What a click leaves: the amounts on the cursor and in the slot, or a swap of the two. */
    record Move(int cursor, int slot, boolean swap) {}

    /**
     * Returns what a left or right click with {@code cursor} items on {@code slot} items leaves, as in a chest.
     *
     * @param similar whether both hold the same kind of stack
     * @param max how many of the kind fit in the slot
     */
    static Move move(final boolean right, final int cursor, final int slot, final boolean similar, final int max) {
        if (cursor == 0 && slot == 0) {
            return new Move(0, 0, false);
        } else if (cursor == 0) {
            final int taken = right ? (slot + 1) / 2 : slot;
            return new Move(taken, slot - taken, false);
        } else if (slot == 0 || similar) {
            final int put = Math.max(0, Math.min(right ? 1 : cursor, max - slot));
            return new Move(cursor - put, slot + put, false);
        }
        return new Move(/* cursor= */ slot, /* slot= */ cursor, true);
    }

    /**
     * Returns, by kind, how many more of it the window held at the close than it showed, leaving out the kinds even.
     * A refused edit gives the admin what is more and takes back what is less; a move within the window evens out.
     */
    static <K> Map<K, Integer> net(final List<Map.Entry<K, Integer>> shown, final List<Map.Entry<K, Integer>> held) {
        final Map<K, Integer> net = new LinkedHashMap<>();
        held.forEach(stack -> net.merge(stack.getKey(), stack.getValue(), Integer::sum));
        shown.forEach(stack -> net.merge(stack.getKey(), -stack.getValue(), Integer::sum));
        net.values().removeIf(amount -> amount == 0);
        return net;
    }

    /** Marks that a click moved a stack of the live inventory. */
    void moved() {
        moved = true;
    }

    boolean wasMoved() {
        return moved;
    }

    /** Shows {@code contents} in the window where a slot holds something else. */
    void show(final @Nullable ItemStack[] contents) {
        for (int view = 0; view < window.getSize(); view++) {
            final int slot = slotOf(container, view);
            if (slot < 0 || slot >= contents.length) {
                continue;
            }
            final ItemStack now = present(contents[slot]);
            if (!Objects.equals(now, present(window.getItem(view)))) {
                window.setItem(view, now == null ? null : now.clone());
            }
        }
    }

    void refreshing(final Scheduler.Task task) {
        refresh = task;
    }

    @Override
    protected void closed(final Player viewer) {
        commit();
    }

    @Override
    protected void stopped(final Player viewer) {
        commit();
    }

    /** Hands the editor to {@link ModeStates} once, on the first close, and stops showing the live inventory. */
    void commit() {
        if (!committed) {
            committed = true;
            final Scheduler.Task task = refresh;
            if (task != null) {
                task.cancel();
            }
            states.commit(this);
        }
    }
}
