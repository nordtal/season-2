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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
                    Slot.list("wanted", Wanted.class),
                    Slot.label("still-needed"),
                    Slot.label("not-counted"),
                    Slot.label("finished"),
                    Slot.label("confirm-button"))
            .titled("Hand in");

    private static final int DEPOSIT_SLOTS = HandInPanel.DEPOSIT_SLOTS;

    private final Inventory inventory;
    private final Objective objective;
    private final Set<String> wanted;
    private final BiConsumer<Player, HandInGui> confirm;
    private final GameDocument document;
    private final String name;
    private long stillNeeded;
    private HandIn.Header header = HandIn.Header.NEEDED;
    // Set from a hand-in that took items until its credit is known, so a second click waits for it.
    private boolean crediting;

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

        this.document =
                new GameDocument(renderer, documents.applied(DOCUMENT), Viewer.of(locale), new Window(stillNeeded));
        this.name = PlainTextComponentSerializer.plainText().serialize(document.title());
        this.inventory = frame(HandInPanel.ROWS, title());

        // The sample names what is wanted in Nordtal's tooltip, so its lines carry no item sprite.
        final List<Wanted> names =
                wanted.stream().map(item -> new Wanted(GameKeys.item(item))).toList();
        final GameDocument.Drawn sample = document.item("sample", null, Map.of("wanted", names));
        sample().ifPresent(material -> inventory.setItem(
                HandInPanel.SAMPLE_SLOT, BlankItem.styled(new ItemStack(material), sample.name(), sample.lore())));

        final ItemStack button = BlankItem.silent();
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> inventory.setItem(slot, button));
    }

    /** Returns a line per item, its texture before its name where the pack's atlases draw it, else its name alone. */
    static List<Wanted> wanted(final java.util.Collection<String> items) {
        return items.stream()
                .map(name -> new Wanted(GameKeys.item(name)
                        .drawn(ItemSprites.of(GameKeys.key(name)).orElse(null))))
                .toList();
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
        if (HandInPanel.CONFIRM_SLOTS.contains(slot) && header != HandIn.Header.FINISHED && !crediting) {
            confirm.accept(player, this);
        }
        return MenuClick.nothing();
    }

    /** Answers a hand-in that took nothing: the header says so when the window holds items. */
    public void refuse(final Player player) {
        show(player, HandIn.refused(offered().isEmpty()));
    }

    /** Marks the items {@link #apply} took as waiting for their credit. */
    public void crediting() {
        crediting = true;
    }

    /** Shows what the credit of a hand-in that took {@code accepted} made of it: {@code paid} counted. */
    public void credited(final Player player, final long paid, final long accepted) {
        crediting = false;
        stillNeeded = Math.max(0L, stillNeeded - Math.max(0L, paid));
        show(player, HandIn.credited(paid, accepted, stillNeeded));
    }

    /** Redraws the header for {@code next}, through a reopen only when its words change. */
    private void show(final Player player, final HandIn.Header next) {
        final String before = mark();
        header = next;
        if (!mark().equals(before)) {
            retitle(player, title());
        }
    }

    private String mark() {
        return switch (header) {
            case NEEDED -> document.label("still-needed", new Window(stillNeeded));
            case NOT_COUNTED -> document.label("not-counted");
            case FINISHED -> document.label("finished");
        };
    }

    private Component title() {
        return HandInPanel.title(name, mark(), document.label("confirm-button"), header == HandIn.Header.FINISHED);
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
