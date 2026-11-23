package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyColumn;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.FocusRing;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.dialog.Paging;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/** The list of a rider in a minecart: the stations the cart reaches, five a page, favourites first. */
final class RideDialog {

    record Stop(
            @Example("Harbour") String name,
            @Example("8925") long distance) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "ride",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("distance", Stop.class),
                    Slot.label("add"),
                    Slot.label("remove"),
                    Slot.label("favourite"),
                    Slot.label("none"),
                    Slot.label("pager", Paging.Page.class))
            .titled("Where to");

    /** What the rider's clicks do: ride to a stop, star or unstar one, turn the page. */
    interface Answers {

        void ride(Player player, RideList.Stop stop, int page);

        void star(Player player, RideList.Stop stop, int page);

        void page(Player player, int page);
    }

    static final int EACH = 5;

    /** A row holds any 32-character name and a five-digit distance on the 280 px panel. */
    static final int ROW = 246;

    static final int WIDTH = ROW + BodyLine.SIDE_GAP + BodyLine.PLATE;

    /** Ten text rows (98 px box) hold the 97 px five-row block with 1 px top slack so the pager sits 7 px below. */
    private static final int PAGED_ROWS = 10;

    private static final int PAGED_TOP = 1;

    /** The pager art sits 7 px under row 5, which is 3 px above its 17 px single-row box (-7 from its text row). */
    private static final int PAGER_DOWN = -7;

    private static final int TEXT_TOP = 50;

    /** Where the empty list's words stand: as a text line's capitals would under the first body line's box. */
    private static final int EMPTY_TEXT = BodyColumn.BODY_TOP + 5;

    private RideDialog() {}

    /** The panel for {@code count} stations: as many rows as a page holds, and a pager 7 px under 5 when paged. */
    static DialogPanel panel(final int count) {
        if (count == 0) {
            return DialogPanel.RIDE_LIST_33;
        }
        return Paging.of(count, EACH).paged() ? DialogPanel.RIDE_LIST_146 : DialogPanel.RIDE_LIST_ROWS.get(count - 1);
    }

    static Dialog of(
            final GameDocument document,
            final List<RideList.Stop> stops,
            final int page,
            final Dialogs.Send send,
            final Answers answers) {
        final Paging paging = Paging.of(stops.size(), EACH);
        final List<DialogBody> body = new ArrayList<>();
        final DialogTitle title;
        if (stops.isEmpty()) {
            title = title(document, stops, TEXT_TOP);
        } else if (paging.paged()) {
            final List<RideList.Stop> shown = paging.slice(stops, page);
            final DialogCanvas canvas = DialogCanvas.exact(
                    WIDTH, EACH * BodyLine.PLATE + (EACH - 1) * BodyLine.SIDE_GAP, PAGED_ROWS, PAGED_TOP);
            for (int index = 0; index < shown.size(); index++) {
                final RideList.Stop stop = shown.get(index);
                canvas.place(0, index * BodyColumn.STEP, true, line -> row(line, document, stop, page, send, answers));
            }
            title = title(document, stops, canvas.panelTop());
            body.add(Dialogs.line(canvas, FocusRing.Ground.BEVEL, title));
            final String words = document.label("pager", paging.page(page));
            body.add(Dialogs.singleLine(
                    send.pager(BodyLine.start(PAGER_DOWN), paging, page, words, answers::page)
                            .build(),
                    Dialogs.pagerWidth(paging, page),
                    FocusRing.Ground.PANEL));
        } else {
            final List<RideList.Stop> shown = paging.slice(stops, page);
            final BodyColumn column = BodyColumn.of(WIDTH, false)
                    .rows(shown.size(), 0, index -> line -> row(line, document, shown.get(index), page, send, answers));
            title = title(document, stops, column.panelTop());
            body.add(Dialogs.line(column, FocusRing.Ground.BEVEL_FOOT, title));
        }
        if (stops.isEmpty()) {
            title.centred(EMPTY_TEXT, document.label("none"), Colours.SOFT);
        }
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build()).body(body).build())
                .type(Dialogs.bodyOnly()));
    }

    private static DialogTitle title(final GameDocument document, final List<RideList.Stop> stops, final int top) {
        return DialogTitle.of(panel(stops.size()), top).header(Symbol.RAIL, Dialogs.plain(document.title()), List.of());
    }

    /** The row rides there, so it is the column's first click; the star after it stars the stop or unstars it. */
    private static void row(
            final BodyLine line,
            final GameDocument document,
            final RideList.Stop stop,
            final int page,
            final Dialogs.Send send,
            final Answers answers) {
        final String distance = document.label("distance", new Stop(stop.name(), stop.distance()));
        final BodyLine.Press ride = new BodyLine.Press(send.click(player -> answers.ride(player, stop, page)), null);
        final BodyLine.Press star = new BodyLine.Press(
                send.click(player -> answers.star(player, stop, page)),
                Tooltip.hover(document.label(stop.favourite() ? "remove" : "add"), null, null));
        line.button(ride, part -> part.row(ROW, null, null, stop.name(), distance, Look.NORMAL))
                .gap(BodyLine.SIDE_GAP)
                .button(star, part -> {
                    part.star(stop.favourite());
                    if (stop.favourite()) {
                        part.words(document.label("favourite"));
                    }
                });
    }
}
