package eu.nordtal.season.steward.messages;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Draws a game document for the browser, in MiniMessage, at its declared examples. */
final class GameDrawing {

    private GameDrawing() {}

    /** Draws a menu with the game's renderer, every value, entry and data list item at its declared example. */
    static DocumentsApi.MenuDrawing menu(
            final MessageRenderer renderer, final Documents.Packaged packaged, final Locale language) {
        final GameDocument document = GameDocument.example(renderer, packaged, Viewer.of(language));
        final List<DocumentsApi.MenuPart> parts = new ArrayList<>();
        for (final Part part : packaged.document().parts()) {
            final String slot = part.slot();
            if (slot == null) {
                continue;
            }
            if (part.type() == Part.Type.ITEM) {
                final GameDocument.Drawn drawn = document.exampleItem(slot);
                final List<String> lines = new ArrayList<>();
                lines.add(markup(drawn.name()));
                drawn.lore().forEach(line -> lines.add(markup(line)));
                parts.add(new DocumentsApi.MenuPart(slot, part.type(), lines));
            } else if (part.type() == Part.Type.LABEL) {
                parts.add(new DocumentsApi.MenuPart(slot, part.type(), List.of(document.exampleLabel(slot))));
            }
        }
        return new DocumentsApi.MenuDrawing(markup(document.title()), parts);
    }

    /** Draws a game text's lines with the game's renderer, every value at its declared example. */
    static List<String> text(final MessageRenderer renderer, final Documents.Packaged packaged, final Locale language) {
        return GameDocument.example(renderer, packaged, Viewer.of(language)).lines().stream()
                .map(GameDrawing::markup)
                .toList();
    }

    /**
     * Returns the component in MiniMessage, with each glyph tag as written.
     * Steward has no glyph table, so the renderer leaves the tag as text, and the browser draws the glyph by its name.
     */
    private static String markup(final Component component) {
        return MiniMessage.miniMessage().serialize(named(component)).replace("\\<glyph:", "<glyph:");
    }

    /** Puts each translated name's English fallback in its place: a browser has no client to translate it. */
    private static Component named(final Component component) {
        final Component named = component instanceof final TranslatableComponent translated
                ? Component.text(Objects.requireNonNullElse(translated.fallback(), translated.key()))
                        .style(translated.style())
                        .children(translated.children())
                : component;
        return named.children(named.children().stream().map(GameDrawing::named).toList());
    }
}
