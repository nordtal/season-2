package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
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
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** The deposit screen. Closing it returns all deposited items to the player. */
public final class HandInGui extends Menu {

    /** @param amount how much the objective still needs */
    record Window(@Example("64") long amount) {}

    /** One item an objective takes, one tooltip line each, since the client does not wrap a line. */
    record Wanted(@Example("item.minecraft.diamond") GameContent item) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "hand-in",
                    "Menus",
                    Window.class,
                    HandInPanel.ROWS,
                    Slot.item("sample").at(List.of(HandInPanel.SAMPLE_SLOT)),
                    Slot.item("confirm").at(HandInPanel.CONFIRM_SLOTS),
                    Slot.list("wanted", Wanted.class),
                    Slot.label("still-needed"),
                    Slot.label("confirm-button"))
            .titled("Hand in");

    private static final int DEPOSIT_SLOTS = HandInPanel.DEPOSIT_SLOTS;

    private final Inventory inventory;
    private final Objective objective;
    private final long stillNeeded;
    private final Set<String> wanted;
    private final BiConsumer<Player, HandInGui> confirm;

    /** @param confirm runs when the player presses confirm */
    public HandInGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final Objective objective,
            final long amount,
            final long target,
            final BiConsumer<Player, HandInGui> confirm) {
        this.objective = objective;
        this.confirm = confirm;
        this.stillNeeded = Math.max(0L, target - amount);
        this.wanted = new LinkedHashSet<>(objective.items() == null ? List.of() : objective.items());

        final GameDocument document =
                new GameDocument(renderer, documents.applied(DOCUMENT), Viewer.of(locale), new Window(stillNeeded));
        this.inventory = frame(
                HandInPanel.ROWS,
                HandInPanel.title(document.title(), document.label("still-needed"), document.label("confirm-button")));

        final Map<String, List<? extends Record>> lists = Map.of("wanted", wanted(wanted));
        // Shows the first wanted material as a sample item.
        sample().ifPresent(material -> inventory.setItem(
                HandInPanel.SAMPLE_SLOT,
                BlankItem.described(new ItemStack(material), document.item("sample", null, lists))));

        final ItemStack button = BlankItem.of(document.item("confirm", null, lists));
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, button));
    }

    static List<Wanted> wanted(final java.util.Collection<String> items) {
        return items.stream().map(GameKeys::item).map(Wanted::new).toList();
    }

    /** Returns the material of the first wanted item. {@link TrackNames} validates the names. */
    private java.util.Optional<Material> sample() {
        return wanted.stream()
                .map(GameKeys::material)
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    public Objective objective() {
        return objective;
    }

    /** Leaves the deposit slots and the player's own inventory free to move items in. */
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

    @Override
    protected void closed(final Player player) {
        returnEverything(player);
    }

    /** Returns all deposited items when the plugin stops. */
    @Override
    protected void stopped(final Player player) {
        returnEverything(player);
    }

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
     * Removes the taken amounts from the deposit slots and keeps the rest there.
     *
     * @return the removed stacks
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

    /** Gives the player the stacks that {@link #apply} removed. */
    public void giveBack(final Player player, final List<ItemStack> stacks) {
        stacks.forEach(stack -> give(player, stack));
    }

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

    /** Adds the stack to the inventory and drops what does not fit at the player's location. */
    private static void give(final Player player, final ItemStack stack) {
        final org.bukkit.Location dropAt = java.util.Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
    }
}
