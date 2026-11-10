package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
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
    static final String SHOWN = "shown";

    /** The field's frame and the panel's content: the widest part. */
    static final int WIDTH = 184;

    private static final int FIELD_WIDTH = 180;
    private static final int SAVE_WIDTH = 100;
    private static final int TEXT_TOP = 50;
    private static final int FRAME_TOP = 43;
    private static final int FRAME_Y = 61;
    private static final int REFUSAL_Y = 150;

    private StationDialog() {}

    /** The visitor's dialog: the station's name, or that it has none, over how to ride from it. */
    static Dialog visitor(final GameDocument document, final @Nullable String name) {
        final Component title = DialogTitle.of(DialogPanel.STATION_78, TEXT_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of())
                .build();
        final String shown = name == null ? document.label("unnamed") : name;
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title)
                        .body(List.of(
                                Dialogs.line(
                                        BodyLine.textLine()
                                                .text(shown, Colours.INK)
                                                .build(),
                                        WIDTH),
                                // The client wraps the hint at the field's width and centres each row.
                                DialogBody.plainMessage(
                                        BodyLine.textLine()
                                                .text(document.label("hint"), Colours.SOFT)
                                                .build(),
                                        WIDTH)))
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    /**
     * The placer's dialog: the field holds {@code typed} and the checkbox {@code shown}.
     *
     * A refused save's {@code refusal} is drawn by the title under the button, so the narrator reads it there.
     */
    static Dialog placer(
            final GameDocument document,
            final String typed,
            final boolean shown,
            final @Nullable String refusal,
            final Dialogs.Send send,
            final Saved saved) {
        final DialogTitle drawn = DialogTitle.of(
                        refusal == null ? DialogPanel.STATION_104 : DialogPanel.STATION_122, FRAME_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of())
                .fieldFrame(DialogTitle.MARGIN, FRAME_Y);
        if (refusal != null) {
            drawn.centred(REFUSAL_Y, refusal, Colours.REFUSAL);
        }
        final ActionButton save = ActionButton.builder(BodyLine.primaryButton(document.label("save")))
                .width(SAVE_WIDTH)
                .action(send.action((player, response) -> saved.save(
                        player,
                        Objects.requireNonNullElse(response.getText(FIELD), ""),
                        Boolean.TRUE.equals(response.getBoolean(SHOWN)))))
                .build();
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(drawn.build())
                        .inputs(List.of(
                                DialogInput.text(FIELD, Component.text(document.label("field")))
                                        .labelVisible(false)
                                        .width(FIELD_WIDTH)
                                        .maxLength(StationNames.MAX_LENGTH)
                                        .initial(typed)
                                        .build(),
                                DialogInput.bool(
                                                SHOWN,
                                                Component.text(document.label("show"), Colours.INK)
                                                        .shadowColor(ShadowColor.none()))
                                        .initial(shown)
                                        .build()))
                        .build())
                .type(DialogType.multiAction(List.of(save)).columns(1).build()));
    }
}
