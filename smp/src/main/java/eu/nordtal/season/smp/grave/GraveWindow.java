package eu.nordtal.season.smp.grave;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/** The window of a grave. Any viewer may take the content items. {@link Graves} handles the close of the window. */
final class GraveWindow extends Menu {

    record Window(@Example("30") long experience) {}

    /** @param player the dead player, {@code null} when the server does not know the name */
    record Owner(
            boolean known,
            @Nullable PlayerContext player,
            @Example("2026-10-23T18:40:00Z") Instant at,
            @Example("-120") int x,
            @Example("64") int y,
            @Example("355") int z) {}

    /** The content rows of the grave the declaration lays out as its example. */
    static final int EXAMPLE_ROWS = 2;

    static final DocumentSpec<Window> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "grave",
                    "Menus",
                    Window.class,
                    GravePanel.rows(EXAMPLE_ROWS),
                    Slot.item("owner", Owner.class).at(List.of(GravePanel.headSlot(EXAMPLE_ROWS))),
                    Slot.label("experience-line"),
                    Slot.label("take-all-button"))
            .titled("Grave");

    private final Graves graves;
    private final UUID graveId;
    private final int contentRows;
    private final @Nullable OpenGrave grave;
    private @Nullable Player closer;

    /** @param experience the header's mark, null for none */
    GraveWindow(
            final Graves graves,
            final UUID graveId,
            final int contentRows,
            final @Nullable OpenGrave grave,
            final String name,
            final @Nullable String experience,
            final String takeAll) {
        this.graves = graves;
        this.graveId = graveId;
        this.contentRows = contentRows;
        this.grave = grave;
        final var _ = frame(GravePanel.rows(contentRows), GravePanel.title(name, contentRows, experience, takeAll));
    }

    /**
     * Opens a grave of {@code reader}'s at the drafted words, with a few stacks in it, for the draft preview.
     * The window counts as awaiting its loot, so no stack moves and Take all only closes it.
     */
    static void example(final Graves graves, final Player reader, final GameDocument drafted, final Instant now) {
        final GraveWindow menu = new GraveWindow(
                graves,
                UUID.randomUUID(),
                EXAMPLE_ROWS,
                null,
                PlainTextComponentSerializer.plainText().serialize(drafted.title()),
                drafted.label("experience-line"),
                drafted.label("take-all-button"));
        final Inventory window = menu.getInventory();
        final List<ItemStack> contents = List.of(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BREAD, 12),
                new ItemStack(Material.OAK_LOG, 32),
                new ItemStack(Material.TORCH, 48),
                new ItemStack(Material.IRON_PICKAXE));
        for (int slot = 0; slot < contents.size(); slot++) {
            window.setItem(slot, contents.get(slot));
        }
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(meta -> {
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(reader);
            }
        });
        final Location at = Objects.requireNonNull(reader.getLocation());
        final GameDocument.Drawn drawn = drafted.item(
                "owner",
                new Owner(
                        true,
                        PlayerContext.of(PlayerId.of(reader.getUniqueId()), reader.getName()),
                        now,
                        at.getBlockX(),
                        at.getBlockY(),
                        at.getBlockZ()));
        window.setItem(GravePanel.headSlot(EXAMPLE_ROWS), BlankItem.styled(head, drawn.name(), drawn.lore()));
        final ItemStack takeAll = BlankItem.silent();
        GravePanel.takeAllSlots(EXAMPLE_ROWS).forEach(slot -> window.setItem(slot, takeAll));
        menu.open(reader);
    }

    UUID graveId() {
        return graveId;
    }

    int contentRows() {
        return contentRows;
    }

    /** Returns the player who closed the window last, or null while no one has. */
    @Nullable
    Player closer() {
        return closer;
    }

    private boolean holdsLoot() {
        return grave != null && grave.holdsLoot();
    }

    /** Allows items to move while the grave holds loot, so none moves once it is emptied or expires. */
    @Override
    protected boolean leavesFree(final int rawSlot) {
        return GravePanel.leavesFree(holdsLoot(), rawSlot, contentRows);
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        if (!GravePanel.takeAllSlots(contentRows).contains(slot)) {
            return MenuClick.nothing();
        }
        if (holdsLoot()) {
            graves.takeAll(player, this);
        }
        // Closing the window lets Graves settle the grave, also if it is empty.
        return MenuClick.closing();
    }

    @Override
    protected void closed(final Player player) {
        closer = player;
        graves.onClosed(player, this);
    }
}
