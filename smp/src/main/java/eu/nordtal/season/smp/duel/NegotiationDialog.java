package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.FocusRing;
import eu.nordtal.season.packrendering.ui.ItemPicture;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.smp.config.DuelLoadoutItemSpec;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The dialog in which two players on the negotiation area agree on a kit, one tile per kit. */
final class NegotiationDialog {

    record Opponent(PlayerContext opponent) {}

    record Redrawn(@Example("Alex picked another kit.") String status) {}

    record Item(
            @Example("64") int amount,
            @Example("item.minecraft.arrow") GameContent item) {}

    record Seconds(@Example("3") long seconds) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "duel-negotiation",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("pick-hint"),
                    Slot.label("loadout", Item.class),
                    Slot.label("alone"),
                    Slot.label("pick"),
                    Slot.label("waiting", Opponent.class),
                    Slot.label("different", Opponent.class),
                    Slot.label("starting", Seconds.class),
                    Slot.label("redrawn", Redrawn.class))
            .titled("Duel kit");

    /** The most kits the dialog shows. */
    static final int KITS = 4;

    private static final DialogPanel PANEL = DialogPanel.COMPACT_116;
    private static final int TILE = ItemPicture.LARGE_FIELD;
    private static final int TILE_Y = 6;
    private static final int TIGHT_GAP = 6;
    private static final int WIDE_GAP = 14;
    private static final int NAME_GAP = 4;
    private static final int FACE = 8;
    private static final int RING = (BodyLine.ACCENT_FRAME - TILE) / 2;
    private static final int FACE_OUT = 2;
    private static final int FACE_UP = 4;
    private static final int FACE_IN = (BodyLine.FACE_FRAME - FACE) / 2;
    private static final int STATUS_Y = 66;
    private static final int BAR_Y = 80;
    private static final int BAR = 140;
    private static final int KEPT_END = 3;

    /** One kit as the dialog shows it: its name in the viewer's language, its icon and its loadout. */
    record Kit(String name, String icon, List<Item> loadout) {}

    /**
     * What one player sees.
     *
     * @param mine     the index of the player's pick, or null
     * @param theirs   the index of the opponent's pick, or null
     * @param opponent the opponent, null while the player stands alone
     * @param seconds  the seconds until the arena once both picked the same kit, else 0
     * @param redrawn  whether the opponent caused this send, so the title says what changed
     */
    record View(
            List<Kit> kits,
            UUID self,
            @Nullable Integer mine,
            @Nullable Integer theirs,
            @Nullable UUID opponentId,
            @Nullable PlayerContext opponent,
            long seconds,
            boolean redrawn) {}

    private NegotiationDialog() {}

    /** Returns the dialog; a click on a kit answers {@code picked} with its index, and none with no {@code send}. */
    static Dialog of(
            final GameDocument document,
            final View view,
            final Dialogs.@Nullable Send send,
            final BiConsumer<Player, Integer> picked) {
        final DialogCanvas canvas = DialogCanvas.of(PANEL);
        final int count = view.kits().size();
        final int gap = count >= KITS ? TIGHT_GAP : WIDE_GAP;
        final int row = count * TILE + Math.max(0, count - 1) * gap;
        final int left = (PANEL.contentWidth() - row) / 2;
        final List<String> names =
                shortNames(view.kits().stream().map(Kit::name).toList());
        for (int kit = 0; kit < count; kit++) {
            tile(canvas, document, view, kit, left + kit * (TILE + gap), names.get(kit), send, picked);
        }
        final String status = status(document, view);
        final String shown = Letters.cut(status, PANEL.contentWidth());
        canvas.text((PANEL.contentWidth() - Letters.visibleWidth(shown)) / 2, STATUS_Y, shown, Colours.SOFT);
        if (view.seconds() > 0) {
            final double done = 1 - view.seconds() / (double) Negotiation.COUNTDOWN_SECONDS;
            canvas.varBar((PANEL.contentWidth() - BAR) / 2, BAR_Y, BAR, done, done, false);
        }
        final DialogTitle title = DialogTitle.of(PANEL, canvas.panelTop())
                .header(Symbol.SHIELD, Dialogs.plain(document.title()), List.of());
        final List<DialogBody> body = List.of(Dialogs.line(canvas, FocusRing.Ground.BEVEL_FOOT, title));
        if (view.redrawn()) {
            title.words(document.label("redrawn", new Redrawn(status)));
        }
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build()).body(body).build())
                .type(Dialogs.bodyOnly()));
    }

    private static void tile(
            final DialogCanvas canvas,
            final GameDocument document,
            final View view,
            final int kit,
            final int x,
            final String name,
            final Dialogs.@Nullable Send send,
            final BiConsumer<Player, Integer> picked) {
        final Kit shown = view.kits().get(kit);
        final List<Tooltip.Row> rows = new ArrayList<>(Tooltip.rows(shown.name(), null, null, Tooltip.WRAP));
        final int titleRows = rows.size();
        shown.loadout()
                .forEach(item -> rows.add(new Tooltip.Row(document.label("loadout", item), Colours.TOOLTIP_TEXT)));
        if (send != null) {
            rows.add(new Tooltip.Row(document.label("pick-hint"), Colours.HINT));
        }
        final BodyLine.Press press = new BodyLine.Press(
                send == null ? null : send.click(player -> picked.accept(player, kit)), Tooltip.hover(rows, titleRows));
        final boolean mine = Integer.valueOf(kit).equals(view.mine());
        final boolean theirs = Integer.valueOf(kit).equals(view.theirs());
        if (mine && theirs) {
            canvas.place(x - RING, TILE_Y - RING, true, BodyLine::accentFrame);
        }
        canvas.place(x, TILE_Y, true, line -> line.button(press, part -> part.darkField(TILE)));
        final int middle = (TILE - FACE) / 2;
        canvas.itemPicture(
                x + middle, TILE_Y + middle, ItemPicture.of(shown.icon(), Symbol.SWORDS, Colours.FAINT), press);
        if (mine) {
            face(canvas, x - FACE_OUT, view.self());
        }
        final @Nullable UUID opponent = view.opponentId();
        if (theirs && opponent != null) {
            face(canvas, x + TILE + FACE_OUT - BodyLine.FACE_FRAME, opponent);
        }
        final int nameX = x + (TILE - Letters.visibleWidth(name)) / 2;
        final int nameY = TILE_Y + TILE + NAME_GAP;
        if (mine && theirs) {
            canvas.bold(nameX, nameY, name, Colours.ACCENT);
        } else {
            canvas.text(nameX, nameY, name, mine ? Colours.INK : Colours.SOFT);
        }
    }

    /** Draws {@code player}'s face on its plate, whose corner lies at {@code x}, {@link #FACE_UP} above the tile. */
    private static void face(final DialogCanvas canvas, final int x, final UUID player) {
        canvas.place(x, TILE_Y - FACE_UP, true, BodyLine::faceFrame);
        canvas.place(x + FACE_IN, TILE_Y - FACE_UP + FACE_IN, false, line -> line.face(player));
    }

    /**
     * Returns each name cut to a tile's width; names whose cut would read the same end in their last three letters.
     */
    static List<String> shortNames(final List<String> names) {
        final List<String> cut =
                names.stream().map(name -> Letters.cut(name, TILE)).toList();
        final List<String> shown = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            final String name = names.get(index);
            final boolean similar =
                    !cut.get(index).equals(name) && cut.indexOf(cut.get(index)) != cut.lastIndexOf(cut.get(index));
            shown.add(similar ? keepEnd(name) : cut.get(index));
        }
        return List.copyOf(shown);
    }

    /** Returns the longest start of {@code name}, an ellipsis and its last three letters that fit a tile. */
    private static String keepEnd(final String name) {
        final String end = name.substring(name.offsetByCodePoints(name.length(), -KEPT_END));
        String start = name.substring(0, name.length() - end.length());
        while (!start.isEmpty() && Letters.visibleWidth(start.stripTrailing() + Letters.ELLIPSIS + end) > TILE) {
            start = start.substring(0, start.offsetByCodePoints(start.length(), -1));
        }
        return start.stripTrailing() + Letters.ELLIPSIS + end;
    }

    /** Returns the loadout lines of a kit's items, for its tooltip. */
    static List<Item> loadout(final List<DuelLoadoutItemSpec> items) {
        return items.stream()
                .map(item -> new Item(Math.max(1, item.amount()), GameKeys.item(item.item())))
                .toList();
    }

    /** Shows a read-only kit dialog at its example state in {@code drafted}'s words, for Steward's draft preview. */
    static void example(final GameDocument drafted, final Player reader, final List<Kit> kits) {
        final UUID alex = UUID.nameUUIDFromBytes("Alex".getBytes(StandardCharsets.UTF_8));
        final View view = new View(
                kits,
                reader.getUniqueId(),
                kits.isEmpty() ? null : 0,
                kits.isEmpty() ? null : 0,
                alex,
                PlayerContext.of(PlayerId.of(alex), "Alex"),
                2,
                false);
        reader.showDialog(of(drafted, view, null, (player, kit) -> {}));
    }

    private static String status(final GameDocument document, final View view) {
        final @Nullable PlayerContext opponent = view.opponent();
        return switch (KitAgreement.of(opponent != null, view.mine(), view.theirs())) {
            case ALONE -> document.label("alone");
            case PICK -> document.label("pick");
            case WAITING -> document.label("waiting", new Opponent(Objects.requireNonNull(opponent)));
            case DIFFERENT -> document.label("different", new Opponent(Objects.requireNonNull(opponent)));
            case AGREED -> document.label("starting", new Seconds(Math.max(1, view.seconds())));
        };
    }
}
