package eu.nordtal.season.smp.rail;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.jspecify.annotations.Nullable;

/** Builds the dialog that names a station, from the labels of the station document. */
final class StationDialog {

    /** The labels the dialog shows, each already rendered for its viewer. */
    record Texts(String title, @Nullable String refusal, String rules, String field, String save, String cancel) {}

    private static final String FIELD = "name";

    /** How long a naming dialog left open still takes its answer. */
    private static final Duration ANSWERED_WITHIN = Duration.ofMinutes(10);

    private StationDialog() {}

    /** Returns the dialog; {@code answered} gets the typed name off the main thread. */
    static Dialog of(final Texts texts, final String initial, final Consumer<String> answered) {
        final List<DialogBody> body = new ArrayList<>();
        final String refusal = texts.refusal();
        if (refusal != null) {
            body.add(DialogBody.plainMessage(Component.text(refusal)));
        }
        body.add(DialogBody.plainMessage(Component.text(texts.rules())));
        final DialogInput field = DialogInput.text(FIELD, Component.text(texts.field()))
                .maxLength(StationNames.MAX_LENGTH)
                .initial(initial)
                .width(250)
                .build();
        final ActionButton save = ActionButton.builder(Component.text(texts.save()))
                .action(DialogAction.customClick(
                        (response, _) -> {
                            final @Nullable String answer = response.getText(FIELD);
                            answered.accept(answer == null ? "" : answer);
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(ANSWERED_WITHIN)
                                .build()))
                .build();
        final ActionButton cancel =
                ActionButton.builder(Component.text(texts.cancel())).build();
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(texts.title()))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .inputs(List.of(field))
                        .build())
                .type(DialogType.confirmation(save, cancel)));
    }
}
