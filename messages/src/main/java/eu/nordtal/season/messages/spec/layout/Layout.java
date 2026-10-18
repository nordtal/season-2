package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.messages.spec.Display;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One message as a reader sees it, declared once in the module whose code fills it.
 * It is a tree of parts whose leaves are keys, the code's elements by id and data lists by id.
 *
 * @param name     its identity in its bundle, in kebab case
 * @param bundle   the bundle it is listed under, whose {@code layouts.json} carries it
 * @param area     the group an admin finds it under, named as a section is
 * @param surface  where a reader sees it
 * @param elements what the code fills in, each placed exactly once in the tree
 * @param parts    the tree, top to bottom
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

    /** A Discord message, whose parts are Components V2's. */
    public static Layout discord(
            final String bundle,
            final String name,
            final String area,
            final List<Element> elements,
            final Part... parts) {
        return new Layout(name, bundle, area, Display.Surface.DISCORD, elements, List.of(parts));
    }

    /** Every part of the tree, each before the parts it holds. */
    public List<Part> allParts() {
        final List<Part> all = new ArrayList<>();
        parts.forEach(part -> part.collect(all));
        return all;
    }

    /** Every key it places, in its text blocks and then in its elements, in order and as often as placed. */
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

    /** Every text an editor added, each by language tag: the added lines in order, then the link buttons' labels. */
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
