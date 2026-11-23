package eu.nordtal.season.smp.duel;

import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One player's stake chest: the own deposit area is free, every other slot is drawn by the {@link StakeTable}.
 *
 * Closing it keeps the deposited items in the chest; they go back to the player when the pair ends before the lock.
 */
final class StakeChest extends Menu {

    /** What a click on the chest asks of the table. */
    interface Seat {

        /** The own deposit area changed. */
        void deposited(int side);

        void step(int side, int delta);

        void confirm(int side, Player player);

        void withdraw(int side);

        /** The player closed the chest with Esc. */
        void closed(int side, Player player);
    }

    private final Inventory inventory;
    private final int side;
    private final Seat seat;
    private final boolean readOnly;
    private Component shown;
    private boolean countdown;
    private boolean ready;
    private boolean locked;

    StakeChest(final int side, final Seat seat, final Component title, final boolean readOnly) {
        this.side = side;
        this.seat = seat;
        this.readOnly = readOnly;
        this.shown = title;
        this.inventory = frame(StakePanel.ROWS, title);
    }

    int side() {
        return side;
    }

    /**
     * Sets every drawn slot and redraws the title through a reopen, only when the title changed.
     *
     * @param display the item of each drawn slot; a drawn slot missing from it stays empty
     */
    void draw(
            final @Nullable Player viewer,
            final Component title,
            final Map<Integer, ItemStack> display,
            final boolean readyNow,
            final boolean countdownNow) {
        ready = readyNow;
        countdown = countdownNow;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!StakePanel.isOwnDeposit(slot)) {
                inventory.setItem(slot, display.get(slot));
            }
        }
        if (!title.equals(shown)) {
            shown = title;
            if (viewer != null) {
                retitle(viewer, title);
            }
        }
    }

    /** Returns copies of the stacks in the own deposit area. */
    List<ItemStack> deposits() {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final int slot : StakePanel.OWN_DEPOSIT) {
            final @Nullable ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.isEmpty()) {
                stacks.add(stack.clone());
            }
        }
        return stacks;
    }

    /** Puts a stack into the own deposit area, for Steward's preview. */
    void put(final int index, final ItemStack stack) {
        inventory.setItem(StakePanel.OWN_DEPOSIT.get(index), stack);
    }

    /** Empties the own deposit area and returns what it held, and ignores every later click and close. */
    List<ItemStack> lock() {
        locked = true;
        final List<ItemStack> taken = deposits();
        StakePanel.OWN_DEPOSIT.forEach(slot -> inventory.setItem(slot, null));
        return taken;
    }

    /** Gives the deposited items to {@code player}, and drops what does not fit at their feet. */
    void giveBack(final Player player) {
        hand(player, lock());
    }

    /** Gives {@code stacks} to {@code player}, and drops what does not fit at their feet. */
    static void hand(final Player player, final List<ItemStack> stacks) {
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        stacks.forEach(stack -> player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left)));
    }

    @Override
    protected boolean leavesFree(final int rawSlot, final ClickType type) {
        if (readOnly || locked) {
            return false;
        }
        if (rawSlot < 0) {
            return true;
        }
        if (rawSlot >= inventory.getSize()) {
            return !type.isShiftClick() && type != ClickType.DOUBLE_CLICK;
        }
        return StakePanel.isOwnDeposit(rawSlot) && type != ClickType.DOUBLE_CLICK;
    }

    @Override
    protected boolean leavesFree(final int rawSlot) {
        return leavesFree(rawSlot, ClickType.LEFT);
    }

    @Override
    protected MenuClick click(final Player player, final int slot, final ClickType type) {
        if (readOnly || locked) {
            return MenuClick.nothing();
        }
        if (StakePanel.isOwnDeposit(slot)) {
            seat.deposited(side);
        } else if (slot == StakePanel.LOWER || slot == StakePanel.RAISE) {
            final int step = type.isShiftClick() ? StakeState.SHIFT_STEP : StakeState.STEP;
            seat.step(side, slot == StakePanel.LOWER ? -step : step);
        } else if (countdown && slot == StakePanel.WITHDRAW) {
            seat.withdraw(side);
        } else if (!countdown && StakePanel.READY.contains(slot)) {
            if (ready) {
                seat.withdraw(side);
            } else {
                seat.confirm(side, player);
            }
        }
        return MenuClick.nothing();
    }

    /** Routes a shift-click from the player's inventory into the own deposit area. */
    @Override
    protected MenuClick clickBelow(final Player player, final int rawSlot, final ClickType type) {
        if (readOnly || locked || !type.isShiftClick()) {
            return MenuClick.nothing();
        }
        final int playerSlot = player.getOpenInventory().convertSlot(rawSlot);
        final @Nullable ItemStack source = player.getInventory().getItem(playerSlot);
        if (source == null || source.isEmpty()) {
            return MenuClick.nothing();
        }
        final ItemStack moving = source.clone();
        deposit(moving);
        if (moving.getAmount() == source.getAmount()) {
            return MenuClick.nothing();
        }
        player.getInventory().setItem(playerSlot, moving.getAmount() <= 0 ? null : moving);
        seat.deposited(side);
        return MenuClick.nothing();
    }

    @Override
    protected void dragged(final Player player) {
        if (!readOnly && !locked) {
            seat.deposited(side);
        }
    }

    @Override
    protected void closed(final Player player) {
        if (!readOnly && !locked) {
            seat.closed(side, player);
        }
    }

    @Override
    protected void stopped(final Player player) {
        if (!readOnly && !locked) {
            giveBack(player);
        }
    }

    /** Moves as much of {@code stack} as fits into the own deposit area, leaving the rest in {@code stack}. */
    private void deposit(final ItemStack stack) {
        for (final int slot : StakePanel.OWN_DEPOSIT) {
            final @Nullable ItemStack held = inventory.getItem(slot);
            if (stack.getAmount() > 0 && held != null && !held.isEmpty() && held.isSimilar(stack)) {
                final int moved = Math.min(stack.getAmount(), held.getMaxStackSize() - held.getAmount());
                if (moved > 0) {
                    held.setAmount(held.getAmount() + moved);
                    inventory.setItem(slot, held);
                    stack.setAmount(stack.getAmount() - moved);
                }
            }
        }
        for (final int slot : StakePanel.OWN_DEPOSIT) {
            final @Nullable ItemStack held = inventory.getItem(slot);
            if (stack.getAmount() > 0 && (held == null || held.isEmpty())) {
                inventory.setItem(slot, stack.clone());
                stack.setAmount(0);
            }
        }
    }
}
