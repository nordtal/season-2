package eu.nordtal.season.smp.npc;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.menu.ItemSprites;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The three-row deposit chest with five explicit deposit slots and live figures outside the title.
 * Credit happens only on the hand-in plate; closing returns everything in the deposit slots.
 */
public final class HandInGui extends Menu {

    /** @param amount a figure formatted into a label */
    record Window(@Example("64") long amount) {}

    /** Live progress figures for the bar tooltip. */
    record Progress(
            @Example("120") long amount,
            @Example("256") long target,
            @Example("64") long offer) {}

    /** One item an objective takes, one tooltip line each, since the client does not wrap a line. */
    record Wanted(@Example("item.minecraft.diamond") GameContent item) {}

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "hand-in",
                    "Menus",
                    Window.class,
                    HandInPanel.ROWS,
                    Slot.item("sample").at(HandInPanel.TICK_SLOTS),
                    Slot.list("wanted", Wanted.class),
                    Slot.label("accepted-title", Window.class),
                    Slot.label("return-title", Window.class),
                    Slot.label("return-not-counted", Window.class),
                    Slot.label("return-surplus", Window.class),
                    Slot.label("progress", Progress.class),
                    Slot.label("progress-offer", Progress.class),
                    Slot.label("not-counted"),
                    Slot.label("finished"),
                    Slot.label("confirm-button"))
            .titled("Hand in");

    private final Inventory inventory;
    private final Objective objective;
    private final Set<String> wanted;
    private final BiConsumer<Player, HandInGui> confirm;
    private final Consumer<Runnable> nextTick;
    private final GameDocument document;
    private final String name;
    private final long target;
    private final boolean readOnly;
    private long amount;
    private long stillNeeded;
    private HandIn.Header header;
    // Set from a hand-in that took items until its credit is known, so a second click waits for it.
    private boolean crediting;

    /** Opens a live hand-in chest for {@code objective} with its opening {@code amount} and {@code target}. */
    public HandInGui(
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Locale locale,
            final String milestoneKey,
            final Objective objective,
            final long amount,
            final long target,
            final boolean completed,
            final BiConsumer<Player, HandInGui> confirm,
            final Consumer<Runnable> nextTick) {
        this(
                new GameDocument(
                        renderer,
                        documents.applied(DOCUMENT),
                        Viewer.of(locale),
                        new Window(Math.max(0L, target - amount))),
                MilestoneNames.objective(renderer, locale, milestoneKey, objective.key()),
                objective,
                amount,
                target,
                completed,
                confirm,
                nextTick,
                false);
    }

    private HandInGui(
            final GameDocument document,
            final String name,
            final Objective objective,
            final long amount,
            final long target,
            final boolean completed,
            final BiConsumer<Player, HandInGui> confirm,
            final Consumer<Runnable> nextTick,
            final boolean readOnly) {
        this.document = document;
        this.name = name;
        this.objective = objective;
        this.target = Math.max(0L, target);
        this.amount = Math.min(Math.max(0L, amount), this.target);
        this.stillNeeded = completed ? 0L : Math.max(0L, this.target - this.amount);
        this.header = completed ? HandIn.Header.FINISHED : HandIn.Header.NEEDED;
        this.wanted = new LinkedHashSet<>(objective.items());
        this.confirm = confirm;
        this.nextTick = nextTick;
        this.readOnly = readOnly;
        this.inventory = frame(
                HandInPanel.ROWS,
                HandInPanel.title(
                        name,
                        mark(),
                        header == HandIn.Header.NOT_COUNTED,
                        document.label("confirm-button"),
                        header == HandIn.Header.FINISHED));

        final ItemStack button = BlankItem.silent();
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, button));
        refreshLive();
    }

    /** Opens a read-only hand-in chest at its example state in {@code drafted}'s words for Steward's draft preview. */
    static void example(final GameDocument drafted, final Player reader) {
        final Objective sample = new Objective(
                "iron",
                ObjectiveType.HAND_IN,
                "example",
                256,
                List.of("IRON_INGOT", "IRON_BLOCK"),
                "",
                List.of(),
                "",
                0,
                20);
        final HandInGui preview = new HandInGui(
                drafted, Dialogs.plain(drafted.title()), sample, 120, 256, false, (p, g) -> {}, Runnable::run, true);
        preview.inventory.setItem(HandInPanel.DEPOSIT_SLOTS.get(0), new ItemStack(Material.IRON_INGOT, 40));
        preview.inventory.setItem(HandInPanel.DEPOSIT_SLOTS.get(1), new ItemStack(Material.IRON_INGOT, 24));
        preview.inventory.setItem(HandInPanel.DEPOSIT_SLOTS.get(2), new ItemStack(Material.BREAD, 12));
        preview.refreshLive();
        preview.open(reader);
    }

    /** Returns a line per item, its texture before its name where the pack's atlases draw it, else its name alone. */
    static List<Wanted> wanted(final java.util.Collection<String> items) {
        return items.stream()
                .map(item -> new Wanted(GameKeys.item(item)
                        .drawn(ItemSprites.of(GameKeys.key(item)).orElse(null))))
                .toList();
    }

    public Objective objective() {
        return objective;
    }

    /** Leaves only the five explicit deposit slots and regular player-inventory clicks free; shift-click is handled. */
    @Override
    protected boolean leavesFree(final int rawSlot, final ClickType type) {
        if (readOnly) {
            return false;
        }
        if (rawSlot < 0) {
            return true;
        }
        if (rawSlot >= inventory.getSize()) {
            return !type.isShiftClick() && type != ClickType.DOUBLE_CLICK;
        }
        return HandInPanel.isDeposit(rawSlot);
    }

    @Override
    protected boolean leavesFree(final int rawSlot) {
        if (readOnly) {
            return false;
        }
        return rawSlot < 0 || rawSlot >= inventory.getSize() || HandInPanel.isDeposit(rawSlot);
    }

    @Override
    protected MenuClick click(final Player player, final int slot, final ClickType type) {
        if (readOnly) {
            return MenuClick.nothing();
        }
        if (HandInPanel.CONFIRM_SLOTS.contains(slot) && header != HandIn.Header.FINISHED && !crediting) {
            confirm.accept(player, this);
            return MenuClick.nothing();
        }
        if (HandInPanel.isDeposit(slot)) {
            nextTick.accept(this::refreshLive);
        }
        return MenuClick.nothing();
    }

    /** Routes shift-clicks from the player's inventory explicitly into the five deposit slots. */
    @Override
    protected MenuClick clickBelow(final Player player, final int rawSlot, final ClickType type) {
        if (readOnly || !type.isShiftClick()) {
            return MenuClick.nothing();
        }
        final int playerSlot = player.getOpenInventory().convertSlot(rawSlot);
        final ItemStack source = player.getInventory().getItem(playerSlot);
        if (source == null || source.getType().isAir() || source.getAmount() <= 0) {
            return MenuClick.nothing();
        }
        final ItemStack moving = source.clone();
        deposit(moving);
        if (moving.getAmount() <= 0) {
            player.getInventory().setItem(playerSlot, null);
        } else if (moving.getAmount() != source.getAmount()) {
            player.getInventory().setItem(playerSlot, moving);
        }
        refreshLive();
        return MenuClick.nothing();
    }

    @Override
    protected void dragged(final Player player) {
        if (!readOnly) {
            nextTick.accept(this::refreshLive);
        }
    }

    /** Answers a hand-in that took nothing: the header says so when the window holds items. */
    public void refuse(final Player player) {
        show(player, mark(), HandIn.refused(offered().isEmpty()));
        refreshLive();
    }

    /** Marks the items {@link #apply} took as waiting for their credit. */
    public void crediting() {
        crediting = true;
    }

    /** Lets the next click hand in again, after a credit that failed. */
    public void uncredited() {
        crediting = false;
        refreshLive();
    }

    /** Shows what the credit of a hand-in that took {@code accepted} made of it: {@code paid} counted. */
    public void credited(final Player player, final long paid, final long accepted) {
        crediting = false;
        final String before = mark();
        final long gained = Math.max(0L, paid);
        amount = Math.min(target, amount + gained);
        stillNeeded = Math.max(0L, stillNeeded - gained);
        show(player, before, HandIn.credited(paid, accepted, stillNeeded));
        refreshLive();
    }

    /** Redraws the header for {@code next}, through a reopen only when its words change from {@code before}. */
    private void show(final Player player, final String before, final HandIn.Header next) {
        header = next;
        if (!mark().equals(before)) {
            retitle(player, title());
        }
    }

    private String mark() {
        return switch (header) {
            case NEEDED -> document.label("progress", new Progress(amount, target, 0));
            case NOT_COUNTED -> document.label("not-counted");
            case FINISHED -> document.label("finished");
        };
    }

    private Component title() {
        return HandInPanel.title(
                name,
                mark(),
                header == HandIn.Header.NOT_COUNTED,
                document.label("confirm-button"),
                header == HandIn.Header.FINISHED);
    }

    /** Updates the live accepted, return and progress displays in the chest slots without retitling the window. */
    public void refreshLive() {
        final List<HandIn.Offered> current = offered();
        final HandIn.Result sorted = HandIn.sort(current, wanted, stillNeeded);
        final long accepted = sorted.accepted();
        final long totalOffered =
                current.stream().mapToLong(HandIn.Offered::amount).sum();
        final long returned = Math.max(0L, totalOffered - accepted);
        final long notCounted = current.stream()
                .filter(stack ->
                        HandIn.sort(List.of(stack), wanted, Long.MAX_VALUE).accepted() == 0)
                .mapToLong(HandIn.Offered::amount)
                .sum();
        final long surplus = Math.max(0L, returned - notCounted);

        // Tick column (slots 0, 1): live accepted count + wanted materials list.
        final List<Wanted> wantedNames =
                wanted.stream().map(item -> new Wanted(GameKeys.item(item))).toList();
        final GameDocument.Drawn sample = document.item("sample", null, Map.of("wanted", wantedNames));
        final List<Component> tickLore = new ArrayList<>();
        tickLore.add(sample.name());
        tickLore.addAll(sample.lore());
        final ItemStack tickItem = BlankItem.styled(
                BlankItem.silent(), document.labelText("accepted-title", new Window(accepted)), tickLore);
        HandInPanel.TICK_SLOTS.forEach(slot -> inventory.setItem(slot, tickItem));

        // Return column (slots 7, 8): live return count + reason when items go back.
        final ItemStack returnItem;
        if (returned > 0) {
            final List<Component> returnLore = new ArrayList<>();
            if (notCounted > 0) {
                returnLore.add(document.labelText("return-not-counted", new Window(notCounted)));
            }
            if (surplus > 0) {
                returnLore.add(document.labelText("return-surplus", new Window(surplus)));
            }
            returnItem = BlankItem.styled(
                    BlankItem.silent(), document.labelText("return-title", new Window(returned)), returnLore);
        } else {
            returnItem = BlankItem.silent();
        }
        HandInPanel.RETURN_SLOTS.forEach(slot -> inventory.setItem(slot, returnItem));

        // Row 1 (slots 9..17): live progress bar figure outside the title.
        final Component barLabel = accepted > 0
                ? document.labelText("progress-offer", new Progress(amount, target, accepted))
                : document.labelText("progress", new Progress(amount, target, 0));
        final ItemStack barItem = BlankItem.styled(BlankItem.silent(), barLabel, List.of());
        HandInPanel.BAR_SLOTS.forEach(slot -> inventory.setItem(slot, barItem));
    }

    @Override
    protected void closed(final Player player) {
        if (!readOnly) {
            returnEverything(player);
        }
    }

    /** Returns all deposited items when the plugin stops. */
    @Override
    protected void stopped(final Player player) {
        if (!readOnly) {
            returnEverything(player);
        }
    }

    /** Scans the five explicit deposit slots and returns every non-empty stack. */
    public List<HandIn.Offered> offered() {
        final List<HandIn.Offered> out = new ArrayList<>();
        for (final int slot : HandInPanel.DEPOSIT_SLOTS) {
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
            if (!HandInPanel.isDeposit(take.slot())) {
                continue;
            }
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
     * Puts the last {@code unpaid} items of {@code taken} into the deposit slots, as the credit did not count them.
     * What those slots cannot hold, or all of it once the window is closed, goes to the player.
     */
    public void putBack(final Player player, final List<ItemStack> taken, final long unpaid) {
        final List<Integer> back =
                unpaid(taken.stream().map(ItemStack::getAmount).toList(), unpaid);
        for (int index = 0; index < taken.size(); index++) {
            if (back.get(index) > 0) {
                final ItemStack stack = taken.get(index).clone();
                stack.setAmount(back.get(index));
                if (showsTo(player)) {
                    deposit(stack);
                }
                if (stack.getAmount() > 0) {
                    give(player, stack);
                }
            }
        }
    }

    /** Moves as much of {@code stack} as fits into the five deposit slots, leaving any surplus in {@code stack}. */
    private void deposit(final ItemStack stack) {
        // First merge into partial similar stacks inside the five deposit slots.
        for (final int slot : HandInPanel.DEPOSIT_SLOTS) {
            if (stack.getAmount() <= 0) {
                return;
            }
            final @Nullable ItemStack held = inventory.getItem(slot);
            if (held != null && !held.isEmpty() && held.isSimilar(stack)) {
                final int moved = Math.min(stack.getAmount(), held.getMaxStackSize() - held.getAmount());
                if (moved > 0) {
                    held.setAmount(held.getAmount() + moved);
                    inventory.setItem(slot, held);
                    stack.setAmount(stack.getAmount() - moved);
                }
            }
        }
        // Then fill empty slots among the five deposit slots.
        for (final int slot : HandInPanel.DEPOSIT_SLOTS) {
            if (stack.getAmount() <= 0) {
                return;
            }
            final @Nullable ItemStack held = inventory.getItem(slot);
            if (held == null || held.isEmpty()) {
                inventory.setItem(slot, stack.clone());
                stack.setAmount(0);
            }
        }
    }

    /** Returns how many of each of {@code amounts} go back, so that {@code unpaid} go back from the last stack on. */
    static List<Integer> unpaid(final List<Integer> amounts, final long unpaid) {
        final Integer[] back = new Integer[amounts.size()];
        long left = Math.max(0L, unpaid);
        for (int index = amounts.size() - 1; index >= 0; index--) {
            final int backAmount = (int) Math.min(left, amounts.get(index));
            back[index] = backAmount;
            left -= backAmount;
        }
        return List.of(back);
    }

    /** Gives the player the stacks that {@link #apply} removed. */
    public void giveBack(final Player player, final List<ItemStack> stacks) {
        stacks.forEach(stack -> give(player, stack));
    }

    private void returnEverything(final Player player) {
        for (final int slot : HandInPanel.DEPOSIT_SLOTS) {
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
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
    }
}
