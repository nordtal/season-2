package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.milestone.Objective;

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
 * <p>Items are only consumed on an explicit confirmation, so a misplaced shift-click cannot
 * swallow an inventory. <b>Closing the screen without pressing the button gives everything back</b>,
 * which has to be done deliberately: a plugin inventory that is simply closed drops its contents
 * into nothing.
 */
public final class HandInGui implements Surface {

    private static final int DEPOSIT_SLOTS = HandInPanel.DEPOSIT_SLOTS;

    private final Inventory inventory;
    private final Objective objective;
    private final long stillNeeded;
    private final Set<String> wanted;

    public HandInGui(final Messages messages, final Locale locale, final Objective objective,
                     final long amount, final long target) {
        this.objective = objective;
        this.stillNeeded = Math.max(0L, target - amount);
        this.wanted = new LinkedHashSet<>(objective.items() == null ? List.of() : objective.items());

        this.inventory = Bukkit.createInventory(this,
                HandInPanel.ROWS * SlotGeometry.COLUMNS,
                HandInPanel.title(
                        MessageRenderer.of(messages).get(locale, "smp.handin.title"),
                        messages.format(locale, "smp.handin.still-needed",
                                java.util.Map.of("amount", stillNeeded)),
                        messages.get(locale, "smp.handin.confirm-button")));

        // A sample of the first wanted material, so the window says what it wants without a
        // sentence. Not takeable - every click outside the tray is cancelled - and it carries the
        // full list, which a single icon cannot show.
        sample().ifPresent(item -> inventory.setItem(HandInPanel.SAMPLE_SLOT,
                describe(messages, locale, item)));

        final ItemStack confirm = BlankItem.of(
                MessageRenderer.of(messages).get(locale, "smp.handin.confirm"),
                List.of(MessageRenderer.of(messages).format(locale, "smp.handin.needed",
                        "amount", stillNeeded, "items", String.join(", ", wanted))));
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, confirm));
    }

    /**
     * The first wanted material this server knows, as an item.
     *
     * <p>A name that reached here unbound is a configuration this server refused, so the sample is
     * left out rather than throwing inside a menu constructor.</p>
     */
    private java.util.Optional<Material> sample() {
        return wanted.stream()
                .map(name -> Material.matchMaterial(name))
                .filter(java.util.Objects::nonNull)
                .filter(material -> material.isItem())
                .findFirst();
    }

    private ItemStack describe(final Messages messages, final Locale locale,
                               final Material material) {
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MessageRenderer.of(messages).get(locale, "smp.handin.wanted")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(MessageRenderer.of(messages)
                    .format(locale, "smp.handin.needed", "amount", stillNeeded,
                            "items", String.join(", ", wanted))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return stack;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Objective objective() {
        return objective;
    }

    public static boolean isConfirm(final int slot) {
        return HandInPanel.CONFIRM_SLOTS.contains(slot);
    }

    /** Whether a slot is one a player may put something into. */
    public static boolean isDeposit(final int slot) {
        return HandInPanel.isDeposit(slot);
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
     * <p><b>It hands back what it took.</b> The credit that pays for these items runs
     * asynchronously and can legitimately credit nothing, and {@link #returnEverything} reads the
     * very slots this method has just emptied - so without these copies the items are gone and the
     * player is told the hand-in succeeded.
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
     * <p>Called on close, always: a plugin inventory that is simply closed drops its contents into
     * nothing.
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
