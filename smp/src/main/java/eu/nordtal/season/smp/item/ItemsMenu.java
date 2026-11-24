package eu.nordtal.season.smp.item;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.chat.Previews;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Paginated Nordtal-styled chest menu handing out registered custom items to admins.
 * Left-click gives 1 item; shift-click gives the item's max stack size; overflow drops at the player's feet.
 */
public final class ItemsMenu extends Menu {

    public static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "admin-items",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("previous"),
                    Slot.label("next"))
            .titled("Nordtal items");

    private final CustomItems registry;
    private final GameDocument document;
    private final int page;
    private final boolean readOnly;

    private ItemsMenu(final CustomItems registry, final GameDocument document, final int page, final boolean readOnly) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.document = Objects.requireNonNull(document, "document");
        final int total = registry.all().size();
        final int pages = ItemsPanel.pageCount(total);
        this.page = Math.max(0, Math.min(page, pages - 1));
        this.readOnly = readOnly;
        final String name = PlainTextComponentSerializer.plainText().serialize(document.title());
        final Inventory window = frame(
                ItemsPanel.rows(total),
                ItemsPanel.title(name, total, this.page, document.label("previous"), document.label("next")));
        populate(window);
    }

    /** Opens the first page of the admin items menu for {@code player}. */
    public static void open(final Player player, final CustomItems registry, final GameDocument document) {
        new ItemsMenu(registry, document, 0, false).open(player);
    }

    /** Registers a read-only draft preview example for Steward. */
    public static void examples(
            final BiConsumer<DocumentSpec<?>, Previews.Example> example, final CustomItems registry) {
        example.accept(DOCUMENT, (reader, drafted) -> new ItemsMenu(registry, drafted, 0, true).open(reader));
    }

    private void populate(final Inventory window) {
        final List<CustomItems.Entry> all = registry.all();
        final int total = all.size();
        final int size = ItemsPanel.pageSize(total);
        final int start = page * size;
        final int end = Math.min(total, start + size);
        for (int index = start; index < end; index++) {
            window.setItem(index - start, all.get(index).create());
        }
        if (ItemsPanel.needsPaging(total)) {
            final ItemStack blank = BlankItem.silent();
            ItemsPanel.previousSlots().forEach(slot -> window.setItem(slot, blank));
            ItemsPanel.nextSlots().forEach(slot -> window.setItem(slot, blank));
        }
    }

    @Override
    protected MenuClick click(final Player player, final int slot, final ClickType type) {
        final List<CustomItems.Entry> all = registry.all();
        final int total = all.size();
        if (ItemsPanel.needsPaging(total)) {
            if (ItemsPanel.previousSlots().contains(slot)) {
                if (page > 0) {
                    return MenuClick.opening(new ItemsMenu(registry, document, page - 1, readOnly));
                }
                return MenuClick.nothing();
            }
            if (ItemsPanel.nextSlots().contains(slot)) {
                if (page + 1 < ItemsPanel.pageCount(total)) {
                    return MenuClick.opening(new ItemsMenu(registry, document, page + 1, readOnly));
                }
                return MenuClick.nothing();
            }
        }
        final int index = page * ItemsPanel.pageSize(total) + slot;
        if (slot < 0 || slot >= ItemsPanel.pageSize(total) || index >= total) {
            return MenuClick.nothing();
        }
        final int amount = quantityFor(type, all.get(index).create().getMaxStackSize());
        if (amount <= 0) {
            return MenuClick.nothing();
        }
        if (!readOnly) {
            give(player, all.get(index).create().asQuantity(amount));
        }
        return new MenuClick(Feedback.SELECT, false, null);
    }

    /** Returns how many items {@code click} hands out: 1 for left-click, {@code maxStackSize} for shift-click. */
    static int quantityFor(final ClickType click, final int maxStackSize) {
        return switch (click) {
            case LEFT -> 1;
            case SHIFT_LEFT, SHIFT_RIGHT -> Math.max(1, maxStackSize);
            default -> 0;
        };
    }

    private static void give(final Player player, final ItemStack stack) {
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
    }
}
