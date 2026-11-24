package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The dialog of a station rail: a visitor reads its name and how to ride from it.
 *
 * Its placer and admins name it and choose whether the map shows it; the spawn's name is fixed by the translations.
 */
final class StationDialog {

    record Most(@Example("32") long most) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "station",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("unnamed"),
                    Slot.label("spawn"),
                    Slot.label("hint"),
                    Slot.label("field"),
                    Slot.label("show"),
                    Slot.label("show-hint"),
                    Slot.label("save"),
                    Slot.label("name-empty"),
                    Slot.label("name-too-long", Most.class),
                    Slot.label("name-taken"))
            .titled("Station");

    /** What the save answers: the typed name and whether the map shows the station. */
    interface Saved {
        void save(Player player, String typed, boolean shown);
    }

    static final String FIELD = "name";

    /** The panel's inside, from margin to margin: the room of every text row. */
    static final int WIDTH = 186;

    /** The visitor's panel top, at screen y. */
    private static final int VISITOR_TOP = 50;

    /** The placer's panel top: the client puts the native field at screen y 63, panel + 20 inside the bezel. */
    private static final int PLACER_TOP = 43;

    /** The first row under the header, at panel + 18. */
    private static final int FIRST_ROW = 18;

    /** From the name's capitals to the hint's: its 8 rows and a 7 px gap. */
    private static final int NAME_TO_HINT = 15;

    /** The refusal's capitals, 7 under the bottom row, which the client puts at panel + 50..69. */
    private static final int REFUSAL_ROW = 76;

    private static final int BEZEL_TOP = 16;

    private static final Pattern PHRASE_BREAK = Pattern.compile("(?<=[,:;]) +");
    private static final int HINT_STEP = 9;

    private StationDialog() {}

    /**
     * The visitor's dialog: the station's name, or that it has none, over how to ride from it.
     * The title draws both, where body lines stood, so the dialog has no focus stop that does nothing. The panel grows
     * by the hint's rows, at most {@link DialogPanel#STATION_VISITOR_ROWS} of them.
     */
    static Dialog visitor(final GameDocument document, final @Nullable String name) {
        final List<DialogPanel> panels = DialogPanel.STATION_VISITOR_ROWS;
        final List<String> hint = phrases(document.label("hint"), WIDTH);
        final int rows = Math.max(1, Math.min(hint.size(), panels.size()));
        final DialogTitle title = DialogTitle.of(panels.get(rows - 1), VISITOR_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of())
                .text(
                        DialogTitle.MARGIN,
                        VISITOR_TOP + FIRST_ROW,
                        fit(name == null ? document.label("unnamed") : name, false),
                        name == null ? Colours.SOFT : Colours.INK);
        for (int index = 0; index < Math.min(hint.size(), rows); index++) {
            title.text(
                    DialogTitle.MARGIN,
                    VISITOR_TOP + FIRST_ROW + NAME_TO_HINT + index * HINT_STEP,
                    fit(hint.get(index), index == rows - 1 && hint.size() > rows),
                    Colours.SOFT);
        }
        return Dialog.create(builder ->
                builder.empty().base(Dialogs.base(title.build()).build()).type(Dialogs.bodyOnly()));
    }

    /**
     * Breaks {@code text} into rows at most {@code room} wide, at its phrases first.
     * A row ends after a comma, a colon or a semicolon where the next phrase does not fit beside it. A phrase wider
     * than a row wraps at its spaces.
     */
    static List<String> phrases(final String text, final int room) {
        final List<String> rows = new ArrayList<>();
        String row = "";
        for (final String phrase : PHRASE_BREAK.splitAsStream(text.strip()).toList()) {
            final String joined = row.isEmpty() ? phrase : row + " " + phrase;
            if (Letters.visibleWidth(joined) <= room) {
                row = joined;
                continue;
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
            final List<String> wrapped = Letters.wrapVisible(phrase, room);
            rows.addAll(wrapped.subList(0, wrapped.size() - 1));
            row = wrapped.get(wrapped.size() - 1);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    /** Returns {@code text} cut to the panel's inside, with "…" where it is too wide or {@code more} follows. */
    private static String fit(final String text, final boolean more) {
        if (!more) {
            return Letters.cut(text, WIDTH);
        }
        final String cut = Letters.cut(text, WIDTH - Letters.width("…"));
        return cut.equals(text) ? cut.stripTrailing() + "…" : cut;
    }

    /**
     * The placer's dialog: the name field sits in a wooden signboard bezel over the map toggle and Save.
     * Clicking the map toggle redraws with the typed text preserved; hovering shows its tooltip.
     */
    static Dialog placer(
            final GameDocument document,
            final String typed,
            final boolean shown,
            final @Nullable String refusal,
            final Dialogs.Send send,
            final Saved toggled,
            final Saved saved) {
        final DialogTitle drawn = DialogTitle.of(
                        refusal == null ? DialogPanel.STATION_78 : DialogPanel.STATION_92, PLACER_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of())
                .stationBezel(DialogTitle.MARGIN, PLACER_TOP + BEZEL_TOP);
        if (refusal != null) {
            drawn.text(DialogTitle.MARGIN, PLACER_TOP + REFUSAL_ROW, Letters.cut(refusal, WIDTH), Colours.REFUSAL);
        }
        final String showLabel = document.label("show");
        final Component mapTooltip = Tooltip.hover(
                Tooltip.rows(showLabel, document.label("show-hint"), null, Tooltip.WRAP_NATIVE),
                Letters.wrap(showLabel, Tooltip.WRAP_NATIVE).size());
        final ActionButton mapToggle = ActionButton.builder(BodyLine.stationMapToggle(shown, showLabel))
                .width(BodyLine.MAP_TOGGLE_BUTTON)
                .tooltip(mapTooltip)
                .action(send.action((player, response) ->
                        toggled.save(player, Objects.requireNonNullElse(response.getText(FIELD), ""), !shown)))
                .build();
        final ActionButton save = ActionButton.builder(BodyLine.primaryButtonOverField(document.label("save")))
                .width(BodyLine.NATIVE_BUTTON)
                .action(send.action((player, response) ->
                        saved.save(player, Objects.requireNonNullElse(response.getText(FIELD), ""), shown)))
                .build();
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(drawn.build())
                        .inputs(List.of(DialogInput.text(FIELD, Component.text(document.label("field")))
                                .labelVisible(false)
                                .width(BodyLine.FIELD)
                                .maxLength(StationNames.MAX_LENGTH)
                                .initial(typed)
                                .build()))
                        .build())
                .type(DialogType.multiAction(List.of(mapToggle, save))
                        .columns(2)
                        .build()));
    }
}
