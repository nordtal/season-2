package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.milestone.Objective;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The deposit screen: put items in, press confirm, and only then does anything happen.
 *
 * <h2>Explicit confirmation, and what it protects against</h2>
 * docs/smp.md is unusually firm here - "items are only consumed on an explicit confirmation; a
 * misplaced shift-click must not swallow an inventory". So this screen is an ordinary chest that
 * anybody can move items in and out of freely, with one button. <b>Closing it without pressing that
 * button gives everything back</b>, which has to be done deliberately: a plugin inventory that is
 * simply closed drops its contents into nothing.
 *
 * <p>There is no hopper-fed chest anywhere in this design either. Automated delivery would turn
 * contribution counting into a race between farms.
 */
public final class HandInGui implements Surface {

    private static final int ROWS = 4;
    private static final int DEPOSIT_SLOTS = 27;
    private static final int CONFIRM_SLOT = 31;

    private final Inventory inventory;
    private final Objective objective;
    private final long stillNeeded;
    private final Set<String> wanted;

    public HandInGui(final Messages messages, final Locale locale, final Objective objective,
                     final long amount, final long target) {
        this.objective = objective;
        this.stillNeeded = Math.max(0L, target - amount);
        this.wanted = new LinkedHashSet<>(objective.items() == null ? List.of() : objective.items());

        this.inventory = Bukkit.createInventory(this, ROWS * 9, MenuTitle.of(ROWS,
                MessageRenderer.of(messages).get(locale, "smp.handin.title")));

        final ItemStack confirm = new ItemStack(Material.LIME_CONCRETE);
        confirm.editMeta(meta -> {
            meta.displayName(MessageRenderer.of(messages).get(locale, "smp.handin.confirm")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    MessageRenderer.of(messages).format(locale, "smp.handin.needed",
                                    "amount", stillNeeded, "items", String.join(", ", wanted))
                            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        });
        inventory.setItem(CONFIRM_SLOT, confirm);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Objective objective() {
        return objective;
    }

    public static boolean isConfirm(final int slot) {
        return slot == CONFIRM_SLOT;
    }

    /** Whether a slot is one a player may put something into. */
    public static boolean isDeposit(final int slot) {
        return slot >= 0 && slot < DEPOSIT_SLOTS;
    }

    /** What is currently sitting in the deposit slots, as plain values the sorter understands. */
    public List<HandIn.Offered> offered() {
        final List<HandIn.Offered> out = new ArrayList<>();
        for (int slot = 0; slot < DEPOSIT_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
                continue;
            }
            out.add(new HandIn.Offered(slot, stack.getType().name(), stack.getAmount()));
        }
        return out;
    }

    public Set<String> wanted() {
        return wanted;
    }

    public long stillNeeded() {
        return stillNeeded;
    }

    /**
     * Applies a sorted deposit: takes what was accepted, leaves the rest in place.
     *
     * <p><b>It hands back what it took</b>, and that return value is not a convenience. The credit
     * that pays for these items runs asynchronously, and it can legitimately credit <em>nothing</em>
     * - a second player finished the objective while this screen was open, or the database refused
     * the write. The only restore path is {@link #returnEverything}, which reads the very slots this
     * method has just emptied, so without the copies the items are gone and the player is told the
     * hand-in succeeded. Found by review, 2026-09-04.
     *
     * @return the stacks that were removed, as they were before removal
     */
    public List<ItemStack> apply(final HandIn.Result result) {
        final List<ItemStack> taken = new ArrayList<>();
        for (final HandIn.Take take : result.takes()) {
            final ItemStack stack = inventory.getItem(take.slot());
            if (stack == null) {
                continue;
            }
            if (take.taken() > 0) {
                final ItemStack copy = stack.clone();
                copy.setAmount(take.taken());
                taken.add(copy);
            }
            if (take.returned() <= 0) {
                inventory.setItem(take.slot(), null);
            } else {
                stack.setAmount(take.returned());
                inventory.setItem(take.slot(), stack);
            }
        }
        return taken;
    }

    /**
     * Gives back stacks {@link #apply} removed, when the credit they paid for did not happen.
     *
     * <p>Straight into the player's inventory rather than back into the screen: by the time this is
     * known the screen may be closed, and a slot that is put back after {@link #returnEverything}
     * has run would be emptied by nothing at all.
     */
    public void giveBack(final Player player, final List<ItemStack> stacks) {
        stacks.forEach(stack -> give(player, stack));
    }

    /**
     * Gives everything in the deposit slots back to the player.
     *
     * <p>Called on close, always. A plugin inventory that is simply closed drops its contents into
     * nothing, so this is not a courtesy - it is the difference between a screen somebody backed out
     * of and a screen that ate their diamonds.
     */
    public void returnEverything(final Player player) {
        for (int slot = 0; slot < DEPOSIT_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            inventory.setItem(slot, null);
            give(player, stack);
        }
    }

    /** Into the inventory, and on the floor at their feet if it does not fit. Never nowhere. */
    private static void give(final Player player, final ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }
}
