package eu.nordtal.season.messagerendering;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.Style;

/**
 * Fits a rendered text to a place the game draws line by line.
 * A tooltip takes one line per line; a name, a title or a bar holds one and draws a line break as an unknown character.
 */
public final class Lines {

    private static final TextReplacementConfig SIDE_BY_SIDE =
            TextReplacementConfig.builder().matchLiteral("\n").replacement(" ").build();

    private Lines() {}

    /** Returns the text's lines, each part keeping the style, click and hover it stood in; one line stays as it is. */
    public static List<Component> of(final Component text) {
        if (!breaks(text)) {
            return List.of(text);
        }
        final List<List<Component>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        split(text, Style.empty(), lines);
        return lines.stream()
                .<Component>map(parts -> Component.textOfChildren(parts.toArray(Component[]::new)))
                .toList();
    }

    /** Returns an item's tooltip, its name first: a name's later lines and each lore line's breaks become lines. */
    public static List<Component> tooltip(final Component name, final List<Component> lore) {
        final List<Component> lines = new ArrayList<>(of(name));
        lore.forEach(line -> lines.addAll(of(line)));
        return List.copyOf(lines);
    }

    /** Returns the text on one line, each line break a space. */
    public static Component joined(final Component text) {
        return breaks(text) ? text.replaceText(SIDE_BY_SIDE) : text;
    }

    private static boolean breaks(final Component component) {
        if (component instanceof final TextComponent text && text.content().indexOf('\n') >= 0) {
            return true;
        }
        return component.children().stream().anyMatch(Lines::breaks);
    }

    /** Appends {@code component}'s parts to the last line, starting a new one at each break, each part fully styled. */
    private static void split(final Component component, final Style inherited, final List<List<Component>> lines) {
        final Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof final TextComponent text) {
            final String[] pieces = text.content().split("\n", -1);
            for (int index = 0; index < pieces.length; index++) {
                if (index > 0) {
                    lines.add(new ArrayList<>());
                }
                if (!pieces[index].isEmpty()) {
                    lines.getLast().add(Component.text(pieces[index], style));
                }
            }
        } else {
            // A translatable or a keybind is the client's to draw: it stays whole on the line it starts on.
            lines.getLast().add(component.children(List.of()).style(style));
        }
        for (final Component child : component.children()) {
            split(child, style, lines);
        }
    }
}
