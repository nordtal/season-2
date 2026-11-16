package eu.nordtal.season.discordrendering;

import java.util.Objects;
import java.util.function.Function;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.emoji.Emoji;

/** Draws a filled select with its document's labels and the member's selected value. */
final class SelectDrawing {
    private SelectDrawing() {}

    static StringSelectMenu draw(
            final DocumentRenderer.FilledSelect filled,
            final String placeholder,
            final Function<DocumentRenderer.Option, String> label) {
        return StringSelectMenu.create(filled.id())
                .setPlaceholder(cut(placeholder, SelectMenu.PLACEHOLDER_MAX_LENGTH))
                .addOptions(filled.options().stream()
                        .limit(SelectMenu.OPTIONS_MAX_AMOUNT)
                        .map(option -> {
                            final SelectOption shown = SelectOption.of(
                                            cut(label.apply(option), SelectOption.LABEL_MAX_LENGTH), option.value())
                                    .withDefault(Objects.equals(option.value(), filled.selected()));
                            return option.emoji() == null
                                    ? shown
                                    : shown.withEmoji(Emoji.fromFormatted(option.emoji()));
                        })
                        .toList())
                .build();
    }

    private static String cut(final String text, final int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
