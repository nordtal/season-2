package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.value.GameContent;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;

/** Converts a line the game wrote, such as a death message, to a {@link GameContent}. */
public final class GameLines {

    private GameLines() {}

    /** Returns the line as content.
     * A translatable line keeps its key and arguments, any other line keeps its text.
     * Styling and hover events are dropped.
     */
    public static GameContent of(final Component line) {
        if (line instanceof final TranslatableComponent translatable) {
            final List<Object> args = new ArrayList<>(translatable.arguments().size());
            for (final TranslationArgument argument : translatable.arguments()) {
                final Component inner = argument.asComponent();
                args.add(inner instanceof TranslatableComponent ? of(inner) : text(inner));
            }
            final String fallback = translatable.fallback();
            return new GameContent(translatable.key(), fallback == null ? translatable.key() : fallback, args);
        }
        // The text serves as the key, which no client translates.
        final String text = text(line);
        return new GameContent(text, text);
    }

    /** Returns the text of a component and its children. */
    public static String text(final Component component) {
        final StringBuilder out = new StringBuilder();
        plain(component, out);
        return out.toString();
    }

    private static void plain(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        } else if (component instanceof final TranslatableComponent translatable) {
            final String fallback = translatable.fallback();
            out.append(fallback == null ? translatable.key() : fallback);
        }
        for (final Component child : component.children()) {
            plain(child, out);
        }
    }
}
