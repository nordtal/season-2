package eu.nordtal.s2.smp.npc;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.papercommon.game.GameKeys;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.menu.BlankItem;
import eu.nordtal.s2.smp.menu.SlotGeometry;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.TrackNames;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The deposit screen: put items in, press confirm, and only then does anything happen.
 *
 * Closing without confirming gives everything back, since a closed plugin inventory drops its contents into nothing.
 */
public final class HandInGui implements Surface {

    private static final int DEPOSIT_SLOTS = HandInPanel.DEPOSIT_SLOTS;

    private final Inventory inventory;
    private final Objective objective;
    private final long stillNeeded;
    private final Set<String> wanted;

    public HandInGui(
            final Messages messages,
            final Locale locale,
            final Objective objective,
            final long amount,
            final long target) {
        this.objective = objective;
        this.stillNeeded = Math.max(0L, target - amount);
        this.wanted = new LinkedHashSet<>(objective.items() == null ? List.of() : objective.items());

        this.inventory = Bukkit.createInventory(
                this,
                HandInPanel.ROWS * SlotGeometry.COLUMNS,
                HandInPanel.title(
                        MessageRenderer.of(messages)
                                .format(locale, MESSAGES.smp().handin().title()),
                        messages.format(locale, MESSAGES.smp().handin().stillNeeded(stillNeeded)),
                        messages.format(locale, MESSAGES.smp().handin().confirmButton())));

        // A sample of the first wanted material, so the window says what it wants without a sentence.
        sample().ifPresent(item -> inventory.setItem(HandInPanel.SAMPLE_SLOT, describe(messages, locale, item)));

        final ItemStack confirm = BlankItem.of(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().handin().confirm()),
                List.of(MessageRenderer.of(messages)
                        .format(locale, MESSAGES.smp().handin().needed(stillNeeded, wantedItems()))));
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, confirm));
    }

    /** The wanted items, which the client names in its reader's language. */
    private List<GameContent> wantedItems() {
        return wanted.stream().map(GameKeys::item).toList();
    }

    /** The first wanted item, which {@link TrackNames} checked when the track loaded. */
    private java.util.Optional<Material> sample() {
        return wanted.stream()
                .map(GameKeys::material)
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    private ItemStack describe(final Messages messages, final Locale locale, final Material material) {
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().handin().wanted())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().handin().needed(stillNeeded, wantedItems()))
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
     * Takes what was accepted and leaves the rest in place.
     *
     * @return the stacks that were removed, so a credit that fails can hand them back
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

    /** Gives back stacks {@link #apply} removed, straight into the inventory, when their credit did not happen. */
    public void giveBack(final Player player, final List<ItemStack> stacks) {
        stacks.forEach(stack -> give(player, stack));
    }

    /** Gives everything in the deposit slots back to the player; called on every close. */
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

    /** Into the inventory, and on the floor at their feet if it does not fit. */
    private static void give(final Player player, final ItemStack stack) {
        final org.bukkit.Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
    }
}
