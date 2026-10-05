package eu.nordtal.season.smp.npc;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.TrackNames;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The deposit screen: put items in, press confirm, and only then does anything happen.
 *
 * Closing without confirming gives everything back, since a closed plugin inventory drops its contents into nothing.
 */
public final class HandInGui extends Menu {

    private static final int DEPOSIT_SLOTS = HandInPanel.DEPOSIT_SLOTS;

    private final Inventory inventory;
    private final Objective objective;
    private final long stillNeeded;
    private final Set<String> wanted;
    private final BiConsumer<Player, HandInGui> confirm;

    /**
     * Builds the deposit screen for one objective.
     *
     * @param confirm credits what the player put in, once they press confirm
     */
    public HandInGui(
            final MessageRenderer renderer,
            final Locale locale,
            final Objective objective,
            final long amount,
            final long target,
            final BiConsumer<Player, HandInGui> confirm) {
        this.objective = objective;
        this.confirm = confirm;
        this.stillNeeded = Math.max(0L, target - amount);
        this.wanted = new LinkedHashSet<>(objective.items() == null ? List.of() : objective.items());

        this.inventory = frame(
                HandInPanel.ROWS,
                HandInPanel.title(
                        renderer.format(locale, MESSAGES.smp().handin().title()),
                        renderer.raw().format(locale, MESSAGES.smp().handin().stillNeeded(stillNeeded)),
                        renderer.raw().format(locale, MESSAGES.smp().handin().confirmButton())));

        // A sample of the first wanted material, so the window says what it wants without a sentence.
        sample().ifPresent(item -> inventory.setItem(HandInPanel.SAMPLE_SLOT, describe(renderer, locale, item)));

        final ItemStack button = BlankItem.of(
                renderer.format(locale, MESSAGES.smp().handin().confirm()),
                List.of(renderer.format(locale, MESSAGES.smp().handin().needed(stillNeeded, wantedItems()))));
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, button));
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

    private ItemStack describe(final MessageRenderer renderer, final Locale locale, final Material material) {
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(renderer.format(locale, MESSAGES.smp().handin().wanted())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(List.of(renderer.format(locale, MESSAGES.smp().handin().needed(stillNeeded, wantedItems()))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return stack;
    }

    public Objective objective() {
        return objective;
    }

    /** The deposit slots are deliberately free, and so is the player's own inventory below: this is a chest to fill. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return rawSlot < 0 || rawSlot >= inventory.getSize() || HandInPanel.isDeposit(rawSlot);
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (HandInPanel.CONFIRM_SLOTS.contains(slot)) {
            confirm.accept(player, this);
        }
        return MenuClick.nothing();
    }

    /** Closing without confirming gives everything back. */
    @Override
    protected void closed(final Player player) {
        returnEverything(player);
    }

    /** Paper disconnects players after the plugins stop, and a closed plugin inventory drops what it holds. */
    @Override
    protected void stopped(final Player player) {
        returnEverything(player);
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

    /** Gives everything in the deposit slots back to the player. */
    private void returnEverything(final Player player) {
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
