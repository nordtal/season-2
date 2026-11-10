package eu.nordtal.season.smp.duel;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The dialog in which two players on the negotiation area agree on a kit. {@link Negotiation} redraws it. */
final class NegotiationDialog {

    record Opponent(PlayerContext opponent) {}

    record Redrawn(@Example("Alex picked another kit.") String status) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "duel-negotiation",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("pick-hint"),
                    Slot.label("mine"),
                    Slot.label("alone"),
                    Slot.label("pick"),
                    Slot.label("waiting", Opponent.class),
                    Slot.label("different", Opponent.class),
                    Slot.label("starting"),
                    Slot.label("redrawn", Redrawn.class))
            .titled("Duel negotiation");

    /** The most kits the dialog lists. */
    static final int KITS = 4;

    /** A kit row holds a kit's name and a 16-character opponent; the status line a 16-character name. */
    static final int WIDTH = 192;

    private static final int TOP = 49;

    /**
     * What one player sees.
     *
     * @param mine     the index of the player's pick, or null
     * @param theirs   the index of the opponent's pick, or null
     * @param opponent the opponent, null while the player stands alone
     * @param redrawn  whether the opponent caused this send, so the title says what changed
     */
    record View(
            List<String> kits,
            @Nullable Integer mine,
            @Nullable Integer theirs,
            @Nullable PlayerContext opponent,
            boolean redrawn) {}

    private NegotiationDialog() {}

    /** Returns the dialog; a click on a kit answers {@code picked} with its index. */
    static Dialog of(
            final GameDocument document,
            final View view,
            final Dialogs.Send send,
            final BiConsumer<Player, Integer> picked) {
        final List<DialogBody> body = new ArrayList<>();
        final PlayerContext against = view.opponent();
        final @Nullable String opponent =
                against == null ? null : against.name().name();
        for (int index = 0; index < view.kits().size(); index++) {
            final int kit = index;
            final String name = view.kits().get(index);
            final boolean mine = Integer.valueOf(index).equals(view.mine());
            final @Nullable String theirs = Integer.valueOf(index).equals(view.theirs()) ? opponent : null;
            final BodyLine.Press press = new BodyLine.Press(
                    send.click(player -> picked.accept(player, kit)),
                    Tooltip.hover(name, null, document.label("pick-hint")));
            body.add(Dialogs.line(
                    BodyLine.start()
                            .button(press, line -> {
                                line.row(WIDTH, null, null, name, theirs, mine ? Look.PRIMARY : Look.NORMAL);
                                if (mine) {
                                    line.words(document.label("mine"));
                                }
                            })
                            .build(),
                    WIDTH));
        }
        final String status = status(document, view);
        body.add(Dialogs.line(BodyLine.textLine().text(status, Colours.SOFT).build(), WIDTH));
        final DialogTitle title = DialogTitle.of(DialogPanel.DUEL_178, TOP)
                .header(Symbol.SHIELD, Dialogs.plain(document.title()), List.of());
        if (view.redrawn()) {
            title.words(document.label("redrawn", new Redrawn(status)));
        }
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build()).body(body).build())
                .type(Dialogs.bodyOnly()));
    }

    private static String status(final GameDocument document, final View view) {
        final @Nullable PlayerContext opponent = view.opponent();
        return switch (KitAgreement.of(opponent != null, view.mine(), view.theirs())) {
            case ALONE -> document.label("alone");
            case PICK -> document.label("pick");
            case WAITING -> document.label("waiting", new Opponent(Objects.requireNonNull(opponent)));
            case DIFFERENT -> document.label("different", new Opponent(Objects.requireNonNull(opponent)));
            case AGREED -> document.label("starting");
        };
    }
}
