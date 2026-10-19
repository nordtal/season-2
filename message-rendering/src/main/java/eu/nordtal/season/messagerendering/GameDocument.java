package eu.nordtal.season.messagerendering;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Condition;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Line;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.messages.document.Text;
import eu.nordtal.season.messages.document.Value;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.text.PlainText;
import eu.nordtal.season.messages.value.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Draws a game document for one viewer.
 * That is a text's lines, or a menu's window title, each item's name and lore, and each label.
 */
public final class GameDocument {

    /** An item as a menu shows it. */
    public record Drawn(Component name, List<Component> lore) {

        public Drawn {
            lore = List.copyOf(lore);
        }
    }

    private final MessageRenderer renderer;
    private final Documents.Packaged packaged;
    private final Viewer viewer;
    private final Map<String, Object> values;
    /** The kind of every value a text of the document may name, an entry's and a data list's item's too. */
    private final Map<String, Kind> kinds;

    public GameDocument(
            final MessageRenderer renderer,
            final Documents.Packaged packaged,
            final Viewer viewer,
            final Record values) {
        this(renderer, packaged, viewer, Value.read(values));
    }

    public GameDocument(
            final MessageRenderer renderer,
            final Documents.Packaged packaged,
            final Viewer viewer,
            final Map<String, Object> values) {
        this.renderer = renderer;
        this.packaged = packaged;
        this.viewer = viewer;
        this.values = Map.copyOf(values);
        final Map<String, Value> own = new LinkedHashMap<>();
        packaged.declaration().slots().forEach(slot -> own.putAll(slot.values()));
        this.kinds = packaged.declaration().kinds(own);
    }

    /** Returns the lines of every text block that its values show, one under the other. */
    public Component text() {
        return Component.join(JoinConfiguration.newlines(), lines());
    }

    /** Returns the lines of every text block that its values show, in order. */
    public List<Component> lines() {
        final List<Component> lines = new ArrayList<>();
        for (final Part part : packaged.document().parts()) {
            for (final Line line : shown(part, Map.of())) {
                lines.add(markup(line.text(), Map.of()));
            }
        }
        return lines;
    }

    public Component title() {
        return markup(
                packaged.document().parts().getFirst().allLines().getFirst().text(), Map.of());
    }

    public Drawn item(final String slot) {
        return item(slot, null, Map.of());
    }

    public Drawn item(final String slot, final @Nullable Record entry) {
        return item(slot, entry, Map.of());
    }

    /**
     * @param entry the values of this entry of an item drawn per entry, else {@code null}
     * @param lists the items of each data list its lore holds, by slot
     */
    public Drawn item(
            final String slot, final @Nullable Record entry, final Map<String, List<? extends Record>> lists) {
        final Map<String, List<Map<String, Object>>> read = new LinkedHashMap<>();
        lists.forEach(
                (id, items) -> read.put(id, items.stream().map(Value::read).toList()));
        return itemOf(slot, entry == null ? Map.of() : Value.read(entry), read);
    }

    /** Draws the item with its entry's values and each data list's items as values by name, as a preview fills them. */
    public Drawn itemOf(
            final String slot, final Map<String, Object> own, final Map<String, List<Map<String, Object>>> lists) {
        final List<Component> lines = new ArrayList<>();
        for (final Line line : shown(part(slot, Part.Type.ITEM), own)) {
            if (line.list() == null) {
                lines.add(markup(line.text(), own));
                continue;
            }
            for (final Map<String, Object> item : lists.getOrDefault(line.list(), List.of())) {
                final Map<String, Object> both = new LinkedHashMap<>(own);
                both.putAll(item);
                lines.add(markup(line.text(), both));
            }
        }
        return lines.isEmpty()
                ? new Drawn(Component.empty(), List.of())
                : new Drawn(lines.getFirst(), lines.subList(1, lines.size()));
    }

    public String label(final String slot) {
        return label(slot, null);
    }

    /** Returns the label as plain text, which the window paints in its own font. */
    public String label(final String slot, final @Nullable Record entry) {
        return labelOf(slot, entry == null ? Map.of() : Value.read(entry));
    }

    /** Paints the label with its entry's values by name, as a preview fills them. */
    public String labelOf(final String slot, final Map<String, Object> own) {
        final List<Line> lines = shown(part(slot, Part.Type.LABEL), own);
        if (lines.isEmpty()) {
            return "";
        }
        final Messages.Prepared prepared = renderer.raw()
                .prepare(viewer, pick(lines.getFirst().text()), all(own), kinds, TextFormat.PLAIN, source());
        return PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words());
    }

    private @Nullable Part part(final String slot, final Part.Type type) {
        return packaged.document().parts().stream()
                .filter(part -> part.type() == type && slot.equals(part.slot()))
                .findFirst()
                .orElse(null);
    }

    private List<Line> shown(final @Nullable Part part, final Map<String, Object> own) {
        if (part == null) {
            return List.of();
        }
        final Map<String, Object> all = all(own);
        return part.allLines().stream()
                .filter(line -> Condition.holds(line.when(), all))
                .toList();
    }

    private Component markup(final Text text, final Map<String, Object> own) {
        return renderer.format(viewer, pick(text), all(own), kinds, source());
    }

    private Map<String, Object> all(final Map<String, Object> own) {
        final Map<String, Object> all = new LinkedHashMap<>(values);
        all.putAll(own);
        return all;
    }

    private String pick(final Text text) {
        return text.pick(Locales.tag(viewer.language()), ThreadLocalRandom.current());
    }

    private String source() {
        return packaged.declaration().id();
    }
}
