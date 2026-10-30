package eu.nordtal.season.smp.duel;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.papercommon.menu.BlankItem;
import eu.nordtal.season.papercommon.menu.Menu;
import eu.nordtal.season.papercommon.menu.MenuClick;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** The window in which two players on the negotiation area agree on a kit. {@link Negotiation} redraws it. */
final class NegotiationWindow extends Menu {

    record Kit(@Example("Sword") String kit) {}

    record Opponent(PlayerContext opponent) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "duel-negotiation",
                    "Menus",
                    DocumentSpec.None.class,
                    NegotiationPanel.ROWS,
                    Slot.item("kit", Kit.class).at(SlotGeometry.rows(0, NegotiationPanel.KIT_ROWS)),
                    Slot.label("alone"),
                    Slot.label("pick"),
                    Slot.label("waiting", Opponent.class),
                    Slot.label("different", Opponent.class),
                    Slot.label("starting"))
            .titled("Duel negotiation");

    /** A kit as the window shows it; {@code icon} is null when the setting names no material. */
    record Shown(String name, @Nullable Material icon) {}

    /**
     * What one viewer sees.
     *
     * @param mine     the index of the viewer's pick, or null
     * @param theirs   the index of the opponent's pick, or null
     * @param opponent the opponent, null while the viewer stands alone
     * @param step     the countdown cells done, of {@code steps}
     */
    record View(
            List<Shown> kits,
            @Nullable Integer mine,
            @Nullable Integer theirs,
            @Nullable PlayerContext opponent,
            int step,
            int steps) {}

    private final Negotiation negotiation;
    private final int kits;

    NegotiationWindow(final Negotiation negotiation, final GameDocument document, final View view) {
        this.negotiation = negotiation;
        this.kits = view.kits().size();
        final KitAgreement agreement = KitAgreement.of(view.opponent() != null, view.mine(), view.theirs());
        final Inventory inventory = frame(
                NegotiationPanel.ROWS,
                NegotiationPanel.title(
                        document.title(),
                        rows(view),
                        status(document, agreement, view.opponent()),
                        agreement == KitAgreement.AGREED ? NegotiationPanel.bar(view.step(), view.steps()) : ""));
        for (int row = 0; row < kits; row++) {
            final Shown kit = view.kits().get(row);
            final GameDocument.Drawn drawn = document.item("kit", new Kit(kit.name()));
            final ItemStack blank = BlankItem.of(drawn);
            final List<Integer> slots = SlotGeometry.rows(row, 1);
            slots.forEach(slot -> inventory.setItem(slot, blank));
            if (kit.icon() != null) {
                inventory.setItem(slots.getFirst(), BlankItem.described(new ItemStack(kit.icon()), drawn));
            }
        }
    }

    private static List<NegotiationPanel.Row> rows(final View view) {
        final List<NegotiationPanel.Row> rows = new ArrayList<>();
        final String opponent =
                view.opponent() == null ? null : view.opponent().name().name();
        for (int index = 0; index < view.kits().size(); index++) {
            final boolean theirs = Integer.valueOf(index).equals(view.theirs());
            rows.add(new NegotiationPanel.Row(
                    view.kits().get(index).name(),
                    theirs ? opponent : null,
                    Integer.valueOf(index).equals(view.mine())));
        }
        return rows;
    }

    private static String status(
            final GameDocument document, final KitAgreement agreement, final @Nullable PlayerContext opponent) {
        return switch (agreement) {
            case ALONE -> document.label("alone");
            case PICK -> document.label("pick");
            case WAITING -> document.label("waiting", new Opponent(Objects.requireNonNull(opponent)));
            case DIFFERENT -> document.label("different", new Opponent(Objects.requireNonNull(opponent)));
            case AGREED -> document.label("starting");
        };
    }

    @Override
    protected MenuClick click(final Player player, final int slot) {
        final int row = SlotGeometry.row(slot);
        if (row >= kits) {
            return MenuClick.nothing();
        }
        negotiation.picked(player, row);
        return MenuClick.nothing();
    }

    @Override
    protected void closed(final Player player) {
        negotiation.closed(player);
    }
}
