package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Mark;
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

/**
 * A drafted menu for an admin to look at: one row per line of each item and label, in the document's order.
 * A row shows the line at the left and its slot at the right; the menu's own code places its slots, so nothing here
 * stands where the menu draws it.
 */
final class PreviewDialog {

    record Line(@Example("owner") String slot, @Example("2") int line) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "paper-common",
                    "draft-preview",
                    "Admin",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("line", Line.class),
                    Slot.label("pager", Paging.Page.class))
            .titled("Draft preview");

    /** One row: a line of an item or a label, and its slot as the row's value. */
    record Row(String text, String slot) {}

    private static final int EACH = 3;
    private static final int ROW = 264;
    private static final int TOP = 49;

    private final Dialogs dialogs;
    private final GameDocument words;
    private final String name;
    private final List<Row> rows;
    private final Paging paging;

    /** @param words the preview's own document in the admin's language: the draft mark and the narrator's words */
    PreviewDialog(final Dialogs dialogs, final GameDocument drafted, final List<Part> parts, final GameDocument words) {
        this.dialogs = dialogs;
        this.words = words;
        this.name = Dialogs.plain(drafted.title());
        this.rows = rows(drafted, parts, words);
        this.paging = Paging.of(rows.size(), EACH);
    }

    /** Lists each item's name and lore lines and each label, in the document's order. */
    static List<Row> rows(final GameDocument drafted, final List<Part> parts, final GameDocument words) {
        final List<Row> rows = new ArrayList<>();
        for (final Part part : parts) {
            final String slot = part.slot();
            if (slot == null || (part.type() != Part.Type.ITEM && part.type() != Part.Type.LABEL)) {
                continue;
            }
            final GameDocument.Drawn drawn =
                    part.type() == Part.Type.ITEM ? drafted.exampleItem(slot) : drafted.exampleLabelItem(slot);
            final List<Component> lines = new ArrayList<>();
            lines.add(drawn.name());
            lines.addAll(drawn.lore());
            for (int index = 0; index < lines.size(); index++) {
                rows.add(new Row(
                        Dialogs.plain(lines.get(index)),
                        index == 0 ? slot : words.label("line", new Line(slot, index + 1))));
            }
        }
        return List.copyOf(rows);
    }

    void show(final Player player, final int page) {
        player.showDialog(dialog(page));
    }

    private Dialog dialog(final int page) {
        final Dialogs.Send send = dialogs.send();
        final List<DialogBody> body = new ArrayList<>();
        for (final Row row : paging.slice(rows, page)) {
            body.add(Dialogs.line(line(row), ROW));
        }
        if (paging.paged()) {
            final String said = words.label("pager", paging.page(page));
            body.add(Dialogs.line(
                    send.pager(BodyLine.start(), paging, page, said, this::show).build(),
                    Dialogs.pagerWidth(paging, page)));
        }
        final Component title = DialogTitle.of(DialogPanel.DRAFT_PREVIEW_150, TOP)
                .header(Symbol.EYE, name, List.of(Mark.text(Dialogs.plain(words.title()), Colours.SOFT)))
                .build();
        return Dialog.create(builder ->
                builder.empty().base(Dialogs.base(title).body(body).build()).type(Dialogs.bodyOnly()));
    }

    /** A row cut with "…" shows its whole line as its tooltip, and the narrator says it whole. */
    private static Component line(final Row row) {
        final boolean cut = !Letters.cut(row.text(), BodyLine.rowRoom(ROW, false, row.slot()))
                .equals(row.text());
        if (!cut) {
            return BodyLine.start()
                    .row(ROW, null, null, row.text(), row.slot(), Look.NORMAL)
                    .build();
        }
        final BodyLine.Press whole = new BodyLine.Press(null, Tooltip.hover(row.slot(), row.text(), null));
        return BodyLine.start()
                .button(
                        whole,
                        part -> part.row(ROW, null, null, row.text(), row.slot(), Look.NORMAL)
                                .words(row.text()))
                .build();
    }
}
