package eu.nordtal.s2.messagerendering;

import eu.nordtal.s2.messages.value.GameContent;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;

/**
 * Turns a line the game wrote, such as a death message or an advancement's title, into a value a message carries.
 * The client still translates it into its reader's language.
 */
public final class GameLines {

    private GameLines() {}

    /**
     * Returns the game's line as content: a translatable line keeps its key and its arguments, any other its text.
     * Styling and hover events are the game's and are not carried.
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
        // Not the game's own line: its text stands as a key no client translates, so it shows as written.
        final String text = text(line);
        return new GameContent(text, text);
    }

    /** Returns the words of a component and its children, such as what a player typed into chat. */
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
