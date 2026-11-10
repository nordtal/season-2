package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
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
import eu.nordtal.season.papercommon.dialog.Paging;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** The list of a rider in a minecart: the stations the cart reaches, three a page, favourites first. */
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

    static final int EACH = 3;

    /** A row holds any 32-character name and a four-digit distance. */
    static final int ROW = 242;

    static final int WIDTH = ROW + BodyLine.SIDE_GAP + BodyLine.PLATE;

    private static final int PLATE_TOP = 49;
    private static final int TEXT_TOP = 50;

    private RideDialog() {}

    /** The panel for {@code count} stations: a pager line below three rows when they take more than one page. */
    static DialogPanel panel(final int count) {
        if (count == 0) {
            return DialogPanel.RIDE_LIST_33;
        }
        return Paging.of(count, EACH).paged() ? DialogPanel.RIDE_LIST_150 : DialogPanel.RIDE_LIST_114;
    }

    static Dialog of(
            final GameDocument document,
            final List<RideList.Stop> stops,
            final int page,
            final Dialogs.Send send,
            final Answers answers) {
        final Paging paging = Paging.of(stops.size(), EACH);
        final List<DialogBody> body = new ArrayList<>();
        if (stops.isEmpty()) {
            body.add(Dialogs.line(
                    BodyLine.textLine()
                            .text(document.label("none"), Colours.SOFT)
                            .build(),
                    WIDTH));
        }
        for (final RideList.Stop stop : paging.slice(stops, page)) {
            body.add(Dialogs.line(row(document, stop, page, send, answers), WIDTH));
        }
        if (paging.paged()) {
            final String words = document.label("pager", paging.page(page));
            body.add(Dialogs.line(
                    send.pager(BodyLine.start(), paging, page, words, answers::page)
                            .build(),
                    Dialogs.pagerWidth(paging, page)));
        }
        final Component title = DialogTitle.of(panel(stops.size()), stops.isEmpty() ? TEXT_TOP : PLATE_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of())
                .build();
        return Dialog.create(builder ->
                builder.empty().base(Dialogs.base(title).body(body).build()).type(Dialogs.bodyOnly()));
    }

    /** The row rides there, so it is the line's first click; the star after it stars the stop or unstars it. */
    private static Component row(
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
        return BodyLine.start()
                .button(ride, line -> line.row(ROW, null, null, stop.name(), distance, Look.NORMAL))
                .gap(BodyLine.SIDE_GAP)
                .button(star, line -> {
                    line.star(stop.favourite());
                    if (stop.favourite()) {
                        line.words(document.label("favourite"));
                    }
                })
                .build();
    }
}
