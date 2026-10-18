package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.messages.spec.Display;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Describes one message as a reader sees it, declared once in the module whose code fills it.
 * The leaves of its tree of parts are keys, the code's elements by id and data lists by id.
 *
 * @param name the identity in its bundle, in kebab case
 * @param bundle the bundle whose {@code layouts.json} lists it
 * @param area the group an admin finds it under, named as a section is
 * @param elements what the code fills in, each placed exactly once in the tree
 */
public record Layout(
        String name, String bundle, String area, Display.Surface surface, List<Element> elements, List<Part> parts) {

    public Layout {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(surface, "surface");
        elements = List.copyOf(elements);
        parts = List.copyOf(parts);
    }

    public static Layout discord(
            final String bundle,
            final String name,
            final String area,
            final List<Element> elements,
            final Part... parts) {
        return new Layout(name, bundle, area, Display.Surface.DISCORD, elements, List.of(parts));
    }

    /** Creates a modal layout with its title, then each field in a row of its own.
     * Only its texts change, never its tree.
     */
    public static Layout modal(
            final String bundle, final String name, final String area, final String title, final Element... fields) {
        final List<Part> parts = new ArrayList<>(List.of(Part.text(Line.title(title))));
        for (final Element field : fields) {
            parts.add(Part.row(field.id()));
        }
        return new Layout(name, bundle, area, Display.Surface.DISCORD, List.of(fields), parts);
    }

    public boolean modal() {
        return elements.stream().anyMatch(element -> element.kind() == Element.Kind.INPUT);
    }

    /** Returns every part, each before the parts it holds. */
    public List<Part> allParts() {
        final List<Part> all = new ArrayList<>();
        parts.forEach(part -> part.collect(all));
        return all;
    }

    /** Returns every key in the order placed and as often as placed. */
    public List<String> keys() {
        final List<String> keys = new ArrayList<>();
        for (final Part part : allParts()) {
            for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                if (line.key() != null) {
                    keys.add(line.key());
                }
            }
        }
        elements.forEach(element -> keys.addAll(element.keys()));
        return keys;
    }

    public Layout rebuilt(final List<Part> rebuiltParts, final List<Element> links) {
        final List<Element> all = new ArrayList<>(elements);
        all.addAll(links);
        return new Layout(name, bundle, area, surface, all, rebuiltParts);
    }

    public List<Map<String, String>> addedTexts() {
        final List<Map<String, String>> texts = new ArrayList<>();
        for (final Part part : allParts()) {
            for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                if (line.texts() != null) {
                    texts.add(line.texts());
                }
            }
        }
        for (final Element element : elements) {
            if (element.labels() != null) {
                texts.add(element.labels());
            }
        }
        return texts;
    }
}
